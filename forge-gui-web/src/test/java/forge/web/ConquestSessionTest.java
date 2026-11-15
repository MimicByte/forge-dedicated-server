package forge.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.Game;
import forge.game.GameType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.HostedMatch;
import forge.gamemodes.match.PreparedMatch;
import forge.gamemodes.planarconquest.ConquestBattle;
import forge.gamemodes.planarconquest.ConquestCommander;
import forge.gamemodes.planarconquest.ConquestData;
import forge.gamemodes.planarconquest.ConquestLocation;
import forge.gamemodes.planarconquest.ConquestPlane;
import forge.gamemodes.planarconquest.ConquestPreferences.CQPref;
import forge.gamemodes.planarconquest.ConquestUtil;
import forge.gui.GuiBase;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgeConstants;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.util.Localizer;
import forge.web.campaign.ConquestGame;
import forge.web.deck.LegalityScope;
import forge.web.deck.DeckImport;
import forge.web.images.ImageFiles;
import forge.web.match.NetplaySeat;
import forge.web.match.WebGuiGame;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** Tests a conquest driven from a browser, with its battles played over netplay. */
public class ConquestSessionTest extends WebHubTest {
    @Override
    WebGuiBase gui() {
        return (WebGuiBase) GuiBase.getInterface();
    }

    @Override
    void afterDisconnecting() {
        ConquestFixture.cleanUp();
    }

    /** Starts the battle at a place over the loopback without a session, then passes the running match to body. */
    private static void battleAt(final ConquestLocation loc, final Consumer<HostedMatch> body) {
        final NetplaySeat local = new NetplaySeat();
        try {
            final WebGuiGame gui = new WebGuiGame(PlayerSettings.saved());
            gui.attach(new TestBrowser(gui, false));
            final ConquestBattle battle = loc.getEvent().createBattle(loc, 0);
            onUi(() -> {
                final PreparedMatch p = FModel.getConquest().prepareBattle(battle, null);
                local.openAlone("Web Player", gui).startPrepared(p, local.webSeat());
            });
            body.accept(local.hostedMatch());
        } finally {
            onUi(local::shutdown);
        }
    }

    // Fails if a Commander event over netplay does not start both players at 30 life with their commanders in the command zone
    @Test(timeOut = 120_000)
    public void aCommanderEventStartsAsConquestPlaysIt() {
        WebTestSupport.skipUnlessStress();
        final ConquestData data = ConquestFixture.create("Zendikar");
        final ConquestLocation loc = ConquestRulesTest.eventWhere(data.getCurrentPlane(), v -> v.equals(Set.of(GameType.Commander)));
        battleAt(loc, match -> {
            final Game game = match.getGame();
            Assert.assertEquals(game.getPlayers().size(), 2);
            for (final Player p : game.getPlayers()) {
                Assert.assertEquals(p.getStartingLife(), 30);
                awaitTrue(() -> p.getCardsIn(ZoneType.Command).stream().filter(c -> c.isCommander()).count() == 1,
                        p.getName() + " has no commander in the command zone");
            }
            Assert.assertEquals(game.getRules().getGameType(), GameType.PlanarConquest);
            // Throws if no human player is on a web seat
            WebTestSupport.remoteGui(match);
        });
    }

    private TestBrowser hostInConquest(final ConquestData data) throws InterruptedException {
        final TestBrowser host = onTheShelf();
        send(host, message("campaignLoad", "name", data.getName()));
        host.awaitMatching("hello", h -> data.getName().equals(str(h, "campaignSave")), "the conquest did not open");
        host.awaitMatching("conquestState", s -> true, "the map was not sent");
        return host;
    }

    /** The amount the bar shows for the balance with this icon. */
    private static int balance(final JsonObject bar, final String icon) {
        for (final JsonElement b : bar.getAsJsonArray("balances")) {
            if (icon.equals(b.getAsJsonObject().get("icon").getAsString())) {
                return b.getAsJsonObject().get("amount").getAsInt();
            }
        }
        throw new AssertionError("The bar has no balance of " + icon);
    }

    private static JsonObject cell(final JsonObject state, final int region, final int row, final int col) {
        for (final JsonElement e : state.getAsJsonArray("cells")) {
            final JsonObject c = e.getAsJsonObject();
            if (c.get("region").getAsInt() == region && c.get("row").getAsInt() == row && c.get("col").getAsInt() == col) {
                return c;
            }
        }
        throw new AssertionError("no such cell");
    }

    // Fails if a new conquest's map does not put the player at the first place with only that place open
    @Test(timeOut = 120_000)
    public void aConquestOpensOnItsMap() throws Exception {
        final ConquestData data = ConquestFixture.create("Zendikar");
        final TestBrowser host = hostInConquest(data);
        final JsonObject state = host.awaitNewest("conquestState");
        Assert.assertEquals(state.get("plane").getAsString(), "Zendikar");
        // The expected counts of regions and places come from the plane's own data
        final int events = data.getCurrentPlane().getEventCount();
        Assert.assertEquals(state.getAsJsonArray("regions").size(), data.getCurrentPlane().getRegions().size());
        Assert.assertEquals(state.getAsJsonArray("cells").size(), events);
        Assert.assertEquals(cell(state, 0, 0, 0).get("state").getAsString(), "open");
        Assert.assertEquals(cell(state, 0, 1, 0).get("state").getAsString(), "fog");
        Assert.assertFalse(cell(state, 0, 1, 0).has("name"), "a place not yet found says what is there");
        final JsonObject bar = host.awaitNewest("campaignBar");
        Assert.assertEquals(balance(bar, "IMG_AETHER_SHARD"), data.getAEtherShards());
        Assert.assertTrue(str(bar, "line").endsWith("/ " + events), "the bar does not count the plane's events: " + str(bar, "line"));
    }

    // Fails if selecting a place moves the player, or closes the portal at the player's current place
    @Test(timeOut = 120_000)
    public void selectingDoesNotMove() throws Exception {
        final ConquestData data = ConquestFixture.create("Eldraine");
        final ConquestLocation portal = ConquestFixture.portal(data.getCurrentPlane());
        final ConquestPlane secret = ConquestUtil.getPlaneByName(portal.getEvent().getTemporaryUnlock());
        data.setCurrentLocation(portal);
        data.addWin(portal.getEvent().createBattle(portal, 0));
        data.saveData();
        final TestBrowser host = hostInConquest(data);
        ConquestUtil.setPlaneTemporarilyAccessible(secret.getName(), true);
        final ConquestLocation next = portal.getNeighbors().get(0);
        send(host, message("conquestSelect", "region", next.getRegionIndex(), "row", next.getRow(), "col", next.getCol()));
        final JsonObject state = host.awaitNewest("conquestState", "selecting sent no map");
        Assert.assertEquals(state.get("steps").getAsInt(), 1);
        Assert.assertEquals(state.getAsJsonObject("at").get("row").getAsInt(), portal.getRow());
        Assert.assertEquals(FModel.getConquest().getModel().getCurrentLocation(), portal);
        Assert.assertFalse(secret.isUnreachable(), "selecting closed the portal");
    }

