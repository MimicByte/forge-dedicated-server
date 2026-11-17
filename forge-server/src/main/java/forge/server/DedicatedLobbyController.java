package forge.server;

import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameType;
import forge.deck.Deck;
import forge.deck.DeckFormat;
import forge.deck.DeckSection;
import forge.deck.CardPool;
import forge.StaticData;
import forge.ai.AIOption;
import forge.ai.AiProfileUtil;
import forge.gamemodes.match.GameLobby.GameStartError;
import forge.gamemodes.match.DedicatedMatchLifecycle;
import forge.gamemodes.match.HostedMatch;
import forge.gamemodes.match.LobbySlot;
import forge.gamemodes.match.LobbySlotType;
import forge.gamemodes.net.event.MessageEvent;
import forge.gamemodes.net.EventFormat;
import forge.gamemodes.net.EventPhase;
import forge.gamemodes.net.NetworkEvent;
import forge.gamemodes.limited.BoosterDraft;
import forge.gamemodes.limited.LimitedPoolType;
import forge.gamemodes.limited.SealedCardPoolGenerator;
import forge.item.SealedTemplate;
import forge.item.generation.IUnOpenedProduct;
import forge.item.generation.UnOpenedProduct;
import forge.gamemodes.limited.LimitedPlayer;
import forge.gamemodes.limited.LimitedPlayerAI;
import forge.gamemodes.limited.SealedDeckBuilder;
import forge.gamemodes.net.server.*;
import forge.player.PlayerControllerHuman;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import javax.swing.SwingUtilities;
import java.util.*;

