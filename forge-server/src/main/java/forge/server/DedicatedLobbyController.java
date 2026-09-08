package forge.server;

import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameType;
import forge.ai.AIOption;
import forge.ai.AiProfileUtil;
import forge.gamemodes.match.GameLobby.GameStartError;
import forge.gamemodes.match.DedicatedMatchLifecycle;
import forge.gamemodes.match.HostedMatch;
import forge.gamemodes.match.LobbySlot;
import forge.gamemodes.match.LobbySlotType;
import forge.gamemodes.net.event.MessageEvent;
import forge.gamemodes.net.server.*;
import forge.player.PlayerControllerHuman;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import javax.swing.SwingUtilities;
import java.util.*;

/** The EDT owns room state. Game mutations run through Forge's game executor. */
public final class DedicatedLobbyController implements DedicatedServerPolicy {
    public enum State { WAITING, COUNTDOWN, STARTING, PLAYING, POSTGAME, RESETTING, STOPPING }
    private final ServerConfig config;
    private final ServerGameLobby lobby;
    private final FServerManager server;
    private volatile ServerConfig.LobbyRules rules;
    private volatile State state = State.WAITING;
    private long deadline;
    private long emptyDeadline;
    private final Map<RemoteClient, Long> disconnected = new HashMap<>();
    private final Map<Integer, AiSlotConfiguration> aiSlots = new HashMap<>();
    private volatile HostedMatch current;
    private boolean updateQueued;
    private long generation;

    public DedicatedLobbyController(ServerConfig config, ServerGameLobby lobby, FServerManager server) {
        this.config = config;
        this.lobby = lobby;
        this.server = server;
        this.rules = config.rules();
        lobby.setStartErrorHandler(error -> say(error.message()));
    }
    public State state() { return state; }
    public ServerConfig.LobbyRules rules() { return rules; }
    public int connectedPlayerCount() { return server.connectedPlayers().size(); }
    public int seatCapacity() { return lobby.getNumberOfSlots(); }
    public record AiSlotConfiguration(int slot, String name, String deckId, String profile, String simulation) { }
    public record AiSlotView(int slot, String type, String name, String deckId, String profile, String simulation) { }
    public record DisconnectedPlayerView(int slot, String name, Long reconnectSecondsRemaining) { }
    public record AiResult(boolean success, String code, String message) {
        static AiResult ok() { return new AiResult(true, "ok", ""); }
        static AiResult failure(String code, String message) { return new AiResult(false, code, message); }
    }
    public record ActionResult(boolean success, String code, String message) {
        static ActionResult ok() { return new ActionResult(true, "ok", ""); }
        static ActionResult failure(String code, String message) { return new ActionResult(false, code, message); }
    }
    private static long now() { return System.nanoTime() / 1_000_000; }
    private void transition(State next) {
        if (state != next) { System.out.println("[server] " + state + " -> " + next); state = next; }
    }
    private void say(String message) { server.broadcast(new MessageEvent(message)); }
    @Override public boolean acceptsNewPlayers() { return acceptsLobbyChanges(); }
    @Override public boolean acceptsLobbyChanges() { return state == State.WAITING || state == State.COUNTDOWN; }
    @Override public boolean acceptsGameActions() { return state == State.PLAYING || state == State.POSTGAME; }
    @Override public boolean allowsPlayer(String name) { return config.allowsPlayer(name); }
    @Override public int loginFailureLimit() { return config.loginFailureLimit(); }
    @Override public int loginFailureWindowSeconds() { return config.loginFailureWindowSeconds(); }
    @Override public int loginBlockSeconds() { return config.loginBlockSeconds(); }