    // Fails if a move does not save the new place, or does not send a path for the marker to follow
    @Test(timeOut = 120_000)
    public void movingSavesThePlaceAndSendsThePath() throws Exception {
        final ConquestData data = ConquestFixture.create("Zendikar");
        final ConquestLocation start = data.getCurrentLocation();
        data.addWin(start.getEvent().createBattle(start, 0));
        data.saveData();
        final TestBrowser host = hostInConquest(data);
        final ConquestLocation next = start.getNeighbors().get(0);
        sessions.onMessage(host, message("conquestSelect", "region", next.getRegionIndex(), "row", next.getRow(), "col", next.getCol()));
        send(host, TestBrowser.action("conquestMove"));
        final JsonObject state = host.awaitNewest("conquestState", s -> s.getAsJsonArray("path").size() == 2, "the move sent no path");
        Assert.assertEquals(state.get("steps").getAsInt(), 0);
        Assert.assertEquals(new ConquestData(data.getDirectory()).getCurrentLocation(), next);
    }

    // Fails if a walk steps between two places that are not conquered, where the map draws no line
    @Test(timeOut = 120_000)
    public void aWalkFollowsTheLines() throws Exception {
        final ConquestData data = ConquestFixture.create("Zendikar");
        final ConquestPlane plane = data.getCurrentPlane();
        Assert.assertTrue(plane.getRowsPerRegion() >= 2 && plane.getCols() >= 3, "the plane is too small for this map");
        // A row of three with only the first conquered, and the row above it conquered. The engine's shortest path runs along the open row
        final List<ConquestLocation> won = List.of(new ConquestLocation(plane, 0, 0, 0), new ConquestLocation(plane, 0, 1, 0),
                new ConquestLocation(plane, 0, 1, 1), new ConquestLocation(plane, 0, 1, 2));
        for (final ConquestLocation loc : won) {
            data.addWin(loc.getEvent().createBattle(loc, 0));
        }
        data.saveData();
        final TestBrowser host = hostInConquest(data);
        final ConquestLocation end = new ConquestLocation(plane, 0, 0, 2);
        sessions.onMessage(host, message("conquestSelect", "region", 0, "row", end.getRow(), "col", end.getCol()));
        send(host, TestBrowser.action("conquestMove"));
        final JsonArray path = host.awaitNewest("conquestState", s -> s.getAsJsonArray("path").size() > 0, "the move sent no path").getAsJsonArray("path");
        Assert.assertEquals(path.size(), 5, "the walk is not the shortest one along the lines: " + path);
        for (int i = 1; i < path.size() - 1; i++) {
            final JsonObject step = path.get(i).getAsJsonObject();
            final ConquestLocation loc = new ConquestLocation(plane, step.get("region").getAsInt(), step.get("row").getAsInt(), step.get("col").getAsInt());
            Assert.assertTrue(data.getCurrentPlaneData().hasConquered(loc), "the walk crosses a place not conquered: " + path);
        }
    }

    // Fails if Battle starts a match with a deck Conquest does not allow
    @Test(timeOut = 120_000)
    public void anIllegalDeckDoesNotBattle() throws Exception {
        final ConquestData data = ConquestFixture.create("Zendikar");
        final TestBrowser host = hostInConquest(data);
        FModel.getConquest().getModel().getSelectedCommander().getDeck().getMain().clear();
        send(host, TestBrowser.action("conquestBattle"));
        host.awaitMatching("error", e -> true, "the illegal deck was not reported");
        Assert.assertNull(FModel.getConquest().getActiveBattle());
    }

    // Fails if a browser without the host's seat can open a conquest
    @Test(timeOut = 120_000)
    public void aGuestCannotOpenConquest() throws Exception {
        final TestBrowser guest = connect("guest");
        sessions.onMessage(guest, message("setName", "name", "Guest"));
        send(guest, message("campaignOpen", "mode", "conquest", "resume", false));
        Thread.sleep(500);
        Assert.assertFalse(guest.got.stream().anyMatch(m -> "conquestSaves".equals(m.get("t").getAsString())));
    }

    private boolean devModeBefore;

    @BeforeClass
    public void allowTheWheelToBeSet() {
        WebTestSupport.initModel();
        devModeBefore = FModel.getPreferences().getPrefBoolean(FPref.DEV_MODE_ENABLED);
        FModel.getPreferences().setPref(FPref.DEV_MODE_ENABLED, true);
    }

    @AfterClass(alwaysRun = true)
    public void restoreDevMode() {
        FModel.getPreferences().setPref(FPref.DEV_MODE_ENABLED, devModeBefore);
    }

    private TestBrowser inBattle(final ConquestData data) throws InterruptedException {
        final TestBrowser host = hostInConquest(data);
        send(host, TestBrowser.action("conquestBattle"));
        host.awaitMatching("hello", h -> "playing".equals(h.get("screen").getAsString()), "the battle did not start");
        awaitPriority(host);
        return host;
    }

