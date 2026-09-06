package forge.server;

import forge.game.GameType;
import forge.gamemodes.net.ChatMessage;
import forge.gamemodes.net.client.ClientGameLobby;
import forge.gamemodes.net.server.FServerManager;
import forge.gamemodes.net.server.ServerGameLobby;
import forge.gui.GuiBase;
import forge.interfaces.ILobbyListener;
import forge.localinstance.properties.ForgeNetPreferences;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.util.BuildInfo;
import forge.interfaces.IUpdateable;
import forge.gamemodes.match.LobbySlotType;
import forge.gamemodes.match.GameLobby.GameLobbyData;
import java.nio.file.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

public final class DedicatedServerMain {
    public static final String UPSTREAM = "89806371a4d1c5b62be45f833535e89e73a27227";
    private DedicatedServerMain() { }

    public static void main(String[] args) throws Exception {
        Path configDir = Path.of(System.getProperty("forge.server.config", "/config")).toAbsolutePath();
        Path status = configDir.resolve("server.status");
        if (args.length == 1 && args[0].equals("health")) {
            try {
                String[] fields = Files.readString(status).trim().split(" ");
                long age = System.currentTimeMillis() - Long.parseLong(fields[0]);
                System.exit(age >= 0 && age < 15000 && !fields[1].equals("STOPPING") ? 0 : 1);
            } catch (Exception e) { System.exit(1); }
            return;
        }
        if (args.length != 0) { throw new IllegalArgumentException("Usage: forge-server [health]"); }
        System.setProperty("java.awt.headless", "true");
        Files.createDirectories(configDir);
        Files.deleteIfExists(status);
        System.setProperty("forge.server.profile", configDir.toString());
        ServerConfig config = ServerConfig.from(System.getenv());
        Path assets = Path.of(System.getProperty("forge.server.assets", ".")).toAbsolutePath();
        if (!Files.isDirectory(assets.resolve("res/cardsfolder"))) { throw new IllegalArgumentException("Missing Forge resources at " + assets); }
        DedicatedGui gui = new DedicatedGui(assets);
        GuiBase.setInterface(gui);
        FModel.initialize(null, preferences -> {
            preferences.setPref(FPref.PLAYER_NAME, "Dedicated Server");
            preferences.setPref(FPref.UI_LANGUAGE, "en-US");
            preferences.setPref(FPref.ENFORCE_DECK_LEGALITY, true);
            preferences.setPref(FPref.UI_ENABLE_ONLINE_IMAGE_FETCHER, false);
            preferences.setPref(FPref.UI_ENABLE_SOUNDS, false);
            preferences.setPref(FPref.UI_ENABLE_MUSIC, false);
            preferences.setPref(FPref.MATCH_AI_SIDEBOARDING_MODE, "AI");
            FModel.getNetPreferences().setPref(ForgeNetPreferences.FNetPref.UPnP, "NEVER");
            FModel.getNetPreferences().setPref(ForgeNetPreferences.FNetPref.NET_BANDWIDTH_LOGGING, false);
            return null;
        });
        System.out.println("[server] Forge " + BuildInfo.getVersionString() + " upstream=" + UPSTREAM + " packaging=1");
        FServerManager server = FServerManager.getInstance();
        AtomicReference<DedicatedLobbyController> controllerRef = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            ServerGameLobby lobby = new ServerGameLobby(config.maxPlayers());
            applyMode(lobby, config);
            DedicatedLobbyController controller = new DedicatedLobbyController(config, lobby, server);
            controllerRef.set(controller);
            gui.setOnMatch(controller::attachMatch);
            server.setLobby(lobby);
            server.setDedicatedPolicy(controller);
            lobby.setListener(new IUpdateable() {
                @Override public void update(boolean full) { server.updateLobbyState(); controller.lobbyChanged(); }
                @Override public void update(int slot, LobbySlotType type) { }
            });
            server.setLobbyListener(new ILobbyListener() {
                @Override public void update(GameLobbyData state, int slot) { }
                @Override public void message(String source, String message, ChatMessage.MessageType type) {
                    System.out.println("[server] " + (source == null ? "" : source + ": ") + message);
                }
                @Override public void close() { }
                @Override public ClientGameLobby getLobby() { return null; }
            });
        });
        DedicatedLobbyController controller = controllerRef.get();
        server.startServer(config.port());
        Timer pulse = new Timer(1000, event -> {
            try {
                controller.tick();
                Path pending = configDir.resolve("server.status.tmp");
                Files.writeString(pending, System.currentTimeMillis() + " " + controller.state() + "\n");
                Files.move(pending, status, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Throwable error) {
                error.printStackTrace();
                new Thread(() -> System.exit(1), "fatal-server-error").start();
            }
        });
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                SwingUtilities.invokeAndWait(() -> controller.abort("Server shutting down."));
                long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
                AtomicReference<DedicatedLobbyController.State> state = new AtomicReference<>();
                do {
                    SwingUtilities.invokeAndWait(() -> state.set(controller.state()));
                    if (state.get() == DedicatedLobbyController.State.WAITING) { break; }
                    Thread.sleep(100);
                } while (System.nanoTime() < end);
                if (state.get() != DedicatedLobbyController.State.WAITING) { Runtime.getRuntime().halt(1); }
                SwingUtilities.invokeAndWait(() -> { pulse.stop(); controller.stopping(); });
                Files.deleteIfExists(status);
                server.shutdownDedicated();
            } catch (Throwable error) { error.printStackTrace(); Runtime.getRuntime().halt(1); }
        }, "dedicated-shutdown"));
        SwingUtilities.invokeAndWait(pulse::start);
        System.out.println("[server] Listening on TCP " + config.port() + " mode=" + config.mode());
        new CountDownLatch(1).await();
    }

    private static void applyMode(ServerGameLobby lobby, ServerConfig config) {
        GameType baseGameType = config.baseGameType();
        if (baseGameType != GameType.Constructed) { lobby.applyVariant(baseGameType); }
        for (ServerConfig.Variant variant : config.variants()) {
            switch (variant) {
            case PLANECHASE -> lobby.applyVariant(GameType.Planechase);
            case VANGUARD -> lobby.applyVariant(GameType.Vanguard);
            case ARCHENEMY -> lobby.applyVariant(GameType.Archenemy);
            }
        }
        if (config.variants().contains(ServerConfig.Variant.ARCHENEMY)) {
            for (int i = 0; i < lobby.getNumberOfSlots(); i++) {
                lobby.getSlot(i).setIsArchenemy(false);
            }
        }
        // The configured base format remains lobby metadata; rules derive table
        // behavior such as Archenemy teams from the applied variant set.
        lobby.setGameType(baseGameType);
    }
}
