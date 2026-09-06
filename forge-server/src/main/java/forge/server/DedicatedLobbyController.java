package forge.server;

import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameType;
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
                disconnected.remove(client);
                server.forgetDisconnected(client);
                HostedMatch match = current;
                if (match != null && match.getGame() != null) {
                    PlayerControllerHuman controller = match.getHumanControllers().stream()
                            .filter(c -> c.getGui() == client.getGui()).findFirst().orElse(null);
                    if (controller != null) {
                        say(client.getUsername() + " did not reconnect; AI takes over.");
                        match.getGame().getAction().invoke(() -> {
                            if (current == match) { match.replaceDedicatedPlayer(controller); }
                        });
                    }
                }
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
            if (!occupied.contains(i)) { lobby.disconnectPlayer(i); }
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
        applyRules(lobby, next);
        FModel.getPreferences().setPref(FPref.UI_MATCHES_PER_GAME, Integer.toString(next.gamesPerMatch()));
        FModel.getPreferences().setPref(FPref.DECKGEN_MAXIMUM_COMMANDER_BRACKET, Integer.toString(next.commanderBracket()));
        FModel.getPreferences().setPref(FPref.ENFORCE_DECK_LEGALITY, next.enforceDeckLegality());
        for (int i = 0; i < lobby.getNumberOfSlots(); i++) { lobby.getSlot(i).setIsReady(false); }
        rules = next;
        server.updateLobbyState();
        return true;
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