    // Fails if a win is not recorded and rewarded exactly once, or a reconnecting browser does not get the same reward with the save unchanged
    @Test(timeOut = 180_000)
    public void aWinIsRecordedAndRewardedOnce() throws Exception {
        WebTestSupport.skipUnlessStress();
        final ConquestData data = ConquestFixture.create("Zendikar");
        final int shards = data.getAEtherShards();
        final int wheelShards = FModel.getConquestPreferences().getPrefInt(CQPref.AETHER_WHEEL_SHARDS);
        final int emblems = FModel.getConquestPreferences().getPrefInt(CQPref.PLANESWALK_CONQUER_EMBLEMS);
        final TestBrowser host = inBattle(data);
        sessions.onMessage(host, message("devConquestWheel", "outcome", "SHARDS"));
        computerLoses(host);
        final JsonObject result = host.awaitMatching("campaignResult", r -> true, "no result was sent");
        Assert.assertTrue(result.get("won").getAsBoolean());
        Assert.assertTrue(str(result, "line").endsWith(Localizer.getInstance().getMessage("lblWebConquestFirstConquest")), "the result does not say the event is conquered");
        final ConquestData model = FModel.getConquest().getModel();
        Assert.assertTrue(model.getCurrentPlaneData().hasConquered(model.getCurrentLocation()));
        Assert.assertEquals(model.getPlaneswalkEmblems(), emblems);
        Assert.assertEquals(model.getAEtherShards(), shards + wheelShards);

        // A reload on the result screen shows the same result and records nothing again
        final TestBrowser reloaded = connect("host");
        Assert.assertEquals(reloaded.awaitMatching("campaignResult", r -> true, "a reload lost the result"), result);
        Assert.assertEquals(model.getCurrentPlaneData().getEventRecord(model.getCurrentLocation()).getTotalWins(), 1);
        Assert.assertEquals(model.getAEtherShards(), shards + wheelShards);

        host.forget();
        sessions.onMessage(reloaded, TestBrowser.action("leave"));
        final JsonObject reward = reloaded.awaitMatching("reward", r -> true, "the reward was not sent with the map");
        Assert.assertEquals(reward.getAsJsonArray("steps").size(), 3);

        final TestBrowser again = connect("host");
        final JsonObject resent = again.awaitMatching("reward", r -> true, "a reload lost the reward");
        Assert.assertEquals(resent, reward);
        Assert.assertEquals(model.getAEtherShards(), shards + wheelShards);
        Assert.assertEquals(model.getPlaneswalkEmblems(), emblems);

        // No other battle starts until the reward is shown, because the reward may lead to a battle that cannot be refused
        sessions.onMessage(again, TestBrowser.action("conquestBattle"));
        onUi(() -> { });
        Assert.assertNull(FModel.getConquest().getActiveBattle(), "a battle started over a reward still to be shown");

        send(again, TestBrowser.action("rewardClaim"));
        final TestBrowser third = connect("host");
        third.awaitMatching("conquestState", s -> true, "the map was not sent");
        Assert.assertFalse(third.got.stream().anyMatch(m -> "reward".equals(m.get("t").getAsString())),
                "an acknowledged reward was sent again");
    }

    // Fails if a lost Commander event restarted from the result screen does not start again at 30 life, or the loss is not recorded
    @Test(timeOut = 180_000)
    public void retryKeepsTheBattlesSetUp() throws Exception {
        WebTestSupport.skipUnlessStress();
        final ConquestData data = ConquestFixture.create("Zendikar");
        final ConquestLocation loc = ConquestRulesTest.eventWhere(data.getCurrentPlane(), v -> v.equals(Set.of(GameType.Commander)));
        data.setCurrentLocation(loc);
        data.saveData();
        final TestBrowser host = inBattle(data);
        sessions.onMessage(host, TestBrowser.action("concede"));
        final JsonObject result = host.awaitMatching("campaignResult", r -> true, "no result was sent");
        Assert.assertFalse(result.get("won").getAsBoolean());
        Assert.assertEquals(FModel.getConquest().getModel().getCurrentPlaneData().getEventRecord(loc).getTotalLosses(), 1);
        send(host, message("nextGame", "decision", "NEW"));
        awaitPriority(host);
        final Game game = sessions.hostLobby().getHostedMatch().getGame();
        for (final Player p : game.getPlayers()) {
            Assert.assertEquals(p.getStartingLife(), 30);
        }
    }

    // Fails if a wheel result of chaos does not start a best-of-three after the reward is claimed, or leaving that match early is not a loss
    @Test(timeOut = 240_000)
    public void aChaosBattleFollowsTheWheelAndLeavingItIsALoss() throws Exception {
        WebTestSupport.skipUnlessStress();
        final ConquestData data = ConquestFixture.create("Zendikar");
        final TestBrowser host = inBattle(data);
        sessions.onMessage(host, message("devConquestWheel", "outcome", "CHAOS"));
        computerLoses(host);
        host.awaitMatching("campaignResult", r -> true, "no result was sent");
        send(host, TestBrowser.action("leave"));
        host.awaitMatching("reward", r -> true, "the reward was not sent");
        send(host, TestBrowser.action("rewardClaim"));
        host.awaitMatching("hello", h -> "playing".equals(h.get("screen").getAsString()), "the chaos battle did not start");
        awaitPriority(host);
        Assert.assertEquals(sessions.hostLobby().getHostedMatch().getGame().getRules().getGamesPerMatch(), 3);
        final int losses = FModel.getConquest().getModel().getChaosBattleRecord().getLosses();
        send(host, TestBrowser.action("leave"));
        host.awaitMatching("conquestState", s -> true, "leaving did not return to the map");
        Assert.assertEquals(FModel.getConquest().getModel().getChaosBattleRecord().getLosses(), losses + 1);
        Assert.assertNull(FModel.getConquest().getActiveBattle());
    }

    // Fails if a battle that could not start blocks the next battle, or records anything
    @Test(timeOut = 180_000)
    public void aBattleThatFailsToStartLeavesTheConquestUsable() throws Exception {
        WebTestSupport.skipUnlessStress();
        final ConquestData data = ConquestFixture.create("Zendikar");
        final TestBrowser host = hostInConquest(data);
        // While a battle is marked active, the next battle cannot be prepared
        final ConquestLocation loc = FModel.getConquest().getModel().getCurrentLocation();
        FModel.getConquest().prepareBattle(loc.getEvent().createBattle(loc, 0), null);
        send(host, TestBrowser.action("conquestBattle"));
        host.awaitMatching("error", e -> true, "the failed start was not reported");
        host.awaitMatching("hello", h -> !"playing".equals(h.get("screen").getAsString()) && "conquest".equals(str(h, "campaign")), "the page did not return to the map");
        Assert.assertNull(FModel.getConquest().getActiveBattle());
        Assert.assertNull(FModel.getConquest().getModel().getCurrentPlaneData().getEventRecord(loc), "a battle that never started was recorded");

        send(host, TestBrowser.action("conquestBattle"));
        host.awaitMatching("hello", h -> "playing".equals(h.get("screen").getAsString()), "a second battle did not start");
        awaitPriority(host);
    }

    // Fails if one unreadable save stops the other saves from being listed
    @Test
    public void anUnreadableSaveDoesNotHideTheOthers() throws Exception {
        final ConquestData data = ConquestFixture.create("Zendikar");
        final String broken = ConquestFixture.broken("<data><planeswalker>nothing a card database knows</planeswalker>");
        // A card with no name stops the read after the planeswalker and the place, before the planes
        final String halfRead = ConquestFixture.broken("<data><planeswalker art=\"1\" name=\"A-Ellywick Tumblestrum\" set=\"AFR\"/>"
                + "<currentLocation col=\"0\" plane=\"Zendikar\" regionIndex=\"0\" row=\"0\"/>"
                + "<unlockedCards><card art=\"1\" name=\"\" set=\"C16\"/></unlockedCards></data>");
        final List<String> names = new ConquestGame().saves().saves().stream().map(s -> s.name()).toList();
        Assert.assertTrue(names.contains(data.getName()), "the readable save is not listed");
        Assert.assertFalse(names.contains(broken), "a save with no planeswalker or place is listed");
        Assert.assertFalse(names.contains(halfRead), "a save with no planes is listed");
    }