    /** Coalesce notifications so a multi-field client update is processed atomically. */
    public void lobbyChanged() {
        if (updateQueued) { return; }
        updateQueued = true;
        SwingUtilities.invokeLater(() -> {
            updateQueued = false;
            if (current != null && lobby.getHostedMatch() == null) { reset(); }
            if (!acceptsLobbyChanges()) { return; }
            if (state == State.COUNTDOWN) { say("Start countdown cancelled."); }
            transition(State.WAITING);
            if (ready()) {
                deadline = now() + config.startDelaySeconds() * 1000L;
                transition(State.COUNTDOWN);
                say("Everyone is ready. Starting in " + config.startDelaySeconds() + " seconds.");
            }
        });
    }
    private boolean ready() {
        List<RemoteClient> players = server.connectedPlayers();
        if (players.size() < 2) { return false; }
        for (RemoteClient client : players) {
            if (!lobby.getSlot(client.getIndex()).isReady()) { return false; }
        }
        List<GameStartError> errors = lobby.validateDedicatedStart(rules.baseGameType());
        if (!errors.isEmpty()) {
            for (GameStartError error : errors) {
                say(error.message());
                if (error.slot() >= 0) { lobby.getSlot(error.slot()).setIsReady(false); }
            }
            server.updateLobbyState();
            return false;
        }
        return true;
    }
    @Override public void connectionsChanged() {
        if (!server.connectedPlayers().isEmpty()) { emptyDeadline = 0; }
        lobbyChanged();
    }
    @Override public void disconnected(RemoteClient client) {
        if (!acceptsLobbyChanges() && state != State.STOPPING) {
            disconnected.put(client, now() + config.reconnectSeconds() * 1000L);
            say(client.getUsername() + " disconnected. Reconnect within " + config.reconnectSeconds() + " seconds.");
            if (server.connectedPlayers().isEmpty()) { emptyDeadline = now() + config.reconnectSeconds() * 1000L; }
        }
    }
    @Override public void reconnected(RemoteClient client) { disconnected.remove(client); emptyDeadline = 0; }

    public void attachMatch(HostedMatch match) {
        current = match;
        long epoch = ++generation;
        // A remote GUI can issue a command as soon as it has received its view.
        // Do not expose a playable room until Match.prepareAllZones has run;
        // otherwise an immediate concede can race initial zone preparation.
        match.setDedicatedLifecycle(new DedicatedMatchLifecycle() {
            private boolean current() {
                return epoch == generation && DedicatedLobbyController.this.current == match
                        && state != State.STOPPING;
            }

            @Override public void gameStarting() {
                if (current() && state == State.POSTGAME) {
                    deadline = 0;
                    transition(State.STARTING);
                }
            }

            @Override public void gamePrepared() {
                runOnEdtAndWait(() -> {
                    if (current() && state == State.STARTING) { transition(State.PLAYING); }
                });
            }

            @Override public void gameFinished() {
                if (!current()) { return; }
                if (state == State.RESETTING) { match.finishDedicatedMatch(); return; }
                transition(State.POSTGAME);
                deadline = now() + config.postgameSeconds() * 1000L;
            }
        });
    }

