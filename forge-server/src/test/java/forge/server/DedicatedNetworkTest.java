package forge.server;

import forge.deck.Deck;
import forge.gamemodes.match.LobbySlotType;
import forge.gamemodes.match.NextGameDecision;
import forge.gamemodes.net.CompatibleObjectDecoder;
import forge.gamemodes.net.CompatibleObjectEncoder;
import forge.gamemodes.net.event.*;
import forge.gamemodes.net.server.*;
import forge.gui.GuiBase;
import forge.localinstance.properties.ForgeNetPreferences;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.util.BuildInfo;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.serialization.ClassResolvers;
import org.testng.Assert;
import org.testng.annotations.*;
import javax.swing.SwingUtilities;
import java.nio.file.*;
import java.net.ServerSocket;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Real TCP and production serialization, without modified client-side lobby behavior. */
public class DedicatedNetworkTest {
    private FServerManager server;
    private ServerGameLobby lobby;
    private DedicatedLobbyController controller;
    private int port;
    private final List<Channel> peers = new ArrayList<>();
    private EventLoopGroup group;
    private javax.swing.Timer pulse;

    @BeforeClass public void initialize() throws Exception {
        System.setProperty("java.awt.headless", "true");
        System.setProperty("forge.server.profile", Files.createTempDirectory("forge-server-test").toString());
        DedicatedGui gui = new DedicatedGui(Path.of("../forge-gui"));
        GuiBase.setInterface(gui);
        FModel.initialize(null, prefs -> {
            prefs.setPref(FPref.UI_LANGUAGE, "en-US");
            prefs.setPref(FPref.ENFORCE_DECK_LEGALITY, true);
            prefs.setPref(FPref.UI_ENABLE_ONLINE_IMAGE_FETCHER, false);
            FModel.getNetPreferences().setPref(ForgeNetPreferences.FNetPref.UPnP, "NEVER");
            return null;
        });
        server = FServerManager.getInstance();
        lobby = new ServerGameLobby(4);
        controller = new DedicatedLobbyController(ServerConfig.from(Map.of(
                "FORGE_SERVER_START_DELAY_SECONDS", "1", "FORGE_SERVER_POSTGAME_SECONDS", "1",
                "FORGE_SERVER_RECONNECT_SECONDS", "2")), lobby, server);
        gui.setOnMatch(controller::attachMatch);
        lobby.setListener(new forge.interfaces.IUpdateable() {
            @Override public void update(boolean full) { server.updateLobbyState(); controller.lobbyChanged(); }
            @Override public void update(int slot, LobbySlotType type) { }
        });
        server.setLobby(lobby);
        server.setDedicatedPolicy(controller);
        try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        server.startServer(port);
        group = new NioEventLoopGroup(1);
        pulse = new javax.swing.Timer(50, e -> controller.tick());
        SwingUtilities.invokeAndWait(pulse::start);
    }
    private Channel connect(String name, String version) throws Exception {
        Channel ch = new Bootstrap().group(group).channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<Channel>() {
                    @Override protected void initChannel(Channel ch) {
                        ch.pipeline().addLast(new CompatibleObjectEncoder(null),
                                new CompatibleObjectDecoder(9766 * 1024, ClassResolvers.cacheDisabled(null)),
                                new ChannelInboundHandlerAdapter() {
                                    @Override public void channelRead(ChannelHandlerContext ctx, Object msg) { }
                                });
                    }
                }).connect("127.0.0.1", port).sync().channel();
        peers.add(ch);
        ch.writeAndFlush(new LoginEvent(name, 0, 0, version, false)).sync();
        return ch;
    }
    private static void await(BooleanSupplier predicate) throws Exception {
        // Three and four concurrent protocol clients can spend several seconds
        // applying their first full tracker snapshot before Forge releases the
        // completed game worker.
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!predicate.getAsBoolean() && System.nanoTime() < end) { Thread.sleep(20); }
        Assert.assertTrue(predicate.getAsBoolean(), "Timed out waiting for network state");
    }
    @Test public void admissionUpdatesAndCleanup() throws Exception {
        Channel a = connect("Alice", BuildInfo.getVersionString());
        await(() -> server.connectedPlayers().size() == 1);
        Channel b = connect("Bob", BuildInfo.getVersionString());
        await(() -> server.connectedPlayers().size() == 2);
        SwingUtilities.invokeAndWait(() -> {
            Assert.assertEquals(lobby.getSlot(0).getType(), LobbySlotType.REMOTE);
            Assert.assertEquals(lobby.getSlot(1).getType(), LobbySlotType.REMOTE);
            Assert.assertEquals(lobby.getSlot(0).getName(), "Alice");
            Assert.assertNotEquals(lobby.getSlot(0).getTeam(), lobby.getSlot(1).getTeam());
        });
        Channel duplicate = connect("Alice", BuildInfo.getVersionString());
        await(() -> !duplicate.isActive());
        Channel wrongVersion = connect("Wrong", "incompatible");
        await(() -> server.connectedPlayers().size() == 3);
        Assert.assertTrue(wrongVersion.isActive(), "A differing snapshot version should receive a compatibility warning, not be rejected");
        wrongVersion.close().sync();
        await(() -> server.connectedPlayers().size() == 2);
        a.writeAndFlush(UpdateLobbyPlayerEvent.isReadyUpdate(true)).sync();
        await(() -> lobby.getSlot(0).isReady());
        a.writeAndFlush(UpdateLobbyPlayerEvent.deckUpdate(new Deck("Invalid test deck"))).sync();
        await(() -> lobby.getSlot(0).getDeck() != null && !lobby.getSlot(0).isReady());
        SwingUtilities.invokeAndWait(() -> Assert.assertFalse(lobby.validateDedicatedStart(forge.game.GameType.Commander).isEmpty()));
        a.close().sync();
        await(() -> server.connectedPlayers().size() == 1 && lobby.getSlot(0).getType() == LobbySlotType.OPEN);
        SwingUtilities.invokeAndWait(() -> {
            Assert.assertEquals(lobby.getSlot(0).getType(), LobbySlotType.OPEN);
            Assert.assertNull(lobby.getSlot(0).getDeck());
        });
        b.close().sync();
        await(() -> server.connectedPlayers().isEmpty());
    }
    @Test public void validatesSpecialVariantRequirements() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ServerGameLobby special = new ServerGameLobby(2);
            special.applyVariant(forge.game.GameType.Planechase);
            special.applyVariant(forge.game.GameType.Vanguard);
            special.setGameType(forge.game.GameType.Constructed);
            for (int i = 0; i < 2; i++) {
                special.getSlot(i).setType(LobbySlotType.REMOTE);
                special.getSlot(i).setName("Player " + i);
                special.getSlot(i).setDeck(new Deck("Incomplete special deck"));
            }
            List<String> withoutNominee = special.validateDedicatedStart(forge.game.GameType.Constructed).stream()
                    .map(forge.gamemodes.match.GameLobby.GameStartError::message).toList();
            Assert.assertTrue(withoutNominee.stream().anyMatch(m -> m.toLowerCase(Locale.ROOT).contains("planes")));
            Assert.assertTrue(withoutNominee.stream().anyMatch(m -> m.contains("Vanguard avatar")));

            ServerGameLobby archenemy = new ServerGameLobby(2);
            archenemy.applyVariant(forge.game.GameType.Archenemy);
            archenemy.setGameType(forge.game.GameType.Constructed);
            Assert.assertTrue(archenemy.hasVariant(forge.game.GameType.Archenemy));
            for (int i = 0; i < 2; i++) {
                archenemy.getSlot(i).setIsArchenemy(false);
                archenemy.getSlot(i).setType(LobbySlotType.REMOTE);
                archenemy.getSlot(i).setName("Player " + i);
                archenemy.getSlot(i).setDeck(new Deck("Incomplete Archenemy deck"));
            }
            List<String> withoutNomineeArchenemy = archenemy.validateDedicatedStart(forge.game.GameType.Constructed).stream()
                    .map(forge.gamemodes.match.GameLobby.GameStartError::message).toList();
            Assert.assertTrue(withoutNomineeArchenemy.stream().anyMatch(m -> m.contains("Exactly one player")),
                    "errors=" + withoutNomineeArchenemy);

            archenemy.getSlot(0).setIsArchenemy(true);
            List<String> withNominee = archenemy.validateDedicatedStart(forge.game.GameType.Constructed).stream()
                    .map(forge.gamemodes.match.GameLobby.GameStartError::message).toList();
            Assert.assertFalse(withNominee.stream().anyMatch(m -> m.contains("Exactly one player")));
            Assert.assertTrue(withNominee.stream().anyMatch(m -> m.toLowerCase(Locale.ROOT).contains("scheme")));
        });
    }
    @Test(dependsOnMethods = "admissionUpdatesAndCleanup") public void serverManagedAiSeatsPersistAcrossReset() throws Exception {
        List<BuiltInPreconCatalog.Entry> decks = controller.availableAiDecks();
        Assert.assertFalse(decks.isEmpty(), "Commander precons should be available to the dedicated server");
        List<String> profiles = controller.availableAiProfiles();
        Assert.assertFalse(profiles.isEmpty(), "Forge should load at least its default AI profile");
        DedicatedLobbyController.AiSlotConfiguration configuration = new DedicatedLobbyController.AiSlotConfiguration(
                3, "AI Test", decks.get(0).id(), profiles.get(0), "HYBRID");

        Assert.assertTrue(controller.updateAiSlot(configuration).success());
        SwingUtilities.invokeAndWait(() -> {
            Assert.assertEquals(lobby.getSlot(2).getType(), LobbySlotType.AI);
            Assert.assertEquals(lobby.getSlot(2).getName(), "AI Test");
            Assert.assertNotNull(lobby.getSlot(2).getDeck());
        });
        Assert.assertEquals(controller.updateAiSlot(new DedicatedLobbyController.AiSlotConfiguration(
                4, "AI Test", decks.get(0).id(), profiles.get(0), "NONE")).code(), "duplicate_name");
        Assert.assertFalse(controller.updateRules(new ServerConfig.LobbyRules(ServerConfig.Mode.CONSTRUCTED,
                Set.of(), 3, 5, true)), "Changing modes must require removing persistent AI seats first");

        SwingUtilities.invokeAndWait(() -> controller.abort("AI reset test"));
        SwingUtilities.invokeAndWait(() -> Assert.assertEquals(lobby.getSlot(2).getType(), LobbySlotType.AI));
        Assert.assertTrue(controller.removeAiSlot(3).success());
        SwingUtilities.invokeAndWait(() -> Assert.assertEquals(lobby.getSlot(2).getType(), LobbySlotType.OPEN));
    }
    @Test public void autoGeneratedModesDoNotRequireClientDecks() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ServerGameLobby momir = new ServerGameLobby(2);
            momir.applyVariant(forge.game.GameType.MomirBasic);
            momir.setGameType(forge.game.GameType.MomirBasic);
            for (int i = 0; i < 2; i++) {
                momir.getSlot(i).setType(LobbySlotType.REMOTE);
                momir.getSlot(i).setName("Player " + i);
            }
            Assert.assertTrue(momir.validateDedicatedStart(forge.game.GameType.MomirBasic).isEmpty());
        });
    }
    @Test public void legalityDisabledAllowsNonConformingSubmittedDecks() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            FModel.getPreferences().setPref(FPref.ENFORCE_DECK_LEGALITY, false);
            try {
                ServerGameLobby room = new ServerGameLobby(2);
                for (int i = 0; i < 2; i++) {
                    room.getSlot(i).setType(LobbySlotType.REMOTE);
                    room.getSlot(i).setName("Player " + i);
                    room.getSlot(i).setDeck(new Deck("Intentionally nonconforming"));
                }
                Assert.assertTrue(room.validateDedicatedStart(forge.game.GameType.Constructed).isEmpty());
            } finally {
                FModel.getPreferences().setPref(FPref.ENFORCE_DECK_LEGALITY, true);
            }
        });
    }
    @Test public void dedicatedValidationAcceptsConfiguredAiSeats() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            FModel.getPreferences().setPref(FPref.ENFORCE_DECK_LEGALITY, false);
            try {
                ServerGameLobby room = new ServerGameLobby(4);
                for (int i = 0; i < 4; i++) {
                    room.getSlot(i).setType(i < 2 ? LobbySlotType.REMOTE : LobbySlotType.AI);
                    room.getSlot(i).setName("Player " + i);
                    room.getSlot(i).setDeck(new Deck("Dedicated seat " + i));
                }
                Assert.assertTrue(room.validateDedicatedStart(forge.game.GameType.Constructed).isEmpty());
            } finally {
                FModel.getPreferences().setPref(FPref.ENFORCE_DECK_LEGALITY, true);
            }
        });
    }
    @Test public void administrativeActionsValidateRequests() throws Exception {
        Assert.assertEquals(controller.announce(" ").code(), "invalid_message");
        Assert.assertTrue(controller.announce("Maintenance reminder").success());
        Assert.assertEquals(controller.takeOverDisconnectedPlayer(1).code(), "not_disconnected");
        Assert.assertEquals(controller.waitIndefinitelyForDisconnectedPlayer(1).code(), "not_disconnected");
        Assert.assertTrue(controller.abortByAdministrator().success());
        await(() -> controller.state() == DedicatedLobbyController.State.WAITING);
    }
    /**
     * Release-candidate smoke test. It needs independently launched desktop
     * Forge clients: the in-process fixture intentionally shares the EDT and
     * cannot model four simultaneous real client UI dispatchers.
     */
    @Test(dependsOnMethods = "admissionUpdatesAndCleanup", timeOut = 120000,
            enabled = false, description = "Run with separate stock desktop clients before release")
    public void repeatedRemoteMatches() throws Exception {
        int round = 0;
        for (int players : new int[] {2, 2, 3, 4}) {
            boolean commander = players != 2 || lobby.hasVariant(forge.game.GameType.Commander);
            List<forge.net.HeadlessNetworkClient> clients = new ArrayList<>();
            try {
                for (int i = 0; i < players; i++) {
                    forge.net.HeadlessNetworkClient client = new forge.net.HeadlessNetworkClient("Player" + i, "127.0.0.1", port);
                    clients.add(client);
                    Assert.assertTrue(client.connect(5000));
                    Deck deck = new Deck("Legal basic-land fixture");
                    deck.getMain().add(FModel.getMagicDb().getCommonCards().getCard("Plains"), commander ? 99 : 60);
                    if (commander) {
                        deck.getOrCreate(forge.deck.DeckSection.Commander).add(
                                FModel.getMagicDb().getCommonCards().getCard("Isamaru, Hound of Konda"));
                    }
                    client.getClient().send(UpdateLobbyPlayerEvent.deckUpdate(deck));
                }
                await(() -> {
                    for (int i = 0; i < players; i++) { if (lobby.getSlot(i).getDeck() == null) { return false; } }
                    return true;
                });
                clients.forEach(forge.net.HeadlessNetworkClient::setReady);
                for (forge.net.HeadlessNetworkClient client : clients) { Assert.assertTrue(client.waitForGameStart(10000)); }
                await(() -> controller.state() == DedicatedLobbyController.State.PLAYING);
                Assert.assertFalse(controller.acceptsNewPlayers(),
                        "A playing room must reject late admissions.");
                if (round == 0) {
                    // Exercise a normal remote concession and continuation.
                    for (int i = 0; i < players - 1; i++) {
                        clients.get(i).getClient().send(new GuiGameEvent(forge.gamemodes.net.ProtocolMethod.concede));
                    }
                    await(() -> controller.state() == DedicatedLobbyController.State.POSTGAME);
                    for (forge.net.HeadlessNetworkClient client : clients) {
                        client.getClient().send(new GuiGameEvent(
                                forge.gamemodes.net.ProtocolMethod.nextGameDecision, NextGameDecision.CONTINUE));
                    }
                    await(() -> controller.state() == DedicatedLobbyController.State.PLAYING);
                    SwingUtilities.invokeAndWait(() -> controller.abort("Test room reset."));
                } else {
                    SwingUtilities.invokeAndWait(() -> controller.abort("Test room reset."));
                }
                await(() -> controller.state() == DedicatedLobbyController.State.WAITING);
                for (int i = 0; i < players; i++) {
                    Assert.assertFalse(lobby.getSlot(i).isReady());
                    Assert.assertNotNull(lobby.getSlot(i).getDeck());
                }
            } finally { clients.forEach(forge.net.HeadlessNetworkClient::close); }
            await(() -> server.connectedPlayers().isEmpty() && lobby.getSlot(0).getType() == LobbySlotType.OPEN);
            SwingUtilities.invokeAndWait(() -> lobby.applyVariant(forge.game.GameType.Commander));
            round++;
        }
    }

    @AfterClass(alwaysRun = true) public void close() {
        if (pulse != null) { pulse.stop(); }
        peers.forEach(Channel::close);
        if (server != null) { server.shutdownDedicated(); }
        if (group != null) { group.shutdownGracefully().syncUninterruptibly(); }
    }
}