    private TestBrowser editor;

    // The session lives longer than a test. Without this, the next test's browser would see an open editor or a removed conquest
    @AfterMethod(alwaysRun = true)
    public void leaveTheConquest() throws InterruptedException {
        if (editor != null) {
            sessions.onMessage(editor, TestBrowser.action("editorClose"));
            editor = null;
        }
        // After a reconnect two browsers have held the host's seat. Only the later one's messages are handled
        TestBrowser host = null;
        for (final TestBrowser browser : browsers) {
            if (browser.hello != null && browser.hello.get("host").getAsBoolean()) {
                host = browser;
            }
        }
        if (host == null) {
            return;
        }
        if ("playing".equals(host.hello.get("screen").getAsString())) {
            send(host, TestBrowser.action("leave"));
            if (host.awaitMatching("hello", h -> !"playing".equals(h.get("screen").getAsString())) == null) {
                return;
            }
        }
        if (str(host.hello, "campaignSave") != null) {
            send(host, TestBrowser.action("campaignLeave"));
            // The shelf reads every save when it opens. The cleanup must not remove a save during that read
            if (host.awaitMatching("conquestSaves", saves -> true) == null) {
                return;
            }
        }
        if ("conquest".equals(str(host.hello, "campaign"))) {
            send(host, TestBrowser.action("campaignLeave"));
            host.awaitMatching("hello", h -> !"conquest".equals(str(h, "campaign")));
        }
    }

    /** Opens the selected commander's deck in the editor and returns the editor's state. */
    private JsonObject editing(final TestBrowser host) throws InterruptedException {
        editor = host;
        send(host, message("conquestEditDeck", "commander", FModel.getConquest().getModel().getSelectedCommander().getName()));
        return host.awaitMatching("editor", e -> e.has("state"), "the editor did not open").getAsJsonObject("state");
    }

    /** Sends the message and returns the editor's state from its reply. */
    private JsonObject edited(final TestBrowser host, final JsonObject message) throws InterruptedException {
        send(host, message);
        return host.awaitMatching("editor", e -> e.has("state"), "the editor did not answer").getAsJsonObject("state");
    }

    private static List<String> mainNames(final JsonObject editorState) {
        final List<String> names = new ArrayList<>();
        editorState.getAsJsonArray("main").forEach(g -> g.getAsJsonObject().getAsJsonArray("cards")
                .forEach(c -> names.add(c.getAsJsonObject().get("name").getAsString())));
        return names;
    }

    /** Owned cards that are not in a deck, not exiled, not basic lands, and not a commander or the planeswalker, in name order. */
    private static List<PaperCard> spares(final ConquestData data) {
        final List<PaperCard> out = new ArrayList<>();
        for (final PaperCard card : data.getUnlockedCards()) {
            boolean used = card.equals(data.getPlaneswalker()) || data.isInExile(card) || card.getRules().getType().isBasicLand();
            for (final ConquestCommander c : data.getCommanders()) {
                used |= c.getCard().equals(card) || c.getDeck().getMain().countByName(card.getName()) > 0;
            }
            if (!used) {
                out.add(card);
            }
        }
        out.sort((a, b) -> a.getName().compareTo(b.getName()));
        return out;
    }

    /** A card the conquest does not own under any printing. */
    private static PaperCard stranger(final ConquestData data) {
        final Set<String> owned = new HashSet<>();
        data.getUnlockedCards().forEach(c -> owned.add(c.getName()));
        for (final PaperCard card : FModel.getMagicDb().getCommonCards().getUniqueCards()) {
            if (!owned.contains(card.getName()) && card.getRules().getType().isCreature() && !card.getRules().getType().isLegendary()) {
                return card;
            }
        }
        throw new AssertionError("every card is owned");
    }

    private static Deck storedDeck(final String name) {
        return FModel.getConquest().getDecks().get(name);
    }

    // Fails if the editor's catalogue lists a card that is exiled or not owned, or such a card can be added to the deck
    @Test(timeOut = 120_000)
    public void theEditorOffersOnlyOwnedCards() throws Exception {
        final TestBrowser host = hostInConquest(ConquestFixture.install());
        final ConquestData data = FModel.getConquest().getModel();
        final PaperCard exiled = spares(data).get(0);
        data.exile(List.of(exiled));
        final JsonObject opened = editing(host);
        Assert.assertEquals(str(opened, "collection"), data.getName());
        send(host, message("catalogue", "request", 1, "text", "", "colours", "", "type", "any", "filters", "",
                "sort", "name", "offset", 0, "showAll", true));
        final JsonObject page = host.awaitMatching("catalogue", c -> true, "the catalogue did not answer");
        final Set<String> available = new HashSet<>();
        ConquestUtil.getAvailablePool().forEach(e -> available.add(e.getKey().getName()));
        final List<String> listed = new ArrayList<>();
        page.getAsJsonArray("rows").forEach(r -> listed.add(r.getAsJsonObject().get("name").getAsString()));
        Assert.assertFalse(listed.isEmpty(), "the catalogue is empty");
        Assert.assertTrue(available.containsAll(listed), "the catalogue lists cards that are not owned: " + listed);
        Assert.assertFalse(listed.contains(exiled.getName()), "an exiled card is offered");
        Assert.assertEquals(page.get("total").getAsInt(), available.size());

        final String stranger = stranger(data).getName();
        final JsonObject after = edited(host, message("editorEdit", "op", "add", "name", stranger, "to", "Main", "count", 1));
        Assert.assertFalse(mainNames(after).contains(stranger), "a card that is not owned was added");
        Assert.assertTrue(host.got.stream().anyMatch(m -> "notice".equals(m.get("t").getAsString())), "the refusal was not said");
        final JsonObject again = edited(host, message("editorEdit", "op", "add", "name", exiled.getName(), "to", "Main", "count", 1));
        Assert.assertFalse(mainNames(again).contains(exiled.getName()), "an exiled card was added");
    }