    private static void runOnEdtAndWait(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(action);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new IllegalStateException("Unable to update dedicated match state", e.getCause());
        }
    }
    public void tick() {
        long time = now();
        if (state == State.COUNTDOWN && time >= deadline && !updateQueued) {
            if (!ready()) { transition(State.WAITING); return; }
            transition(State.STARTING);
            Runnable start = lobby.startGame();
            if (start == null) {
                for (int i = 0; i < lobby.getNumberOfSlots(); i++) { lobby.getSlot(i).setIsReady(false); }
                transition(State.WAITING);
                server.updateLobbyState();
            } else { start.run(); }
        }
        if (state == State.STOPPING || state == State.RESETTING) { return; }
        if (emptyDeadline != 0 && time >= emptyDeadline) { abort("Room abandoned; resetting."); return; }
        for (RemoteClient client : List.copyOf(disconnected.keySet())) {
            if (time >= disconnected.get(client)) {
                takeOverDisconnectedPlayer(client, client.getUsername() + " did not reconnect; AI takes over.");
            }
        }
        if (state == State.POSTGAME && time >= deadline) {
            say("Postgame decision timed out; returning to lobby.");
            current.finishDedicatedMatch();
        }
    }
    public void abort(String reason) {
        if (state == State.RESETTING || state == State.STOPPING) { return; }
        say(reason);
        transition(State.RESETTING);
        HostedMatch match = current;
        if (match == null || match.getGame() == null || match.getGame().isGameOver()) {
            if (match != null) { match.finishDedicatedMatch(); } else { reset(); }
            return;
        }
        Game game = match.getGame();
        // The game worker can currently be blocked in a synchronous remote
        // GUI call. Release it before queuing a terminal game action.
        server.cancelDedicatedReplies();
        game.getAction().invoke(() -> {
            if (current != match) { return; }
            game.setGameOver(GameEndReason.Draw);
            for (PlayerControllerHuman human : List.copyOf(match.getHumanControllers())) {
                human.getInputQueue().onGameOver(true);
            }
        });
    }
    private void reset() {
        if (state == State.STOPPING) { return; }
        transition(State.RESETTING);
        ++generation;
        current = null;
        disconnected.clear();
        emptyDeadline = 0;
        server.resetDedicatedConnections();
        Set<Integer> occupied = new HashSet<>();
        server.connectedPlayers().forEach(c -> occupied.add(c.getIndex()));
        for (int i = 0; i < lobby.getNumberOfSlots(); i++) {
            if (aiSlots.containsKey(i)) {
                lobby.getSlot(i).setIsReady(false);
            } else if (!occupied.contains(i)) { lobby.disconnectPlayer(i); }
            else {
                LobbySlot slot = lobby.getSlot(i);
                slot.setType(LobbySlotType.REMOTE);
                slot.setIsReady(false);
            }
        }
        transition(State.WAITING);
        server.updateLobbyState();
    }
    public void stopping() { transition(State.STOPPING); ++generation; }

    /** Lists players awaiting reconnection. A null deadline means an administrator disabled takeover. */
    public List<DisconnectedPlayerView> disconnectedPlayerViews() {
        return callOnEdt(() -> {
            long currentTime = now();
            List<DisconnectedPlayerView> views = new ArrayList<>();
            for (Map.Entry<RemoteClient, Long> entry : disconnected.entrySet()) {
                Long deadline = entry.getValue() == Long.MAX_VALUE ? null
                        : Math.max(0, (entry.getValue() - currentTime + 999) / 1000);
                views.add(new DisconnectedPlayerView(entry.getKey().getIndex() + 1,
                        entry.getKey().getUsername(), deadline));
            }
            return views.stream().sorted(Comparator.comparingInt(DisconnectedPlayerView::slot)).toList();
        });
    }

    /** Sends a server-labelled announcement to all connected players. */
    public ActionResult announce(String message) {
        if (message == null || message.isBlank() || message.length() > 500) {
            return ActionResult.failure("invalid_message", "message must be 1 to 500 characters.");
        }
        return callOnEdt(() -> {
            if (state == State.STOPPING) { return ActionResult.failure("server_stopping", "Server is stopping."); }
            say("Server: " + message.trim());
            return ActionResult.ok();
        });
    }

    /** Returns the current room to its lobby, ending an active game as a draw. */
    public ActionResult abortByAdministrator() {
        return callOnEdt(() -> {
            if (state == State.STOPPING) { return ActionResult.failure("server_stopping", "Server is stopping."); }
            abort("Room reset by administrator.");
            return ActionResult.ok();
        });
    }

    /** Immediately hands an awaiting disconnected player's seat to AI. */
    public ActionResult takeOverDisconnectedPlayer(int oneBasedSlot) {
        return callOnEdt(() -> {
            if (state == State.STOPPING) { return ActionResult.failure("server_stopping", "Server is stopping."); }
            RemoteClient client = disconnected.keySet().stream()
                    .filter(candidate -> candidate.getIndex() == oneBasedSlot - 1).findFirst().orElse(null);
            if (client == null) { return ActionResult.failure("not_disconnected", "No disconnected player occupies that slot."); }
            takeOverDisconnectedPlayer(client, client.getUsername() + " was replaced with AI by an administrator.");
            return ActionResult.ok();
        });
    }

    /** Prevents automatic AI takeover while another player remains connected. */
    public ActionResult waitIndefinitelyForDisconnectedPlayer(int oneBasedSlot) {
        return callOnEdt(() -> {
            if (state == State.STOPPING) { return ActionResult.failure("server_stopping", "Server is stopping."); }
            RemoteClient client = disconnected.keySet().stream()
                    .filter(candidate -> candidate.getIndex() == oneBasedSlot - 1).findFirst().orElse(null);
            if (client == null) { return ActionResult.failure("not_disconnected", "No disconnected player occupies that slot."); }
            disconnected.put(client, Long.MAX_VALUE);
            say("Server: reconnect timeout disabled for " + client.getUsername() + ".");
            return ActionResult.ok();
        });
    }

    private void takeOverDisconnectedPlayer(RemoteClient client, String message) {
        disconnected.remove(client);
        server.forgetDisconnected(client);
        HostedMatch match = current;
        if (match == null || match.getGame() == null) { return; }
        PlayerControllerHuman controller = match.getHumanControllers().stream()
                .filter(candidate -> candidate.getGui() == client.getGui()).findFirst().orElse(null);
        if (controller == null) { return; }
        say(message);
        match.getGame().getAction().invoke(() -> {
            if (current == match) { match.replaceDedicatedPlayer(controller); }
        });
    }

    /** Applies a complete rule set on the EDT. Admin callers receive false outside a stable lobby. */
    public boolean updateRules(ServerConfig.LobbyRules next) {
        if (SwingUtilities.isEventDispatchThread()) { return updateRulesOnEdt(next); }
        final java.util.concurrent.atomic.AtomicBoolean changed = new java.util.concurrent.atomic.AtomicBoolean();
        try {
            SwingUtilities.invokeAndWait(() -> changed.set(updateRulesOnEdt(next)));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to update dedicated lobby rules", e);
        }
        return changed.get();
    }

    private boolean updateRulesOnEdt(ServerConfig.LobbyRules next) {
        if (state != State.WAITING) { return false; }
        if (!aiSlots.isEmpty() && next.mode() != rules.mode()) { return false; }
        applyRules(lobby, next);
        FModel.getPreferences().setPref(FPref.UI_MATCHES_PER_GAME, Integer.toString(next.gamesPerMatch()));
        FModel.getPreferences().setPref(FPref.DECKGEN_MAXIMUM_COMMANDER_BRACKET, Integer.toString(next.commanderBracket()));
        FModel.getPreferences().setPref(FPref.ENFORCE_DECK_LEGALITY, next.enforceDeckLegality());
        for (int i = 0; i < lobby.getNumberOfSlots(); i++) { lobby.getSlot(i).setIsReady(false); }
        rules = next;
        server.updateLobbyState();
        return true;
    }

    /** Returns the bundled deck choices available for the room's current base format. */
    List<BuiltInPreconCatalog.Entry> availableAiDecks() {
        return callOnEdt(() -> BuiltInPreconCatalog.available(rules.mode()));
    }

    public List<String> availableAiProfiles() {
        return callOnEdt(() -> List.copyOf(AiProfileUtil.getProfilesDisplayList()));
    }

    public List<AiSlotView> aiSlotViews() {
        return callOnEdt(() -> {
            List<AiSlotView> views = new ArrayList<>();
            for (int index = 0; index < lobby.getNumberOfSlots(); index++) {
                LobbySlot slot = lobby.getSlot(index);
                AiSlotConfiguration ai = aiSlots.get(index);
                views.add(new AiSlotView(index + 1, slot.getType().name(), slot.getName(),
                        ai == null ? null : ai.deckId(), ai == null ? null : ai.profile(),
                        ai == null ? null : ai.simulation()));
            }
            return views;
        });
    }

    public boolean hasAiSlots() { return callOnEdt(() -> !aiSlots.isEmpty()); }

    /** Adds or replaces an AI only while the room is a stable lobby. */
    public AiResult updateAiSlot(AiSlotConfiguration configuration) {
        return callOnEdt(() -> updateAiSlotOnEdt(configuration));
    }

    public AiResult removeAiSlot(int oneBasedSlot) {
        return callOnEdt(() -> {
            int index = oneBasedSlot - 1;
            if (state != State.WAITING) { return AiResult.failure("lobby_not_waiting", "AI seats may only change while the lobby is waiting."); }
            if (index < 0 || index >= lobby.getNumberOfSlots()) { return AiResult.failure("invalid_slot", "Slot is outside this lobby."); }
            if (!aiSlots.containsKey(index)) { return AiResult.failure("not_ai", "The selected slot is not an AI seat."); }
            aiSlots.remove(index);
            lobby.disconnectPlayer(index);
            server.updateLobbyState();
            lobbyChanged();
            return AiResult.ok();
        });
    }

    private AiResult updateAiSlotOnEdt(AiSlotConfiguration configuration) {
        int index = configuration.slot() - 1;
        if (state != State.WAITING) { return AiResult.failure("lobby_not_waiting", "AI seats may only change while the lobby is waiting."); }
        if (index < 0 || index >= lobby.getNumberOfSlots()) { return AiResult.failure("invalid_slot", "Slot is outside this lobby."); }
        if (rules.mode() != ServerConfig.Mode.COMMANDER && rules.mode() != ServerConfig.Mode.CONSTRUCTED) {
            return AiResult.failure("unsupported_mode", "Built-in AI precons are available only for Commander and Constructed.");
        }
        LobbySlot slot = lobby.getSlot(index);
        if (slot.getType() != LobbySlotType.OPEN && slot.getType() != LobbySlotType.AI) {
            return AiResult.failure("slot_occupied", "AI cannot replace a human or disconnected player.");
        }
        if (configuration.name() == null || configuration.name().isBlank() || configuration.name().length() > 30) {
            return AiResult.failure("invalid_name", "AI name must be 1 to 30 characters.");
        }
        for (int other = 0; other < lobby.getNumberOfSlots(); other++) {
            LobbySlot otherSlot = lobby.getSlot(other);
            if (other != index && otherSlot.getType() != LobbySlotType.OPEN
                    && configuration.name().trim().equalsIgnoreCase(otherSlot.getName())) {
                return AiResult.failure("duplicate_name", "AI name is already in use by another seat.");
            }
        }
        if (!AiProfileUtil.getProfilesDisplayList().contains(configuration.profile())) {
            return AiResult.failure("invalid_profile", "Unknown AI profile.");
        }
        Set<AIOption> options = simulationOptions(configuration.simulation());
        if (options == null) { return AiResult.failure("invalid_simulation", "simulation must be NONE, HYBRID, or FULL."); }
        BuiltInPreconCatalog.Entry deck = BuiltInPreconCatalog.find(rules.mode(), configuration.deckId());
        if (deck == null) { return AiResult.failure("invalid_deck", "Unknown built-in precon for the current mode."); }

        slot.setType(LobbySlotType.AI);
        slot.setName(configuration.name().trim());
        slot.setAvatarIndex(0);
        slot.setSleeveIndex(0);
        slot.setTeam(index);
        slot.setIsArchenemy(false);
        slot.setIsReady(false);
        slot.setAiOptions(options);
        slot.setAiProfile(configuration.profile());
        slot.setDeck(deck.deck());
        slot.setDeckName(deck.name());
        aiSlots.put(index, configuration);
        server.updateLobbyState();
        lobbyChanged();
        return AiResult.ok();
    }

    private static Set<AIOption> simulationOptions(String simulation) {
        return switch (simulation) {
        case "NONE" -> Set.of();
        case "HYBRID" -> Set.of(AIOption.USE_HYBRID_SIMULATION);
        case "FULL" -> Set.of(AIOption.USE_FULL_SIMULATION);
        default -> null;
        };
    }

    private static <T> T callOnEdt(java.util.concurrent.Callable<T> action) {
        if (SwingUtilities.isEventDispatchThread()) {
            try { return action.call(); }
            catch (Exception e) { throw new IllegalStateException("Dedicated lobby operation failed", e); }
        }
        final java.util.concurrent.atomic.AtomicReference<T> result = new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicReference<Exception> failure = new java.util.concurrent.atomic.AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                try { result.set(action.call()); }
                catch (Exception e) { failure.set(e); }
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while updating dedicated lobby", e);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new IllegalStateException("Unable to update dedicated lobby", e.getCause());
        }
        if (failure.get() != null) { throw new IllegalStateException("Dedicated lobby operation failed", failure.get()); }
        return result.get();
    }

    static void applyRules(ServerGameLobby lobby, ServerConfig.LobbyRules rules) {
        lobby.clearVariants();
        GameType baseGameType = rules.baseGameType();
        if (baseGameType != GameType.Constructed) { lobby.applyVariant(baseGameType); }
        for (ServerConfig.Variant variant : rules.variants()) {
            switch (variant) {
            case PLANECHASE -> lobby.applyVariant(GameType.Planechase);
            case VANGUARD -> lobby.applyVariant(GameType.Vanguard);
            case ARCHENEMY -> lobby.applyVariant(GameType.Archenemy);
            case ARCHENEMY_RUMBLE -> lobby.applyVariant(GameType.ArchenemyRumble);
            }
        }
        for (int i = 0; i < lobby.getNumberOfSlots(); i++) { lobby.getSlot(i).setIsArchenemy(false); }
        lobby.setGameType(baseGameType);
    }
}