/** The EDT owns room state. Game mutations run through Forge's game executor. */
public final class DedicatedLobbyController implements DedicatedServerPolicy {
    public enum State { WAITING, COUNTDOWN, LIMITED_DRAFTING, LIMITED_BUILDING, STARTING, PLAYING, POSTGAME, RESETTING, STOPPING }
    private final ServerConfig config;
    private final ServerGameLobby lobby;
    private final FServerManager server;
    private volatile ServerConfig.LobbyRules rules;
    private volatile State state = State.WAITING;
    private long deadline;
    private int lastCountdownAnnouncement;
    private long emptyDeadline;
    private final Map<RemoteClient, Long> disconnected = new HashMap<>();
    /** Drafters are held by BoosterDraftHost grace until pool distribution. */
    private final Set<RemoteClient> draftDisconnected = new HashSet<>();
    private final Set<RemoteClient> kicked = new HashSet<>();
    private final Map<Integer, AiSlotConfiguration> aiSlots = new HashMap<>();
    /** Uploaded decks are deliberately process-local and are never written to storage. */
    private final Map<Integer, Deck> uploadedAiDecks = new HashMap<>();
    /** Uploaded cube decks are process-local and discarded on server exit. */
    private final Map<String, Deck> limitedAssets = new HashMap<>();
    /** API source name for the configured Limited event; not inferred from pool type. */
    private String limitedSource;
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
    public record AiSlotConfiguration(int slot, String name, String deckId, String profile, String simulation, int team) { }
    public record UploadedAiSlotConfiguration(int slot, String name, String profile, String simulation, int team, Deck deck) { }
    public record AiSlotView(int slot, String type, String name, String deckId, String profile, String simulation, int team) { }
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
    @Override public boolean acceptsLimitedDeckUpdates() { return state == State.LIMITED_BUILDING; }
    @Override public boolean acceptsGameActions() { return state == State.PLAYING || state == State.POSTGAME; }
    @Override public boolean acceptsDraftActions() { return state == State.LIMITED_DRAFTING; }
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
            if (state == State.LIMITED_BUILDING) {
                if (limitedPlayersReady()) { startLimitedMatch(); }
                return;
            }
            // A configured Limited event is started only by its explicit API
            // action. In particular, do not run constructed deck validation or
            // the ordinary ready countdown while players are preparing a pod.
            if (state == State.WAITING && lobby.getCurrentEvent() != null) { return; }
            if (!acceptsLobbyChanges()) { return; }
            if (state == State.COUNTDOWN) { say("Start countdown cancelled."); }
            lastCountdownAnnouncement = 0;
            transition(State.WAITING);
            if (ready()) {
                deadline = now() + config.startDelaySeconds() * 1000L;
                lastCountdownAnnouncement = Math.min(5, config.startDelaySeconds()) + 1;
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

    private boolean limitedPlayersReady() {
        List<RemoteClient> players = server.connectedPlayers();
        return players.size() >= 2 && players.stream()
                .allMatch(client -> lobby.getSlot(client.getIndex()).isReady());
    }

    private void startLimitedMatch() {
        if (state != State.LIMITED_BUILDING) { return; }
        transition(State.STARTING);
        Runnable start = lobby.startGame();
        if (start == null) {
            transition(State.LIMITED_BUILDING);
            return;
        }
        start.run();
    }

    /** Configuration accepted from the private API; products never pass through a GUI dialog. */
    public record LimitedConfiguration(EventFormat format, String source, String setCode, String assetId,
            int packs, int podSize, int pickTimerSeconds, int disconnectGraceSeconds) { }
    public record LimitedView(String format, String phase, String source, String product,
            int podSize, int participants) { }
    public record LimitedAssetResult(boolean success, String code, String message, String id) {
        static LimitedAssetResult ok(String id) { return new LimitedAssetResult(true, "ok", "", id); }
        static LimitedAssetResult failure(String code, String message) { return new LimitedAssetResult(false, code, message, null); }
    }

    public LimitedAssetResult uploadLimitedCube(Deck deck) {
        return callOnEdt(() -> {
            if (state != State.WAITING) { return LimitedAssetResult.failure("lobby_not_waiting", "Assets may only be uploaded while the lobby is waiting."); }
            if (deck == null || deck.getMain().isEmpty()) { return LimitedAssetResult.failure("invalid_deck", "The cube .dck must contain cards in its main deck."); }
            String id = UUID.randomUUID().toString().substring(0, 12);
            limitedAssets.put(id, deck);
            return LimitedAssetResult.ok(id);
        });
    }

    public ActionResult configureLimited(LimitedConfiguration configuration) {
        return callOnEdt(() -> configureLimitedOnEdt(configuration));
    }

    private ActionResult configureLimitedOnEdt(LimitedConfiguration configuration) {
        if (state != State.WAITING) {
            return ActionResult.failure("lobby_not_waiting", "Limited events may only be configured while the lobby is waiting.");
        }
        if (configuration.packs() < 1 || configuration.packs() > 12
                || configuration.podSize() < 2 || configuration.podSize() > 8
                || configuration.pickTimerSeconds() < 0 || configuration.pickTimerSeconds() > 600
                || configuration.disconnectGraceSeconds() < 0 || configuration.disconnectGraceSeconds() > 3600) {
            return ActionResult.failure("invalid_event", "Invalid pack, pod, timer, or reconnect-grace value.");
        }
        List<IUnOpenedProduct> products = new ArrayList<>();
        String source = configuration.source() == null ? "" : configuration.source().toUpperCase(Locale.ROOT);
        String label;
        String landSet;
        if ("FULL".equals(source)) {
            IUnOpenedProduct product = new UnOpenedProduct(SealedTemplate.genericDraftBooster);
            for (int i = 0; i < configuration.packs(); i++) { products.add(product); }
            landSet = forge.card.CardEdition.Predicates.getRandomSetWithAllBasicLands(
                    FModel.getMagicDb().getEditions()).getCode();
            label = "Full";
        } else if ("SET".equals(source) && configuration.setCode() != null
                && FModel.getMagicDb().getBoosters().get(configuration.setCode()) != null) {
            IUnOpenedProduct product = new UnOpenedProduct(FModel.getMagicDb().getBoosters().get(configuration.setCode()));
            for (int i = 0; i < configuration.packs(); i++) { products.add(product); }
            landSet = configuration.setCode();
            label = configuration.setCode();
        } else if ("CUBE".equals(source) && limitedAssets.containsKey(configuration.assetId())) {
            Deck cube = limitedAssets.get(configuration.assetId());
            UnOpenedProduct product = new UnOpenedProduct(SealedTemplate.genericDraftBooster,
                    new CardPool(cube.getMain()));
            product.setLimitedPool(true);
            for (int i = 0; i < configuration.packs(); i++) { products.add(product); }
            landSet = forge.card.CardEdition.Predicates.getRandomSetWithAllBasicLands(
                    FModel.getMagicDb().getEditions()).getCode();
            label = cube.getName() == null ? "Uploaded Cube" : cube.getName();
        } else {
            return ActionResult.failure("unsupported_product", "source must be FULL, SET with an available setCode, or CUBE with an uploaded assetId.");
        }
        lobby.clearCurrentEvent();
        lobby.setLimitedMode(true);
        limitedSource = source;
        lobby.createEvent(configuration.format());
        NetworkEvent event = lobby.getCurrentEvent();
        event.setPoolType(LimitedPoolType.Full);
        event.setProductDescription(label);
        event.setPickTimerSeconds(configuration.pickTimerSeconds());
        event.setDisconnectGraceSeconds(configuration.disconnectGraceSeconds());
        if (configuration.format() == EventFormat.BOOSTER_DRAFT) {
            event.setDraft(BoosterDraft.createDraftForNetwork(LimitedPoolType.Full, products,
                    label, landSet, configuration.podSize()));
        } else {
            event.setSealedGenerator(new SealedCardPoolGenerator(products, landSet, label));
        }
        server.updateLobbyState();
        return ActionResult.ok();
    }

    public ActionResult startLimited() {
        return callOnEdt(() -> {
            if (state != State.WAITING || lobby.getCurrentEvent() == null) {
                return ActionResult.failure("event_not_ready", "Configure a Limited event while the lobby is waiting first.");
            }
            List<RemoteClient> players = server.connectedPlayers();
            if (players.size() < 2 || players.stream().anyMatch(c -> !lobby.getSlot(c.getIndex()).isReady())) {
                return ActionResult.failure("players_not_ready", "Two connected human players must be ready before starting a Limited event.");
            }
            NetworkEvent event = lobby.getCurrentEvent();
            if (event.getFormat() == EventFormat.BOOSTER_DRAFT) {
                lobby.startDraftEvent();
                transition(State.LIMITED_DRAFTING);
            } else {
                lobby.startSealedEvent();
                assignSealedAiDecks(event);
                transition(State.LIMITED_BUILDING);
            }
            return ActionResult.ok();
        });
    }

    public ActionResult cancelLimited() {
        return callOnEdt(() -> {
            if (state != State.WAITING || lobby.getCurrentEvent() == null) {
                return ActionResult.failure("event_not_waiting", "Only an unstarted Limited event may be cancelled.");
            }
            lobby.clearCurrentEvent();
            lobby.setLimitedMode(false);
            limitedSource = null;
            server.updateLobbyState();
            return ActionResult.ok();
        });
    }

    public LimitedView limitedView() {
        return callOnEdt(() -> {
            NetworkEvent event = lobby.getCurrentEvent();
            if (event == null) { return null; }
            int pod = event.getDraft() == null ? 0 : event.getDraft().getPodSize();
            return new LimitedView(event.getFormat().name(), event.getPhase().name(),
                    limitedSource, event.getProductDescription(), pod,
                    event.getParticipants().size());
        });
    }
    @Override public void connectionsChanged() {
        if (!server.connectedPlayers().isEmpty()) { emptyDeadline = 0; }
        lobbyChanged();
    }
    @Override public void disconnected(RemoteClient client) {
        if (state == State.LIMITED_DRAFTING) {
            draftDisconnected.add(client);
            return;
        }
        if (!acceptsLobbyChanges() && state != State.STOPPING) {
            disconnected.put(client, now() + config.reconnectSeconds() * 1000L);
            say(client.getUsername() + " disconnected. Reconnect within " + config.reconnectSeconds() + " seconds.");
            if (server.connectedPlayers().isEmpty()) { emptyDeadline = now() + config.reconnectSeconds() * 1000L; }
        }
    }
    @Override public void reconnected(RemoteClient client) {
        draftDisconnected.remove(client);
        disconnected.remove(client);
        emptyDeadline = 0;
    }
    @Override public boolean wasKicked(RemoteClient client) { return kicked.remove(client); }

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
        if (state == State.LIMITED_DRAFTING && lobby.getDraftHost() != null && lobby.getDraftHost().isFinished()) {
            NetworkEvent event = lobby.getCurrentEvent();
            if (event != null) {
                assignLimitedAiDecks(event);
                event.setPhase(EventPhase.POOL_DISTRIBUTION);
            }
            transition(State.LIMITED_BUILDING);
            for (RemoteClient client : draftDisconnected) {
                disconnected.put(client, time + config.reconnectSeconds() * 1000L);
            }
            draftDisconnected.clear();
            if (server.connectedPlayers().isEmpty()) {
                emptyDeadline = time + config.reconnectSeconds() * 1000L;
            }
            server.updateLobbyState();
        }
        if (state == State.COUNTDOWN) {
            int announcement = nextCountdownAnnouncement(deadline, time, lastCountdownAnnouncement);
            if (announcement > 0) {
                say(Integer.toString(announcement));
                lastCountdownAnnouncement = announcement;
            }
        }
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

    private void assignLimitedAiDecks(NetworkEvent event) {
        if (event.getDraft() == null) { return; }
        for (forge.gamemodes.net.EventParticipant participant : event.getParticipants()) {
            if (!participant.isAI() || participant.getLobbySlotIndex() < 0) { continue; }
            LimitedPlayer player = event.getDraft().getAllPlayers().get(participant.getSeatIndex());
            if (player instanceof LimitedPlayerAI ai) {
                Deck deck = ai.buildDeck(event.getDraft().getLandSetCode());
                LobbySlot slot = lobby.getSlot(participant.getLobbySlotIndex());
                slot.setDeck(deck);
                slot.setDeckName(deck.getName());
                slot.setIsReady(false);
            }
        }
    }

    private void assignSealedAiDecks(NetworkEvent event) {
        if (event.getSealedGenerator() == null) { return; }
        for (forge.gamemodes.net.EventParticipant participant : event.getParticipants()) {
            if (!participant.isAI() || participant.getLobbySlotIndex() < 0) { continue; }
            CardPool pool = event.getSealedGenerator().getCardPool(false);
            if (pool == null) { continue; }
            Deck deck = new SealedDeckBuilder(pool.toFlatList()).buildDeck(event.getSealedGenerator().getLandSetCode());
            LobbySlot slot = lobby.getSlot(participant.getLobbySlotIndex());
            slot.setDeck(deck);
            slot.setDeckName(deck.getName());
            slot.setIsReady(false);
        }
    }

    /** Returns the next final-five countdown value to announce, or zero when none is due. */
    static int nextCountdownAnnouncement(long deadline, long time, int lastAnnouncement) {
        long millisRemaining = deadline - time;
        if (millisRemaining <= 0) { return 0; }
        int secondsRemaining = (int) Math.min(Integer.MAX_VALUE, (millisRemaining + 999L) / 1000L);
        return secondsRemaining <= 5 && secondsRemaining < lastAnnouncement ? secondsRemaining : 0;
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

    /**
     * A game worker can fail after Forge has caught and displayed its crash
     * report, leaving its input queues and both remote UIs waiting forever.
     * This path deliberately does not enqueue work on that worker: it closes
     * the failed match from the EDT and restores a usable lobby immediately.
     */
    public void recoverFromFatalMatchError(String detail) {
        callOnEdt(() -> {
            HostedMatch match = current;
            if (match == null || state == State.WAITING || state == State.STOPPING) {
                return null;
            }
            DedicatedCrashReporter.reportText("Fatal match error", detail);
            say("Server encountered an unrecoverable match error. This match was aborted; returning to the lobby.");
            server.cancelDedicatedReplies();
            for (PlayerControllerHuman human : List.copyOf(match.getHumanControllers())) {
                human.getInputQueue().clearInputs();
            }
            match.finishDedicatedMatch();
            reset();
            return null;
        });
    }
    private void reset() {
        if (state == State.STOPPING) { return; }
        transition(State.RESETTING);
        ++generation;
        current = null;
        lobby.clearCurrentEvent();
        lobby.setLimitedMode(false);
        limitedSource = null;
        disconnected.clear();
        draftDisconnected.clear();
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

    /** Disconnects a player from a stable lobby without allowing a reconnect grace period. */
    public ActionResult kickPlayer(int oneBasedSlot) {
        return callOnEdt(() -> {
            if (state != State.WAITING) { return ActionResult.failure("lobby_not_waiting", "Players may only be kicked while the lobby is waiting."); }
            int index = oneBasedSlot - 1;
            if (index < 0 || index >= lobby.getNumberOfSlots()) { return ActionResult.failure("invalid_slot", "Slot is outside this lobby."); }
            RemoteClient client = server.getClientBySlotIndex(index);
            if (client == null || !client.isConnected()) { return ActionResult.failure("not_connected", "No connected player occupies that slot."); }
            kicked.add(client);
            say("Server: " + client.getUsername() + " was removed from the lobby.");
            if (!server.disconnectDedicatedPlayer(index)) {
                kicked.remove(client);
                return ActionResult.failure("not_connected", "No connected player occupies that slot.");
            }
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
                String deckId = ai == null ? null : ai.deckId();
                if (uploadedAiDecks.containsKey(index)) { deckId = "upload:" + slot.getDeckName(); }
                views.add(new AiSlotView(index + 1, slot.getType().name(), slot.getName(),
                        deckId, ai == null ? null : ai.profile(),
                        ai == null ? null : ai.simulation(), slot.getTeam() + 1));
            }
            return views;
        });
    }

    public boolean hasAiSlots() { return callOnEdt(() -> !aiSlots.isEmpty()); }

    /** Changes an occupied seat's one-based team through the private management API. */
    public ActionResult updateSlotTeam(int oneBasedSlot, int oneBasedTeam) {
        return callOnEdt(() -> {
            if (state != State.WAITING) {
                return ActionResult.failure("lobby_not_waiting", "Teams may only change while the lobby is waiting.");
            }
            int slotIndex = oneBasedSlot - 1;
            if (slotIndex < 0 || slotIndex >= lobby.getNumberOfSlots()) {
                return ActionResult.failure("invalid_slot", "Slot is outside this lobby.");
            }
            if (oneBasedTeam < 1 || oneBasedTeam > lobby.getNumberOfSlots()) {
                return ActionResult.failure("invalid_team", "team must be a one-based lobby slot number.");
            }
            if (lobby.hasVariant(GameType.Archenemy)) {
                return ActionResult.failure("derived_team", "Archenemy teams are derived from the nominated Archenemy seat.");
            }
            LobbySlot slot = lobby.getSlot(slotIndex);
            if (slot.getType() == LobbySlotType.OPEN) {
                return ActionResult.failure("slot_open", "An open seat has no team to change.");
            }
            slot.setTeam(oneBasedTeam - 1);
            slot.setIsReady(false);
            AiSlotConfiguration ai = aiSlots.get(slotIndex);
            if (ai != null) {
                aiSlots.put(slotIndex, new AiSlotConfiguration(ai.slot(), ai.name(), ai.deckId(),
                        ai.profile(), ai.simulation(), oneBasedTeam));
            }
            server.updateLobbyState();
            lobbyChanged();
            return ActionResult.ok();
        });
    }

    /** Adds or replaces an AI only while the room is a stable lobby. */
    public AiResult updateAiSlot(AiSlotConfiguration configuration) {
        return callOnEdt(() -> updateAiSlotOnEdt(configuration));
    }

    /** Assigns a parsed, in-memory .dck to an AI seat. */
    public AiResult updateUploadedAiSlot(UploadedAiSlotConfiguration configuration) {
        return callOnEdt(() -> updateUploadedAiSlotOnEdt(configuration));
    }

    public AiResult removeAiSlot(int oneBasedSlot) {
        return callOnEdt(() -> {
            int index = oneBasedSlot - 1;
            if (state != State.WAITING) { return AiResult.failure("lobby_not_waiting", "AI seats may only change while the lobby is waiting."); }
            if (index < 0 || index >= lobby.getNumberOfSlots()) { return AiResult.failure("invalid_slot", "Slot is outside this lobby."); }
            if (!aiSlots.containsKey(index)) { return AiResult.failure("not_ai", "The selected slot is not an AI seat."); }
            aiSlots.remove(index);
            uploadedAiDecks.remove(index);
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
        if (!supportsConfiguredAi()) { return AiResult.failure("unsupported_mode", "Configured AI decks are available only for Commander and Constructed."); }
        LobbySlot slot = lobby.getSlot(index);
        if (slot.getType() != LobbySlotType.OPEN && slot.getType() != LobbySlotType.AI) {
            return AiResult.failure("slot_occupied", "AI cannot replace a human or disconnected player.");
        }
        if (configuration.name() == null || configuration.name().isBlank() || configuration.name().length() > 30) {
            return AiResult.failure("invalid_name", "AI name must be 1 to 30 characters.");
        }
        if (configuration.team() < 1 || configuration.team() > lobby.getNumberOfSlots()) {
            return AiResult.failure("invalid_team", "team must be a one-based lobby slot number.");
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
        slot.setTeam(configuration.team() - 1);
        slot.setIsArchenemy(false);
        slot.setIsReady(false);
        slot.setAiOptions(options);
        slot.setAiProfile(configuration.profile());
        slot.setDeck(deck.deck());
        slot.setDeckName(deck.name());
        aiSlots.put(index, configuration);
        uploadedAiDecks.remove(index);
        server.updateLobbyState();
        lobbyChanged();
        return AiResult.ok();
    }

    private AiResult updateUploadedAiSlotOnEdt(UploadedAiSlotConfiguration configuration) {
        if (!supportsConfiguredAi()) { return AiResult.failure("unsupported_mode", "Configured AI decks are available only for Commander and Constructed."); }
        Deck deck = configuration.deck();
        if (deck == null || deck.getName() == null || deck.getName().isBlank()) {
            return AiResult.failure("invalid_deck", "The uploaded .dck must include a deck name.");
        }
        // Force deferred deck sections to resolve before checking card counts and legality.
        if (deck.getMain().isEmpty()) { return AiResult.failure("invalid_deck", "The uploaded .dck has no playable main deck."); }
        if (rules.enforceDeckLegality()) {
            String problem = rules.baseGameType().getDeckFormat().getDeckConformanceProblem(deck);
            if (problem != null) { return AiResult.failure("invalid_deck", problem); }
            if (lobby.hasVariant(GameType.Planechase)) {
                problem = DeckFormat.getPlaneSectionConformanceProblem(deck.get(DeckSection.Planes));
                if (problem != null) { return AiResult.failure("invalid_deck", problem); }
            }
            if (lobby.hasVariant(GameType.Vanguard)) {
                var avatars = deck.get(DeckSection.Avatar);
                if (avatars == null || avatars.countAll() == 0) {
                    return AiResult.failure("invalid_deck", "choose a Vanguard avatar.");
                }
            }
        }
        AiSlotConfiguration settings = new AiSlotConfiguration(configuration.slot(), configuration.name(),
                "upload:" + deck.getName(), configuration.profile(), configuration.simulation(), configuration.team());
        AiResult result = validateAiSlotSettings(settings);
        if (!result.success()) { return result; }
        int index = configuration.slot() - 1;
        LobbySlot slot = lobby.getSlot(index);
        applyAiSlot(slot, settings.name(), settings.team(), settings.profile(), settings.simulation(), deck, deck.getName());
        aiSlots.put(index, settings);
        uploadedAiDecks.put(index, deck);
        server.updateLobbyState();
        lobbyChanged();
        return AiResult.ok();
    }

    private boolean supportsConfiguredAi() {
        return rules.mode() == ServerConfig.Mode.COMMANDER || rules.mode() == ServerConfig.Mode.CONSTRUCTED;
    }

    private AiResult validateAiSlotSettings(AiSlotConfiguration configuration) {
        int index = configuration.slot() - 1;
        if (state != State.WAITING) { return AiResult.failure("lobby_not_waiting", "AI seats may only change while the lobby is waiting."); }
        if (index < 0 || index >= lobby.getNumberOfSlots()) { return AiResult.failure("invalid_slot", "Slot is outside this lobby."); }
        LobbySlot slot = lobby.getSlot(index);
        if (slot.getType() != LobbySlotType.OPEN && slot.getType() != LobbySlotType.AI) { return AiResult.failure("slot_occupied", "AI cannot replace a human or disconnected player."); }
        if (configuration.name() == null || configuration.name().isBlank() || configuration.name().length() > 30) { return AiResult.failure("invalid_name", "AI name must be 1 to 30 characters."); }
        if (configuration.team() < 1 || configuration.team() > lobby.getNumberOfSlots()) { return AiResult.failure("invalid_team", "team must be a one-based lobby slot number."); }
        for (int other = 0; other < lobby.getNumberOfSlots(); other++) {
            LobbySlot otherSlot = lobby.getSlot(other);
            if (other != index && otherSlot.getType() != LobbySlotType.OPEN && configuration.name().trim().equalsIgnoreCase(otherSlot.getName())) { return AiResult.failure("duplicate_name", "AI name is already in use by another seat."); }
        }
        if (!AiProfileUtil.getProfilesDisplayList().contains(configuration.profile())) { return AiResult.failure("invalid_profile", "Unknown AI profile."); }
        if (simulationOptions(configuration.simulation()) == null) { return AiResult.failure("invalid_simulation", "simulation must be NONE, HYBRID, or FULL."); }
        return AiResult.ok();
    }

    private void applyAiSlot(LobbySlot slot, String name, int team, String profile, String simulation, Deck deck, String deckName) {
        slot.setType(LobbySlotType.AI);
        slot.setName(name.trim());
        slot.setAvatarIndex(0);
        slot.setSleeveIndex(0);
        slot.setTeam(team - 1);
        slot.setIsArchenemy(false);
        slot.setIsReady(false);
        slot.setAiOptions(simulationOptions(simulation));
        slot.setAiProfile(profile);
        slot.setDeck(deck);
        slot.setDeckName(deckName);
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
        FModel.getPreferences().setPref(FPref.UI_MATCHES_PER_GAME, Integer.toString(rules.gamesPerMatch()));
        FModel.getPreferences().setPref(FPref.DECKGEN_MAXIMUM_COMMANDER_BRACKET, Integer.toString(rules.commanderBracket()));
        FModel.getPreferences().setPref(FPref.ENFORCE_DECK_LEGALITY, rules.enforceDeckLegality());
        FModel.getPreferences().setPref(FPref.LEGACY_MANABURN, rules.manaBurn());
        FModel.getPreferences().setPref(FPref.LEGACY_ORDER_COMBATANTS, rules.legacyOrderCombatants());
        FModel.getPreferences().setPref(FPref.FILTERED_HANDS, rules.filteredHands());
        FModel.getPreferences().setPref(FPref.MATCH_AI_TIMEOUT, Integer.toString(rules.aiTimeoutSeconds()));
        StaticData.instance().setFilteredHandsEnabled(rules.filteredHands());
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