    // Fails if a card added in the editor is not in the next battle's deck, or the map does not get the deck's new size on close
    @Test(timeOut = 120_000)
    public void anEditedDeckIsTheDeckFoughtWith() throws Exception {
        final TestBrowser host = hostInConquest(ConquestFixture.install());
        final ConquestData data = FModel.getConquest().getModel();
        final String spare = spares(data).get(0).getName();
        final int size = data.getSelectedCommander().getDeck().getMain().countAll();
        editing(host);
        Assert.assertTrue(mainNames(edited(host, message("editorEdit", "op", "add", "name", spare, "to", "Main", "count", 1))).contains(spare));
        send(host, TestBrowser.action("editorClose"));
        host.awaitMatching("conquestState", s -> s.getAsJsonObject("commander").get("deckSize").getAsInt() == size + 1,
                "the map was not told the deck's new size");
        onUi(() -> {
            final ConquestLocation loc = data.getCurrentLocation();
            final PreparedMatch prepared = FModel.getConquest().prepareBattle(loc.getEvent().createBattle(loc, 0), null);
            try {
                Assert.assertEquals(prepared.human().getDeck().getMain().countByName(spare), 1, "the battle's deck lacks the card added");
            } finally {
                FModel.getConquest().cancelBattle();
            }
        });
    }

    // Fails if any editor request changes the deck's Commander section or its name
    @Test(timeOut = 120_000)
    public void theCommanderStays() throws Exception {
        final TestBrowser host = hostInConquest(ConquestFixture.install());
        final ConquestData data = FModel.getConquest().getModel();
        final ConquestCommander commander = data.getSelectedCommander();
        final List<PaperCard> spares = spares(data);
        editing(host);
        edited(host, message("editorEdit", "op", "add", "name", spares.get(0).getName(), "to", "Main", "count", 1));
        edited(host, TestBrowser.action("editorUndo"));
        edited(host, message("editorEdit", "op", "remove", "name", commander.getName(), "from", "Commander", "count", 1));
        edited(host, message("editorEdit", "op", "move", "name", commander.getName(), "from", "Commander", "to", "Main", "count", 1));
        edited(host, message("editorEdit", "op", "commander", "name", spares.get(0).getName(), "count", 1));
        edited(host, message("editorRename", "name", "Another name"));
        edited(host, message("editorDeck", "op", "duplicate"));
        edited(host, message("editorDeck", "op", "delete"));
        edited(host, message("editorCheck", "format", "Commander", "unrestricted", false));
        Assert.assertEquals(edited(host, message("editorCheck", "format", "Constructed", "unrestricted", true)).get("format").getAsString(), "PlanarConquest");
        final JsonObject replaced = edited(host, message("importCommit", "text",
                "Commander\n1 " + commander.getName() + "\n\nDeck\n1 " + spares.get(0).getName() + "\n", "name", "Imported",
                "format", "PlanarConquest", "unrestricted", false, "action", "replace"));
        Assert.assertEquals(mainNames(replaced), List.of(spares.get(0).getName()), "the import did not replace the main deck");
        Assert.assertEquals(replaced.get("name").getAsString(), commander.getName());
        final Deck stored = storedDeck(commander.getName());
        Assert.assertNotNull(stored, "the deck is no longer saved under the commander's name");
        Assert.assertEquals(stored.get(DeckSection.Commander).toFlatList(), List.of(commander.getCard()));
        Assert.assertNull(storedDeck("Another name"));
        Assert.assertNull(storedDeck("Imported"));
        Assert.assertEquals(FModel.getConquest().getDecks().size(), 1, "the deck was copied");
        Assert.assertSame(commander.getDeck(), stored, "the commander does not hold the deck that was saved");
    }

    // Fails if an import adds a card that is not owned, drops an owned card listed under another printing, or refuses the basic lands
    @Test(timeOut = 120_000)
    public void importTakesOnlyOwnedCards() throws Exception {
        final TestBrowser host = hostInConquest(ConquestFixture.install());
        final ConquestData data = FModel.getConquest().getModel();
        PaperCard owned = null;
        PaperCard otherPrinting = null;
        for (final PaperCard spare : spares(data)) {
            for (final PaperCard printing : FModel.getMagicDb().getCommonCards().getAllCards(spare.getName())) {
                if (!printing.getEdition().equals(spare.getEdition())) {
                    owned = spare;
                    otherPrinting = printing;
                }
            }
        }
        Assert.assertNotNull(owned, "the fixture owns no spare card with a second printing");
        final String stranger = stranger(data).getName();
        final int forests = data.getSelectedCommander().getDeck().getMain().countByName("Forest");
        final String list = "1 " + owned.getName() + " (" + otherPrinting.getEdition() + ")\n1 " + stranger + "\n5 Forest\n";
        Assert.assertNotEquals(DeckImport.read(list, LegalityScope.of(GameType.PlanarConquest, null)).deck().getMain().toFlatList().get(0), owned,
                "the list names the printing owned, so it shows nothing");
        editing(host);
        send(host, message("importRead", "request", 7, "text", list, "format", "PlanarConquest", "unrestricted", false));
        final JsonObject read = host.awaitMatching("importResult", r -> true, "the list was not read");
        Assert.assertEquals(read.getAsJsonArray("lines").get(0).getAsJsonObject().get("kind").getAsString(), "read");
        Assert.assertEquals(read.getAsJsonArray("lines").get(1).getAsJsonObject().get("kind").getAsString(), "problem",
                "a card that is not owned was not marked");
        Assert.assertEquals(read.getAsJsonArray("lines").get(2).getAsJsonObject().get("kind").getAsString(), "read");
        Assert.assertEquals(read.getAsJsonObject("summary").get("notImported").getAsInt(), 1);

        final JsonObject added = edited(host, message("importCommit", "text", list, "name", "Imported", "format", "PlanarConquest",
                "unrestricted", false, "action", "add"));
        Assert.assertTrue(mainNames(added).contains(owned.getName()), "the owned card was left out");
        Assert.assertFalse(mainNames(added).contains(stranger), "a card that is not owned was imported");
        final Deck stored = storedDeck(data.getSelectedCommander().getName());
        Assert.assertEquals(stored.getMain().count(owned), 1, "the card went in as a printing that is not owned");
        Assert.assertEquals(stored.getMain().countByName("Forest"), forests + 5);
    }

    private static JsonObject exile(final PaperCard card, final boolean retrieve) {
        final JsonObject m = message("trade", "source", retrieve ? "exile" : "collection");
        final JsonArray picks = new JsonArray();
        final JsonObject pick = new JsonObject();
        pick.addProperty("key", card.getImageKey(false));
        pick.addProperty("count", 1);
        picks.add(pick);
        m.add("picks", picks);
        return m;
    }

    /** The number of cards in one of the campaign's lists. */
    private static int listed(final JsonObject trading, final String source) {
        for (final JsonElement l : trading.getAsJsonArray("lists")) {
            if (source.equals(l.getAsJsonObject().get("source").getAsString())) {
                return l.getAsJsonObject().get("count").getAsInt();
            }
        }
        throw new AssertionError("There is no list of " + source);
    }

    private List<String> cardsOf(final TestBrowser host, final String source) throws InterruptedException {
        send(host, message("catalogue", "request", 1, "text", "", "colours", "", "type", "any", "filters", "",
                "sort", "name", "offset", 0, "showAll", true, "source", source));
        final List<String> names = new ArrayList<>();
        final JsonObject page = host.awaitMatching("catalogue", c -> true, "the " + source + " was not listed");
        // The browser uses one catalogue page for the editor and both lists. It uses this field to tell them apart
        Assert.assertEquals(str(page, "source"), source);
        page.getAsJsonArray("rows").forEach(r -> names.add(r.getAsJsonObject().get("name").getAsString()));
        return names;
    }

    // Fails if a session's campaign thread stays alive after its work. Every session that ever used one would then keep a thread
    @Test(timeOut = 120_000)
    public void campaignWorkLeavesNoThreadBehind() throws Exception {
        onTheShelf();
        final long end = System.currentTimeMillis() + 30_000;
        while (Thread.getAllStackTraces().keySet().stream().anyMatch(t -> "WebCampaign".equals(t.getName()) && t.isAlive())) {
            Assert.assertTrue(System.currentTimeMillis() < end, "a campaign thread is still alive half a minute after its work");
            Thread.sleep(250);
        }
    }

    // Fails if a conquest's messages are answered out of order, as can happen when they run on separate threads
    @Test(timeOut = 120_000)
    public void messagesAreAnsweredInTheOrderSent() throws Exception {
        final TestBrowser host = hostInConquest(ConquestFixture.install());
        host.forget();
        sessions.onMessage(host, message("catalogue", "request", 1, "text", "", "colours", "", "type", "any", "filters", "",
                "sort", "name", "offset", 0, "showAll", true, "source", "collection"));
        sessions.onMessage(host, TestBrowser.action("trading"));
        host.awaitMatching("catalogue", c -> true, "the collection was not listed");
        host.awaitMatching("trading", c -> true, "the collection was not counted");
        final List<String> types = new ArrayList<>();
        host.got.forEach(m -> types.add(m.get("t").getAsString()));
        Assert.assertTrue(types.indexOf("catalogue") < types.indexOf("trading"), "the later message was answered first: " + types);
    }

    // Fails if a card in a deck can be exiled, or exiling and retrieving a free card do not pay, charge and move it between the lists
    @Test(timeOut = 120_000)
    public void exileAndRetrieveOverTheWire() throws Exception {
        final TestBrowser host = hostInConquest(ConquestFixture.install());
        final ConquestData data = FModel.getConquest().getModel();
        final PaperCard inDeck = data.getSelectedCommander().getDeck().getMain().toFlatList().stream()
                .filter(c -> !c.getRules().getType().isBasicLand()).findFirst().orElseThrow();
        final int shards = data.getAEtherShards();
        send(host, exile(inDeck, false));
        host.awaitMatching("notice", n -> true, "exiling a card in use was not refused");
        // A refused trade still ends with the lists. The next send clears the replies, so a late one would be read as the next trade's
        host.awaitMatching("trading", c -> true, "the refused exile's lists were not sent");
        Assert.assertFalse(data.isInExile(inDeck));
        Assert.assertEquals(data.getAEtherShards(), shards);

        final PaperCard spare = spares(data).get(0);
        final int value = data.getExileValue(List.of(spare));
        final int cost = data.getRetrieveCost(List.of(spare));
        send(host, exile(spare, false));
        host.awaitMatching("campaignBar", b -> balance(b, "IMG_AETHER_SHARD") == shards + value, "the exile was not paid for");
        Assert.assertEquals(listed(host.awaitMatching("trading", c -> true, "the lists' sizes were not sent"), "exile"), 1);
        Assert.assertFalse(cardsOf(host, "collection").contains(spare.getName()), "an exiled card is still in the collection");
        Assert.assertEquals(cardsOf(host, "exile"), List.of(spare.getName()));
        final JsonObject row = host.awaitNewest("catalogue").getAsJsonArray("rows").get(0).getAsJsonObject();
        Assert.assertEquals(row.get("value").getAsInt(), cost);

        // With too few shards the card stays in exile, and the notice uses mobile's message to say why
        data.spendAEtherShards(data.getAEtherShards());
        send(host, exile(spare, true));
        host.awaitMatching("notice", n -> true, "a retrieval that cannot be paid for was not refused");
        host.awaitMatching("trading", c -> true, "the refused retrieval's lists were not sent");
        Assert.assertTrue(data.isInExile(spare));
        data.rewardAEtherShards(shards + value);

        send(host, exile(spare, true));
        host.awaitMatching("campaignBar", b -> balance(b, "IMG_AETHER_SHARD") == shards + value - cost, "the retrieval was not charged");
        host.awaitMatching("trading", c -> true, "the retrieval's lists were not sent");
        Assert.assertFalse(data.isInExile(spare));
        Assert.assertTrue(cardsOf(host, "collection").contains(spare.getName()));
    }

    /** Connects the host and opens the list of saved conquests, whatever state the last test left the session in. */
    private TestBrowser onTheShelf() throws InterruptedException {
        final TestBrowser host = connect("host");
        sessions.onMessage(host, TestBrowser.action("claimHost"));
        host.awaitMatching("hello", h -> h.get("host").getAsBoolean(), "the host's seat was not given");
        sessions.onMessage(host, message("setName", "name", "Host"));
        send(host, message("campaignOpen", "mode", "conquest", "resume", false));
        host.awaitMatching("hello", h -> "conquest".equals(str(h, "campaign")) && str(h, "campaignSave") == null, "the shelf did not open");
        host.awaitMatching("conquestSaves", s -> true, "the conquests were not listed");
        return host;
    }

    private static JsonObject pref(final String key, final int value) {
        return message("campaignPref", "key", key, "value", String.valueOf(value));
    }

    private static List<String> names(final JsonObject message, final String list) {
        final List<String> names = new ArrayList<>();
        message.getAsJsonArray(list).forEach(e -> names.add(e.getAsJsonObject().get("name").getAsString()));
        return names;
    }

    private static File saveDir(final String name) {
        return new File(ForgeConstants.CONQUEST_SAVE_DIR, name.replace(' ', '_'));
    }

    // Fails if the form does not get a plane's commanders or a commander's planeswalkers, or a conquest made from them is not saved and shown
    @Test(timeOut = 120_000)
    public void aCreatedConquestOpensOnItsMap() throws Exception {
        final String name = ConquestFixture.expected();
        final TestBrowser host = onTheShelf();
        send(host, TestBrowser.action("conquestOptions"));
        final JsonObject planes = host.awaitMatching("conquestOptions", o -> true, "the planes were not sent");
        Assert.assertTrue(names(planes, "planes").contains("Zendikar"));
        Assert.assertTrue(planes.get("startShards").getAsInt() > 0);
        send(host, message("conquestOptions", "plane", "Zendikar"));
        final List<String> commanders = names(host.awaitMatching("conquestOptions", o -> o.has("commanders"), "the plane's commanders were not sent"), "commanders");
        final String commander = commanders.get(0);
        send(host, message("conquestOptions", "plane", "Zendikar", "commander", commander));
        final String walker = names(host.awaitMatching("conquestOptions", o -> o.has("planeswalkers"), "the commander's planeswalkers were not sent"), "planeswalkers").get(0);
        send(host, message("conquestCreate", "name", name, "plane", "Zendikar", "commander", commander, "planeswalker", walker));
        host.awaitMatching("hello", h -> name.equals(str(h, "campaignSave")), "the new conquest did not open");
        final JsonObject state = host.awaitMatching("conquestState", s -> true, "the new conquest's map was not sent");
        Assert.assertEquals(state.get("plane").getAsString(), "Zendikar");
        final ConquestData saved = new ConquestData(saveDir(name));
        Assert.assertEquals(saved.getSelectedCommander().getName(), commander);
        Assert.assertEquals(saved.getPlaneswalker().getName(), walker);
    }

    // Fails if renaming the last played conquest leaves the old folder, or the start page's Resume or the shelf's current mark keeps the old name
    @Test(timeOut = 120_000)
    public void renamingTheCurrentConquestKeepsItCurrent() throws Exception {
        final ConquestData data = ConquestFixture.create("Zendikar");
        final String to = ConquestFixture.expected();
        final TestBrowser host = onTheShelf();
        send(host, message("campaignRename", "name", data.getName(), "to", to));
        final JsonObject saves = host.awaitMatching("conquestSaves", s -> to.equals(str(s, "current")), "the shelf does not mark the new name as current");
        Assert.assertTrue(names(saves, "saves").contains(to));
        Assert.assertFalse(names(saves, "saves").contains(data.getName()));
        host.awaitMatching("hello", h -> h.has("campaignResume") && to.equals(str(h.getAsJsonObject("campaignResume"), "conquest")), "the start page was not told the new name");
        Assert.assertFalse(data.getDirectory().exists(), "the old folder is still there");

        // A name that another conquest uses is refused, and nothing is renamed
        final ConquestData other = ConquestFixture.create("Zendikar");
        send(host, message("campaignRename", "name", to, "to", other.getName()));
        final JsonObject refused = host.awaitMatching("error", e -> true, "a name in use was not refused");
        Assert.assertTrue(saveDir(to).isDirectory());
        // The browser clears an error on every hello, so the refusal must be the last message sent
        awaitTrue(() -> host.got.size() >= 3, "the shelf was not sent again with the refusal");
        Assert.assertSame(host.got.get(host.got.size() - 1), refused, "the refusal was followed by a hello, which wipes it from the page");
    }

    // Fails if a deleted conquest is still listed, or its folder is still there
    @Test(timeOut = 120_000)
    public void deletingRemovesTheSave() throws Exception {
        final ConquestData data = ConquestFixture.create("Zendikar");
        final TestBrowser host = onTheShelf();
        Assert.assertTrue(names(host.awaitNewest("conquestSaves"), "saves").contains(data.getName()));
        send(host, message("campaignDelete", "name", data.getName()));
        final JsonObject saves = host.awaitMatching("conquestSaves", s -> true, "the shelf was not sent again");
        Assert.assertFalse(names(saves, "saves").contains(data.getName()));
        Assert.assertFalse(data.getDirectory().exists());
    }

    // Fails if a plane can be unlocked without the emblems or entered while locked, an unlock does not charge exactly once, or going back costs anything
    @Test(timeOut = 120_000)
    public void unlockingSpendsOnce() throws Exception {
        final TestBrowser host = hostInConquest(ConquestFixture.install());
        final ConquestData data = FModel.getConquest().getModel();
        final ConquestPlane alara = FModel.getPlanes().get("Alara");
        final int cost = data.getPlaneUnlockCost();
        final int few = data.getPlaneswalkEmblems();
        Assert.assertTrue(few < cost, "the fixture can already afford a plane");
        send(host, message("conquestPlaneswalk", "plane", "Alara", "unlock", true));
        host.awaitMatching("error", e -> true, "an unlock that cannot be paid for was not refused");
        Assert.assertFalse(data.isPlaneUnlocked(alara));
        Assert.assertEquals(data.getPlaneswalkEmblems(), few);
        Assert.assertEquals(data.getCurrentPlane().getName(), "Zendikar");

        data.rewardPlaneswalkEmblems(cost);
        send(host, message("conquestPlaneswalk", "plane", "Alara", "unlock", false));
        host.awaitMatching("error", e -> true, "a locked plane was entered without unlocking it");
        Assert.assertEquals(data.getCurrentPlane().getName(), "Zendikar");
        Assert.assertEquals(data.getPlaneswalkEmblems(), few + cost);

        send(host, message("conquestPlaneswalk", "plane", "Alara", "unlock", true));
        host.awaitMatching("conquestState", s -> "Alara".equals(s.get("plane").getAsString()), "the map is not the new plane's");
        host.awaitMatching("campaignBar", b -> balance(b, "IMG_PW_BADGE_COMMON") == few, "the bar does not show the emblems spent");
        Assert.assertEquals(data.getPlaneswalkEmblems(), few);
        final ConquestData saved = new ConquestData(data.getDirectory());
        Assert.assertEquals(saved.getCurrentPlane().getName(), "Alara");
        Assert.assertEquals(saved.getPlaneswalkEmblems(), few);
        Assert.assertTrue(saved.isPlaneUnlocked(alara));

        // Going back to an unlocked plane is free, even when the request asks to unlock it
        send(host, message("conquestPlaneswalk", "plane", "Zendikar", "unlock", true));
        host.awaitMatching("conquestState", s -> "Zendikar".equals(s.get("plane").getAsString()), "the player did not go back");
        Assert.assertEquals(data.getPlaneswalkEmblems(), few);
    }

    private static JsonObject aether(final JsonObject shown, final boolean pull) {
        return message("conquestAether", "colors", shown == null ? "" : shown.get("colors").getAsString(), "type", shown == null ? "" : shown.get("type").getAsString(),
                "rarity", shown == null ? "" : shown.get("rarity").getAsString(), "cmc", shown == null ? "" : shown.get("cmc").getAsString(), "pull", pull);
    }

    // Fails if a pull does not cost the price the page showed and add the card it names, or a pull with no shards gives a card
    @Test(timeOut = 120_000)
    public void aPullSpendsAndUnlocks() throws Exception {
        final TestBrowser host = hostInConquest(ConquestFixture.install());
        final ConquestData data = FModel.getConquest().getModel();
        final int shards = data.getAEtherShards();
        final int owned = data.getUnlockedCardCount();
        send(host, aether(null, false));
        final JsonObject shown = host.awaitMatching("conquestAether", a -> true, "the Aether was not sent");
        final int cost = shown.get("cost").getAsInt();
        final int matching = shown.get("matching").getAsInt();
        Assert.assertTrue(cost > 0 && matching > 0, "the fixture's starting filters match nothing");
        Assert.assertEquals(shown.get("type").getAsString(), "CREATURE");
        Assert.assertEquals(shown.getAsJsonArray("rarities").size(), 4);

        send(host, aether(shown, true));
        final JsonObject after = host.awaitMatching("conquestAether", a -> a.has("pulled"), "the pull gave no card");
        final String name = after.getAsJsonObject("pulled").get("name").getAsString();
        Assert.assertEquals(data.getAEtherShards(), shards - cost);
        Assert.assertEquals(data.getUnlockedCardCount(), owned + 1);
        boolean has = false;
        for (final PaperCard card : data.getUnlockedCards()) {
            has |= card.getName().equals(name);
        }
        Assert.assertTrue(has, "the card pulled is not owned");
        Assert.assertEquals(after.get("matching").getAsInt(), matching - 1);
        host.awaitMatching("campaignBar", b -> balance(b, "IMG_AETHER_SHARD") == shards - cost, "the bar does not show the shards spent");

        data.spendAEtherShards(data.getAEtherShards());
        send(host, aether(shown, true));
        final JsonObject refused = host.awaitMatching("conquestAether", a -> true, "the Aether did not answer");
        Assert.assertFalse(refused.has("pulled"), "a pull with no shards gave a card");
        Assert.assertTrue(refused.has("problem"));
        Assert.assertEquals(data.getUnlockedCardCount(), owned + 1);
        Assert.assertEquals(data.getAEtherShards(), 0);
    }

    // Fails if a preference is saved that breaks a rule, is negative, is not a whole number or is not on the page, or a valid one is not saved
    @Test(timeOut = 120_000)
    public void aPreferenceIsCheckedBeforeItIsSaved() throws Exception {
        final forge.gamemodes.planarconquest.ConquestPreferences prefs = FModel.getConquestPreferences();
        final int commons = prefs.getPrefInt(CQPref.BOOSTER_COMMONS);
        final int pull = prefs.getPrefInt(CQPref.AETHER_BASE_PULL_COST);
        final TestBrowser host = hostInConquest(ConquestFixture.create("Zendikar"));
        final String current = prefs.getPref(CQPref.CURRENT_CONQUEST);
        send(host, pref("BOOSTER_COMMONS", 16 - prefs.getPrefInt(CQPref.BOOSTER_UNCOMMONS) - prefs.getPrefInt(CQPref.BOOSTER_RARES)));
        host.awaitMatching("campaignPrefs", p -> p.has("problem"), "a pack of more than 15 cards was not refused");
        Assert.assertEquals(prefs.getPrefInt(CQPref.BOOSTER_COMMONS), commons);
        send(host, pref("AETHER_BASE_PULL_COST", -1));
        host.awaitMatching("campaignPrefs", p -> p.has("problem"), "a negative value was not refused");
        Assert.assertEquals(prefs.getPrefInt(CQPref.AETHER_BASE_PULL_COST), pull);
        send(host, message("campaignPref", "key", "AETHER_BASE_PULL_COST", "value", "1.5"));
        host.awaitMatching("campaignPrefs", p -> p.has("problem"), "a value that is not a whole number was not refused");
        Assert.assertEquals(prefs.getPrefInt(CQPref.AETHER_BASE_PULL_COST), pull);
        send(host, pref("CURRENT_CONQUEST", 1));
        host.awaitMatching("campaignPrefs", p -> true, "the preferences were not sent");
        Assert.assertEquals(prefs.getPref(CQPref.CURRENT_CONQUEST), current, "a preference that is not the page's was written");

        send(host, pref("AETHER_BASE_PULL_COST", pull + 50));
        final JsonObject saved = host.awaitMatching("campaignPrefs", p -> !p.has("problem"), "a good value was refused");
        Assert.assertEquals(saved.getAsJsonArray("rows").size(), 20);
        Assert.assertEquals(new forge.gamemodes.planarconquest.ConquestPreferences().getPrefInt(CQPref.AETHER_BASE_PULL_COST), pull + 50);

        send(host, TestBrowser.action("campaignPrefsReset"));
        host.awaitMatching("campaignPrefs", p -> true, "the reset did not answer");
        Assert.assertEquals(prefs.getPrefInt(CQPref.AETHER_BASE_PULL_COST), Integer.parseInt(CQPref.AETHER_BASE_PULL_COST.getDefault()));
        Assert.assertEquals(prefs.getPref(CQPref.CURRENT_CONQUEST), current, "the reset forgot which conquest is current");
    }

    // Fails if a name containing a path finds a folder outside the saves. A delete would then remove that folder
    @Test
    public void aNameWithAPathInItFindsNothing() {
        final ConquestData data = ConquestFixture.create("Zendikar");
        final String folder = data.getDirectory().getName();
        Assert.assertNotNull(ConquestGame.find(data.getName()));
        Assert.assertNull(ConquestGame.find("../saves/" + folder));
        Assert.assertNull(ConquestGame.find("..\\saves\\" + folder));
        Assert.assertNull(ConquestGame.find(folder + "/."));
    }

    // Fails if a rebalanced card does not use the art of its original card, or a card with its own art gets another card's art
    @Test
    public void aRebalancedCardWearsItsOriginalsArt() {
        final PaperCard rebalanced = FModel.getMagicDb().getCommonCards().getCard("A-Phylath, World Sculptor");
        Assert.assertTrue(rebalanced.isRebalanced());
        final PaperCard original = forge.util.ImageUtil.getPaperCardFromImageKey(ImageFiles.artKey(rebalanced.getImageKey(false)));
        Assert.assertEquals(original.getName(), "Phylath, World Sculptor");
        Assert.assertEquals(original.getEdition(), rebalanced.getEdition());
        Assert.assertEquals(ImageFiles.artKey(original.getImageKey(false)), original.getImageKey(false));
    }

}
