package forge.web.campaign;

import forge.card.CardRarity;
import forge.card.ColorSet;
import forge.deck.Deck;
import forge.game.GameType;
import forge.game.GameView;
import forge.gamemodes.planarconquest.ConquestAether;
import forge.gamemodes.planarconquest.ConquestBattle;
import forge.gamemodes.planarconquest.ConquestChaosBattle;
import forge.gamemodes.planarconquest.ConquestCommander;
import forge.gamemodes.planarconquest.ConquestController;
import forge.gamemodes.planarconquest.ConquestData;
import forge.gamemodes.planarconquest.ConquestEvent;
import forge.gamemodes.planarconquest.ConquestEvent.ChaosWheelOutcome;
import forge.gamemodes.planarconquest.ConquestEvent.ConquestEventRecord;
import forge.gamemodes.planarconquest.ConquestLocation;
import forge.gamemodes.planarconquest.ConquestPlane;
import forge.gamemodes.planarconquest.ConquestPlaneData;
import forge.gamemodes.planarconquest.ConquestPreferences;
import forge.gamemodes.planarconquest.ConquestPreferences.CQPref;
import forge.gamemodes.planarconquest.ConquestRecord;
import forge.gamemodes.planarconquest.ConquestRegion;
import forge.gamemodes.planarconquest.ConquestRewardStep;
import forge.gamemodes.planarconquest.ConquestUtil;
import forge.gamemodes.planarconquest.ConquestUtil.CMCFilter;
import forge.gamemodes.planarconquest.ConquestUtil.RarityFilter;
import forge.gamemodes.planarconquest.ConquestUtil.TypeFilter;
import forge.gamemodes.quest.QuestUtil;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgeConstants;
import forge.model.FModel;
import forge.util.Localizer;
import forge.web.deck.CardCatalog;
import forge.web.deck.DeckCatalog;
import forge.web.deck.DeckFacts;
import forge.web.deck.DeckEditor;
import forge.web.protocol.BrowserChannel;
import forge.web.protocol.CampaignProtocol.*;
import forge.web.protocol.Commands;
import forge.web.protocol.Commands.Handler;
import forge.web.protocol.FromBrowser.*;
import forge.web.protocol.ToBrowser.*;
import org.apache.commons.lang3.EnumUtils;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Planar Conquest as one session plays it: the saves, the map, the party and its decks, the Aether, planeswalking and the battles. The conquest itself is Forge's, with one per process, so this class holds only what the session knows that the save does not. */
public final class ConquestGame implements Campaign {
    private ConquestLocation selection;
    /** The result of the game just ended, while its match is still open. */
    private volatile CampaignResult result;
    /** What the last won battle gave. It is kept until the browser says it has shown it. */
    private volatile Reward reward;
    private volatile boolean chaosOwed;
    /** Dev mode's choice of where the next wheel stops. */
    private volatile ChaosWheelOutcome nextWheel;

    static ConquestData model() {
        return FModel.getConquest().getModel();
    }

    /** Reads every save, which loads the plane each save is on. Do not call this on the socket thread. */
    @Override
    public ConquestSaves saves() {
        final List<ConquestSave> rows = new ArrayList<>();
        for (final ConquestData data : ConquestController.listSaves()) {
            if (!readable(data)) {
                continue;
            }
            final File file = new File(data.getDirectory(), "data.xml");
            rows.add(new ConquestSave(data.getName(), data.getPlaneswalker().getDisplayName(), data.getPlaneswalker().getImageKey(false),
                    planeName(data.getCurrentPlane()), art(data.getCurrentPlane()), data.getCurrentPlaneData().getConqueredCount(),
                    data.getCurrentPlane().getEventCount(), data.getUnlockedCardCount(), data.getAEtherShards(),
                    data.getPlaneswalkEmblems(),
                    file.exists() ? Campaign.savedOn(file) : null));
        }
        return new ConquestSaves(rows, current());
    }

    /** The conquest played last, if its folder is still there. */
    @Override
    public String current() {
        final String name = FModel.getConquestPreferences().getPref(CQPref.CURRENT_CONQUEST);
        return name != null && saveDir(name).isDirectory() ? name : null;
    }

    @Override
    public String open(final String save) {
        final ConquestData data = find(save);
        if (data == null) {
            return null;
        }
        FModel.getConquest().load(data);
        opened();
        return data.getName();
    }

    /** The save with this name, or null when there is none or it cannot be read. */
    public static ConquestData find(final String name) {
        final File dir = name == null ? null : saveDir(name);
        final ConquestData data = dir != null && dir.isDirectory() && Campaign.isSaveNamed(ForgeConstants.CONQUEST_SAVE_DIR, dir, name.replace(' ', '_'))
                ? new ConquestData(dir) : null;
        return data != null && readable(data) ? data : null;
    }

    /** A save whose file could not be read to the end has no planes, or no data at all, so it has no page to show. */
    private static boolean readable(final ConquestData data) {
        return data.getPlaneswalker() != null && data.getCurrentLocation() != null && data.getCurrentPlaneData() != null;
    }

    private static File saveDir(final String name) {
        return new File(ForgeConstants.CONQUEST_SAVE_DIR, name.replace(' ', '_'));
    }

    static ConquestCommander commander(final String name) {
        for (final ConquestCommander c : model().getCommanders()) {
            if (c.getName().equals(name)) {
                return c;
            }
        }
        return null;
    }

    /** What the deck editor builds a commander's deck from: the cards owned and not exiled. */
    static DeckEditor.Collection collection(final ConquestCommander commander) {
        final ConquestData data = model();
        // The commander caches its deck, and the editor saves a copy
        return new DeckEditor.Collection(data.getName(), ConquestUtil::getAvailablePool, data::isNewCard, () -> {
            data.resetNewCards();
            data.saveData();
        }, ConquestUtil::getBasicLandSets, commander::reloadDeck, true);
    }

    /** A plane has no picture of its own, so it uses its first region's picture. */
    private static String art(final ConquestPlane plane) {
        final ConquestRegion.ArtCard picture = plane.getRegions().get(0).getArtCard();
        return picture.card().getImageKey(picture.backFace());
    }

    /** A plane a conquest can be on, found by the name the browser shows, or null. */
    private static ConquestPlane plane(final String name) {
        for (final ConquestPlane plane : FModel.getPlanes()) {
            if (!plane.isUnreachable() && planeName(plane).equals(name)) {
                return plane;
            }
        }
        return null;
    }

    private static PaperCard named(final Iterable<PaperCard> cards, final String name) {
        for (final PaperCard card : cards) {
            if (card.getName().equals(name)) {
                return card;
            }
        }
        return null;
    }

    /** Sorts by name, with the rebalanced cards last. No picture exists for them, so a list should not start with one. */
    private static final Comparator<ConquestCardOption> PICTURED_FIRST = Comparator
            .comparing((ConquestCardOption c) -> c.name().startsWith("A-")).thenComparing(ConquestCardOption::name);

    /** What a new conquest may start with. Finding the planeswalkers reads every card, so do not call this on the socket thread. */
    static ConquestOptions options(final String planeName, final String commanderName) {
        final List<ConquestPlaneOption> planes = new ArrayList<>();
        for (final ConquestPlane p : FModel.getPlanes()) {
            if (!p.isUnreachable()) {
                planes.add(new ConquestPlaneOption(planeName(p), art(p), description(p), p.getEventCount()));
            }
        }
        final ConquestPlane plane = plane(planeName);
        List<ConquestCardOption> commanders = null;
        List<ConquestCardOption> walkers = null;
        PaperCard commander = null;
        if (plane != null) {
            commanders = new ArrayList<>();
            for (final PaperCard card : plane.getCommanders()) {
                String region = null;
                for (final ConquestRegion r : plane.getRegions()) {
                    if (region == null && r.getCardPool().contains(card)) {
                        region = r.getName();
                    }
                }
                commanders.add(new ConquestCardOption(card.getName(), card.getImageKey(false), CardCatalog.wubrg(card.getRules().getColorIdentity()), region));
            }
            commanders.sort(PICTURED_FIRST);
            commander = named(plane.getCommanders(), commanderName);
        }
        if (commander != null) {
            walkers = new ArrayList<>();
            for (final PaperCard card : ConquestUtil.getStartingPlaneswalkerOptions(commander)) {
                walkers.add(new ConquestCardOption(card.getName(), card.getImageKey(false), CardCatalog.wubrg(card.getRules().getColorIdentity()), null));
            }
            walkers.sort(PICTURED_FIRST);
        }
        return new ConquestOptions(planes, commanders, walkers, FModel.getConquestPreferences().getPrefInt(CQPref.AETHER_START_SHARDS),
                plane == null ? null : planeName, commander == null ? null : commanderName);
    }

    /** Starts a conquest and makes it the open one. Returns why it cannot, or null when it is created. */
    static String create(final String name, final String planeName, final String commanderName, final String walkerName) {
        final String cleaned = QuestUtil.cleanString(name == null ? "" : name).trim();
        final String problem = ConquestUtil.nameProblem(cleaned);
        if (problem != null) {
            return problem;
        }
        final ConquestPlane plane = plane(planeName);
        final PaperCard commander = plane == null ? null : named(plane.getCommanders(), commanderName);
        final PaperCard walker = commander == null ? null : named(ConquestUtil.getStartingPlaneswalkerOptions(commander), walkerName);
        if (walker == null) {
            return Localizer.getInstance().getMessage("lblWebConquestCannotStart");
        }
        FModel.getConquest().create(cleaned, plane, walker, commander);
        return null;
    }

    @Override
    public String rename(final String name, final String to) {
        final ConquestData data = find(name);
        final String cleaned = QuestUtil.cleanString(to == null ? "" : to).trim();
        if (data == null || cleaned.equals(data.getName())) {
            return null;
        }
        final String problem = ConquestUtil.nameProblem(cleaned);
        if (problem != null) {
            return problem;
        }
        ConquestController.rename(data, cleaned);
        return null;
    }

    @Override
    public void delete(final String name) {
        final ConquestData data = find(name);
        if (data != null) {
            ConquestController.delete(data);
        }
    }

    static String planeName(final ConquestPlane plane) {
        return plane.getName().replace('_', ' ');
    }

    void opened() {
        selection = null;
        result = null;
        reward = null;
        chaosOwed = false;
        nextWheel = null;
    }

    public void setNextWheel(final ChaosWheelOutcome outcome) {
        nextWheel = outcome;
    }

    @Override
    public CampaignResult result() {
        return result;
    }

    @Override
    public Reward reward() {
        return reward;
    }

    @Override
    public CampaignResult gameOver(final GameView hostGame) {
        final ConquestController controller = FModel.getConquest();
        final ConquestBattle battle = controller.getActiveBattle();
        if (battle == null || hostGame == null) {
            return null;
        }
        final ConquestBattle.Outcome outcome = controller.recordOutcome(hostGame);
        boolean first = false;
        if (outcome == ConquestBattle.Outcome.WON) {
            final ChaosWheelOutcome wheel = nextWheel != null ? nextWheel : ChaosWheelOutcome.random();
            nextWheel = null;
            final List<ConquestRewardStep> steps = controller.claimRewards(battle, wheel);
            first = steps.stream().anyMatch(s -> s.kind() == ConquestRewardStep.Kind.CONQUER_EMBLEMS);
            chaosOwed = steps.stream().anyMatch(s -> s.kind() == ConquestRewardStep.Kind.CHAOS_BATTLE);
            reward = new Reward(steps.stream().map(s -> step(s, battle)).toList());
        }
        final boolean won = outcome == ConquestBattle.Outcome.WON;
        final boolean over = outcome != ConquestBattle.Outcome.UNFINISHED;
        final boolean chaos = battle instanceof ConquestChaosBattle;
        final Localizer text = Localizer.getInstance();
        final List<ResultButton> buttons = new ArrayList<>();
        // A battle ends as on mobile. A win is acknowledged. A lost event can be fought again, but a lost chaos battle cannot
        if (!over) {
            buttons.add(new ResultButton(text.getMessage("btnContinue"), "nextGame", true));
            buttons.add(new ResultButton(text.getMessage("lblQuit"), "quit", false));
        } else if (won || chaos) {
            buttons.add(new ResultButton(text.getMessage(won ? "lblGreat" : "lblOK"), "leave", true));
        } else {
            buttons.add(new ResultButton(text.getMessage("lblRetry"), "restart", true));
            buttons.add(new ResultButton(text.getMessage("lblQuit"), "leave", false));
        }
        final String event = battle.getEventName();
        result = new CampaignResult(won, over,
                !won || chaos ? null : first ? event + " \u00b7 " + text.getMessage("lblWebConquestFirstConquest") : event, buttons);
        return result;
    }

    @Override
    public void left() {
        FModel.getConquest().finishBattle();
        result = null;
    }

    /** When a reward ends in a chaos battle, that battle starts next. The player has no choice. */
    @Override
    public void claim(final Host host) {
        reward = null;
        final boolean owed = chaosOwed;
        chaosOwed = false;
        if (owed) {
            startBattle(new ConquestChaosBattle(), host);
        }
    }

    private static RewardStep step(final ConquestRewardStep s, final ConquestBattle battle) {
        List<PackCard> cards = null;
        String pack = null;
        String art = null;
        if (s.cards() != null) {
            cards = s.cards().stream().map(r -> new PackCard(r.getCard().getName(), r.getCard().getImageKey(false),
                    r.getCard().getRarity().name(), r.getReplacementShards())).toList();
            if (s.chaos()) {
                pack = ((ConquestChaosBattle) battle).getWorldName();
            } else {
                final ConquestPlane plane = model().getCurrentPlane();
                pack = planeName(plane);
                art = art(plane);
            }
        }
        // A booster pays shards for its duplicates, and so does a region's commander when the player owns them all
        final String icon = switch (s.kind()) {
            case SHARDS, BOOSTER, REGION_COMMANDER -> "IMG_AETHER_SHARD";
            case EMBLEMS, CONQUER_EMBLEMS -> "IMG_PW_BADGE_COMMON";
            default -> null;
        };
        final String title = s.kind() == ConquestRewardStep.Kind.REGION_COMMANDER
                ? Localizer.getInstance().getMessage("lblConqueredNReceivedCommander", battle.getLocation().getRegion().getName()).replace("\n", " \u00b7 ") : null;
        return new RewardStep(s.kind().name(), s.amount(), s.outcome() == null ? null : s.outcome().name(), cards,
                s.number(), s.total(), s.chaos(), pack, art, icon, title, null, null, null);
    }

    @Override
    public List<Record> page() {
        return List.of(bar(), state(List.of()));
    }

    CampaignBar bar() {
        final ConquestData data = model();
        final ConquestPlane plane = data.getCurrentPlane();
        final Localizer text = Localizer.getInstance();
        // The engine counts a card as new until the next game ends. A new card that can lead is also a new commander
        final List<String> cards = new ArrayList<>();
        for (final PaperCard card : data.getUnlockedCards()) {
            if (data.isNewCard(card)) {
                cards.add(card.getName());
            }
        }
        final List<String> commanders = new ArrayList<>();
        for (final ConquestCommander commander : data.getCommanders()) {
            if (data.isNewCard(commander.getCard())) {
                commanders.add(commander.getCard().getName());
            }
        }
        return new CampaignBar(data.getName(),
                planeName(plane) + " \u00b7 " + data.getCurrentPlaneData().getConqueredCount() + " / " + plane.getEventCount(),
                List.of(new Balance("IMG_AETHER_SHARD", data.getAEtherShards(), text.getMessage("lblAetherShards")),
                        new Balance("IMG_PW_BADGE_COMMON", data.getPlaneswalkEmblems(), text.getMessage("lblPlaneswalkEmblems"))),
                Map.of("collection", cards, "party", commanders));
    }

    /** The commanders and planeswalkers the conquest has found. */
    ConquestParty party() {
        final ConquestData data = model();
        final ConquestCommander lead = data.getSelectedCommander();
        final List<ConquestCommanderRow> commanders = new ArrayList<>();
        for (final ConquestCommander c : data.getCommanders()) {
            final ConquestRecord record = c.getRecord();
            commanders.add(new ConquestCommanderRow(c.getName(), c.getCard().getImageKey(false),
                    CardCatalog.wubrg(c.getCard().getRules().getColorIdentity()), c.getOrigin(), record.getWins(), record.getLosses(),
                    c.getDeck().getMain().countAll(), c.getDeckProblem(), c == lead));
        }
        final List<ConquestWalkerRow> walkers = new ArrayList<>();
        for (final PaperCard card : data.getSortedPlaneswalkers()) {
            walkers.add(new ConquestWalkerRow(card.getName(), card.getImageKey(false), CardCatalog.wubrg(card.getRules().getColorIdentity()),
                    card.equals(data.getPlaneswalker())));
        }
        return new ConquestParty(commanders, walkers);
    }

    /** Makes an owned planeswalker the one the player travels as. Returns false when the conquest owns none with that name. */
    static boolean setPlaneswalker(final String name) {
        final ConquestData data = model();
        for (final PaperCard card : data.getSortedPlaneswalkers()) {
            if (card.getName().equals(name)) {
                data.setPlaneswalker(card);
                data.saveData();
                return true;
            }
        }
        return false;
    }

    /** A commander's deck, in the form the deck finder's panel shows. */
    static DeckDetails deckDetails(final ConquestCommander commander) {
        final Deck deck = commander.getDeck();
        return new DeckDetails("conquest:" + commander.getName(), commander.getName(), commander.getDeckProblem(),
                CardCatalog.wubrg(commander.getCard().getRules().getColorIdentity()), DeckFacts.stats(deck),
                CardCatalog.groups(deck.getMain(), DeckCatalog.NO_FLAGS), List.of(), null, 0, null);
    }

    /** The cards owned and not exiled, or the exiled cards. */
    private static List<PaperCard> cardsOf(final boolean exile) {
        final ConquestData data = model();
        final List<PaperCard> cards = new ArrayList<>();
        for (final PaperCard card : exile ? data.getExiledCards() : data.getUnlockedCards()) {
            if (exile || !data.isInExile(card)) {
                cards.add(card);
            }
        }
        return cards;
    }

    @Override
    public Trading trading() {
        final ConquestPreferences prefs = FModel.getConquestPreferences();
        final double base = prefs.getPrefInt(CQPref.AETHER_BASE_DUPLICATE_VALUE);
        final List<String> planes = new ArrayList<>();
        for (final ConquestPlane plane : FModel.getPlanes()) {
            if (!plane.isUnreachable()) {
                planes.add(planeName(plane));
            }
        }
        // Mobile's text, joined into one line
        final String note = Localizer.getInstance().getMessage("lblExileRetrieveProportion",
                Math.round(100 * prefs.getPrefInt(CQPref.AETHER_BASE_EXILE_VALUE) / base),
                Math.round(100 * prefs.getPrefInt(CQPref.AETHER_BASE_RETRIEVE_COST) / base)).replace('\n', ' ');
        return new Trading(List.of(new TradeList("collection", cardsOf(false).size()), new TradeList("exile", cardsOf(true).size())), note, planes,
                null, null);
    }

    /** A page of the collection or the exile. Each card shows what exiling or retrieving it is worth. */
    @Override
    public CataloguePage cards(final CatalogueQuery q) {
        final ConquestData data = model();
        final boolean exile = "exile".equals(q.source());
        final List<PaperCard> cards = cardsOf(exile);
        if (q.group() != null) {
            for (final ConquestPlane plane : FModel.getPlanes()) {
                if (planeName(plane).equals(q.group())) {
                    cards.removeIf(c -> !plane.getCardPool().contains(c));
                }
            }
        }
        // A card that cannot be exiled shows why, and is still listed
        final Function<PaperCard, String> problem = exile ? c -> null : c -> data.getExileProblem(List.of(c));
        final CataloguePage page = CardCatalog.of(cards).query(q.request(), new CardCatalog.Query(q.text(), q.colours(), q.type(), q.filters(), q.sort(),
                q.offset(), true), problem, null, name -> 0, c -> new CardCatalog.Extra(data.isNewCard(c) ? Boolean.TRUE : null,
                exile ? data.getRetrieveCost(List.of(c)) : data.getExileValue(List.of(c)), null, null, null, null));
        return page.from(exile ? "exile" : "collection");
    }

    /** Exiles cards from the collection, or brings cards back from the exile. A conquest owns one of each card, so a pick's count is ignored. */
    @Override
    public String trade(final String source, final List<TradePick> picks) {
        final boolean retrieve = "exile".equals(source);
        final ConquestData data = model();
        final List<PaperCard> cards = cardsOf(retrieve);
        cards.removeIf(c -> picks.stream().noneMatch(p -> p.key().equals(c.getImageKey(false))));
        if (cards.isEmpty()) {
            return null;
        }
        final String problem = retrieve ? data.getRetrieveProblem(cards) : data.getExileProblem(cards);
        if (problem != null) {
            return problem;
        }
        if (retrieve) {
            data.retrieve(cards);
        } else {
            data.exile(cards);
        }
        return null;
    }

    /** The Aether with these filters, after a pull if one is asked for. */
    ConquestAetherState aether(final ConquestAetherQuery q) {
        final ConquestData data = model();
        final ConquestAether.Filter start = ConquestAether.Filter.startingFor(data.getSelectedCommander());
        // A query with no type is the first of a visit, so it uses the filters mobile starts with
        final boolean first = q.type() == null || q.type().isEmpty();
        final ConquestAether.Filter filter = first ? start : new ConquestAether.Filter(
                ColorSet.fromNames((q.colors() == null ? "" : q.colors()).toCharArray()),
                EnumUtils.getEnum(TypeFilter.class, q.type(), start.type()),
                EnumUtils.getEnum(RarityFilter.class, q.rarity(), start.rarity()),
                EnumUtils.getEnum(CMCFilter.class, q.cmc(), start.cmc()));
        PaperCard pulled = null;
        String problem = null;
        if (q.pull()) {
            final ConquestAether.Pools before = ConquestAether.pools(data, filter);
            pulled = ConquestAether.pull(data, filter);
            if (pulled == null) {
                problem = Localizer.getInstance().getMessage(before.filtered().isEmpty() || before.strict().isEmpty()
                        ? "lblWebConquestNothingToPull" : "lblWebConquestTooFewShards");
            }
        }
        final ConquestAether.Pools pools = ConquestAether.pools(data, filter);
        // A land of basic rarity counts as a common and a special card as a rare, the same way the pull counts them
        final int[] byRarity = new int[4];
        for (final PaperCard card : pools.filtered()) {
            final CardRarity rarity = card.getRarity();
            byRarity[rarity == CardRarity.MythicRare ? 3 : rarity == CardRarity.Rare || rarity == CardRarity.Special ? 2
                    : rarity == CardRarity.Uncommon ? 1 : 0]++;
        }
        final List<ConquestOption> types = new ArrayList<>();
        for (final TypeFilter t : TypeFilter.values()) {
            types.add(new ConquestOption(t.name(), t.toString(), null));
        }
        final List<ConquestOption> rarities = new ArrayList<>();
        for (final RarityFilter r : RarityFilter.values()) {
            rarities.add(new ConquestOption(r.name(), r.toString(), ConquestUtil.getShardValue(r.getRarity(), CQPref.AETHER_BASE_PULL_COST)));
        }
        final List<ConquestOption> cmcs = new ArrayList<>();
        for (final CMCFilter c : CMCFilter.values()) {
            // The heading already says what the range measures
            cmcs.add(new ConquestOption(c.name(), c.toString().replace("Mana Value ", ""), null));
        }
        return new ConquestAetherState(pools.locked().size(), pools.filtered().size(), pools.strict().size(),
                List.of(byRarity[0], byRarity[1], byRarity[2], byRarity[3]), ConquestAether.cost(pools, filter), types, rarities, cmcs,
                CardCatalog.wubrg(filter.colors()), filter.type().name(), filter.rarity().name(), filter.cmc().name(),
                CardCatalog.wubrg(data.getSelectedCommander().getCard().getRules().getColorIdentity()),
                pulled == null ? null : new PackCard(pulled.getName(), pulled.getImageKey(false), pulled.getRarity().name(), 0),
                problem);
    }

    /** Every plane that can be reached: those unlocked, with what is conquered there, and those still locked. */
    ConquestPlanes planes() {
        final ConquestData data = model();
        final List<ConquestPlaneRow> rows = new ArrayList<>();
        for (final ConquestPlane plane : FModel.getPlanes()) {
            if (plane.isUnreachable()) {
                continue;
            }
            final ConquestPlaneData planeData = data.getPlaneData(plane);
            final List<String> regions = new ArrayList<>();
            plane.getRegions().forEach(r -> regions.add(r.getName()));
            rows.add(new ConquestPlaneRow(planeName(plane), art(plane), description(plane), planeData != null, plane == data.getCurrentPlane(),
                    planeData == null ? 0 : planeData.getConqueredCount(), plane.getEventCount(), regions));
        }
        return new ConquestPlanes(rows, data.getPlaneUnlockCost(), data.getPlaneswalkEmblems());
    }

    private static String description(final ConquestPlane plane) {
        return plane.getDescription() == null ? "" : plane.getDescription().replace("\\n", " ");
    }

    /** Travels to a plane. If unlock is asked for and paid, the plane is unlocked first. Returns why it cannot, or null when the player has arrived. */
    String planeswalk(final String planeName, final boolean unlock) {
        final ConquestData data = model();
        final ConquestPlane plane = plane(planeName);
        if (plane == null || (!data.isPlaneUnlocked(plane) && !unlock)) {
            return Localizer.getInstance().getMessage("lblWebConquestCannotGo");
        }
        if (!data.isPlaneUnlocked(plane)) {
            if (!data.spendPlaneswalkEmblems(data.getPlaneUnlockCost())) {
                return Localizer.getInstance().getMessage("lblWebConquestTooFewEmblems");
            }
            data.unlockPlane(plane);
        }
        data.planeswalkTo(plane);
        data.saveData();
        selection = null;
        return null;
    }

    /** The statistics of a plane the conquest has unlocked, or of them all. */
    @Override
    public CampaignStats stats(final String planeName) {
        final ConquestData data = model();
        final ConquestPlane asked = plane(planeName);
        final ConquestPlane plane = asked != null && data.isPlaneUnlocked(asked) ? asked : null;
        final ConquestData.Stats s = data.getStats(plane);
        final Localizer l = Localizer.getInstance();
        final List<Figure> figures = List.of(
                new Figure(l.getMessage("lblAetherShards"), s.shards(), null),
                new Figure(l.getMessage("lblPlaneswalkEmblems"), s.emblems(), null),
                new Figure(l.getMessage("lblTotalWins"), s.wins(), null),
                new Figure(l.getMessage("lblTotalLosses"), s.losses(), null),
                new Figure(l.getMessage("lblConqueredEvents"), s.conquered(), s.events()),
                new Figure(l.getMessage("lblUnlockedCards"), s.unlockedCards(), s.cards()),
                new Figure(l.getMessage("lblCommanders"), s.commanders(), s.allCommanders()),
                new Figure(l.getMessage("lblPlaneswalkers"), s.planeswalkers(), s.allPlaneswalkers()));
        final List<String> planes = new ArrayList<>();
        for (final ConquestPlane p : FModel.getPlanes()) {
            if (data.isPlaneUnlocked(p)) {
                planes.add(planeName(p));
            }
        }
        final List<StatTable> tables = new ArrayList<>();
        if (plane != null) {
            final List<List<String>> regions = new ArrayList<>();
            final ConquestPlaneData planeData = data.getPlaneData(plane);
            for (int r = 0; r < plane.getRegions().size(); r++) {
                int conquered = 0;
                int wins = 0;
                int losses = 0;
                for (int row = 0; row < plane.getRowsPerRegion(); row++) {
                    for (int col = 0; col < plane.getCols(); col++) {
                        final ConquestEventRecord record = planeData.getEventRecord(r, row, col);
                        if (record != null) {
                            conquered += record.hasConquered() ? 1 : 0;
                            wins += record.getTotalWins();
                            losses += record.getTotalLosses();
                        }
                    }
                }
                regions.add(List.of(plane.getRegions().get(r).getName(), conquered + " / " + plane.getRowsPerRegion() * plane.getCols(),
                        String.valueOf(wins), String.valueOf(losses)));
            }
            tables.add(new StatTable(List.of(l.getMessage("lblRegion"), l.getMessage("lblConqueredEvents"), l.getMessage("lblTotalWins"),
                    l.getMessage("lblTotalLosses")), regions));
        }
        final List<List<String>> commanders = new ArrayList<>();
        for (final ConquestCommander c : data.getCommanders()) {
            commanders.add(List.of(c.getName(), String.valueOf(c.getRecord().getWins()), String.valueOf(c.getRecord().getLosses())));
        }
        commanders.add(List.of(l.getMessage("lblChaosBattles"), String.valueOf(data.getChaosBattleRecord().getWins()),
                String.valueOf(data.getChaosBattleRecord().getLosses())));
        tables.add(new StatTable(List.of(l.getMessage("lblCommanders"), l.getMessage("lblTotalWins"), l.getMessage("lblTotalLosses")), commanders));
        return new CampaignStats(l.getMessage("lblStatistics"), figures, tables, planes, plane == null ? null : planeName(plane),
                l.getMessage("lblAllPlanes"));
    }

    /** A Conquest preference as its page lists it, with mobile's label and group. */
    private record PrefField(CQPref pref, String label, String group) {
    }

    /** Mobile's preferences, in mobile's order. The conquest played last is also a preference, but it is not in this list. */
    private static final List<PrefField> PREFS = List.of(
            new PrefField(CQPref.AETHER_BASE_DUPLICATE_VALUE, "lblBaseDuplicateValue", "lblAetherShards"),
            new PrefField(CQPref.AETHER_BASE_EXILE_VALUE, "lblBaseExileValue", "lblAetherShards"),
            new PrefField(CQPref.AETHER_BASE_RETRIEVE_COST, "lblBaseRetrieveCost", "lblAetherShards"),
            new PrefField(CQPref.AETHER_BASE_PULL_COST, "lblBasePullCost", "lblAetherShards"),
            new PrefField(CQPref.AETHER_UNCOMMON_MULTIPLIER, "lblUncommonMultiplier", "lblAetherShards"),
            new PrefField(CQPref.AETHER_RARE_MULTIPLIER, "lblRareMultiplier", "lblAetherShards"),
            new PrefField(CQPref.AETHER_MYTHIC_MULTIPLIER, "lblMythicMultiplier", "lblAetherShards"),
            new PrefField(CQPref.AETHER_START_SHARDS, "lblStartingShards", "lblAetherShards"),
            new PrefField(CQPref.AETHER_WHEEL_SHARDS, "lblChaosWheelShardValue", "lblAetherShards"),
            new PrefField(CQPref.BOOSTER_COMMONS, "lblCommons", "lblBoosterPacks"),
            new PrefField(CQPref.BOOSTER_UNCOMMONS, "lblUncommons", "lblBoosterPacks"),
            new PrefField(CQPref.BOOSTER_RARES, "lblRares", "lblBoosterPacks"),
            new PrefField(CQPref.BOOSTERS_PER_MYTHIC, "lblBoostersPerMythic", "lblBoosterPacks"),
            new PrefField(CQPref.PLANESWALK_CONQUER_EMBLEMS, "lblBaseConquerReward", "lblPlaneswalkEmblems"),
            new PrefField(CQPref.PLANESWALK_WHEEL_EMBLEMS, "lblChaosWheelBonus", "lblPlaneswalkEmblems"),
            new PrefField(CQPref.PLANESWALK_FIRST_UNLOCK, "lblFirstPlaneUnlockCost", "lblPlaneswalkEmblems"),
            new PrefField(CQPref.PLANESWALK_UNLOCK_INCREASE, "lblCostIncreasePerUnlock", "lblPlaneswalkEmblems"),
            new PrefField(CQPref.CHAOS_BATTLE_WINS_MEDIUMAI, "lblWinsforMediumAI", "lblChaosBattles"),
            new PrefField(CQPref.CHAOS_BATTLE_WINS_HARDAI, "lblWinsforHardAI", "lblChaosBattles"),
            new PrefField(CQPref.CHAOS_BATTLE_WINS_EXPERTAI, "lblWinsforExpertAI", "lblChaosBattles"));

    @Override
    public CampaignPrefs prefs(final String problem) {
        final ConquestPreferences prefs = FModel.getConquestPreferences();
        final Localizer l = Localizer.getInstance();
        final List<PrefRow> rows = new ArrayList<>();
        for (final PrefField f : PREFS) {
            rows.add(new PrefRow(f.pref().name(), l.getMessage(f.label()), l.getMessage(f.group()), prefs.getPref(f.pref()), null));
        }
        return new CampaignPrefs(l.getMessage("lblConquestPreference"), rows, l.getMessage("lblWebConquestPrefsShared"), problem, true);
    }

    /** Every Conquest preference is a whole number. */
    @Override
    public String setPref(final String key, final String text) {
        final int value;
        try {
            value = Integer.parseInt(text.trim());
        } catch (final NumberFormatException e) {
            return Localizer.getInstance().getMessage("lblWebPrefWholeNumber");
        }
        final ConquestPreferences prefs = FModel.getConquestPreferences();
        for (final PrefField f : PREFS) {
            if (f.pref().name().equals(key)) {
                final String problem = value < 0 ? Localizer.getInstance().getMessage("lblWebConquestPrefNegative")
                        : prefs.validatePreference(f.pref(), value);
                if (problem == null) {
                    prefs.setPref(f.pref(), String.valueOf(value));
                    prefs.save();
                }
                return problem;
            }
        }
        return null;
    }

    @Override
    public void resetPrefs() {
        final ConquestPreferences prefs = FModel.getConquestPreferences();
        for (final PrefField f : PREFS) {
            prefs.setPref(f.pref(), f.pref().getDefault());
        }
        prefs.save();
    }

    /** The current map. walked is the move just made, for the marker to animate. It is empty otherwise. */
    ConquestState state(final List<ConquestLocation> walked) {
        final ConquestData data = model();
        final ConquestPlane plane = data.getCurrentPlane();
        final ConquestPlaneData planeData = data.getCurrentPlaneData();
        final ConquestLocation here = data.getCurrentLocation();
        final ConquestLocation chosen = selection != null && selection.getPlane() == plane ? selection : here;
        final List<ConquestRegionRow> regions = new ArrayList<>();
        final List<ConquestCell> cells = new ArrayList<>();
        for (int r = 0; r < plane.getRegions().size(); r++) {
            final ConquestRegion region = plane.getRegions().get(r);
            int conquered = 0;
            for (int row = 0; row < plane.getRowsPerRegion(); row++) {
                for (int col = 0; col < plane.getCols(); col++) {
                    final ConquestLocation loc = new ConquestLocation(plane, r, row, col);
                    final boolean won = planeData.hasConquered(loc);
                    if (won) {
                        conquered++;
                    }
                    cells.add(cell(loc, won, planeData));
                }
            }
            final ConquestRegion.ArtCard art = region.getArtCard();
            regions.add(new ConquestRegionRow(region.getName(), art.card().getImageKey(art.backFace()), CardCatalog.wubrg(region.getColorSet()),
                    conquered, plane.getRowsPerRegion() * plane.getCols()));
        }
        final List<ConquestLocation> toChosen = chosen.equals(here) ? null : route(chosen);
        final ConquestCommander commander = data.getSelectedCommander();
        return new ConquestState(planeName(plane), plane.getRowsPerRegion(), plane.getCols(), regions, cells, place(here),
                data.getPlaneswalker().getDisplayName(), data.getPlaneswalker().getImageKey(false), place(chosen),
                toChosen == null ? 0 : toChosen.size() - 1, walked.stream().map(ConquestGame::place).toList(),
                new ConquestLead(commander.getDisplayName(), commander.getCard().getImageKey(false),
                        commander.getDeck().getMain().countAll(), commander.getDeckProblem()));
    }

    /** A place can be fought when it is the plane's first place, is conquered, or is next to a conquered place. */
    private static boolean reachable(final ConquestLocation loc, final ConquestPlaneData planeData) {
        if (loc.isAt(0, 0, 0) || planeData.hasConquered(loc)) {
            return true;
        }
        for (final ConquestLocation neighbor : loc.getNeighbors()) {
            if (planeData.hasConquered(neighbor)) {
                return true;
            }
        }
        return false;
    }

    private static ConquestCell cell(final ConquestLocation loc, final boolean won, final ConquestPlaneData planeData) {
        if (!reachable(loc, planeData)) {
            return new ConquestCell(loc.getRegionIndex(), loc.getRow(), loc.getCol(), "fog", null, null, null, null, 0, 0, null);
        }
        final ConquestEvent event = loc.getEvent();
        final PaperCard avatar = event.getAvatarCard();
        final ConquestEventRecord record = planeData.getEventRecord(loc);
        return new ConquestCell(loc.getRegionIndex(), loc.getRow(), loc.getCol(), won ? "won" : "open", event.getName(),
                event.getOpponentName(), avatar == null ? null : avatar.getImageKey(false),
                event.getVariants().stream().map(GameType::name).toList(),
                record == null ? 0 : record.getTotalWins(), record == null ? 0 : record.getTotalLosses(),
                event.getTemporaryUnlock() == null ? null : event.getTemporaryUnlock().replace('_', ' '));
    }

    private static ConquestPlace place(final ConquestLocation loc) {
        return new ConquestPlace(loc.getRegionIndex(), loc.getRow(), loc.getCol());
    }

    /** Selects a place the player could walk to. Any other place is ignored. */
    void select(final int region, final int row, final int col) {
        final ConquestData data = model();
        final ConquestPlane plane = data.getCurrentPlane();
        if (region < 0 || region >= plane.getRegions().size() || row < 0 || row >= plane.getRowsPerRegion()
                || col < 0 || col >= plane.getCols()) {
            return;
        }
        final ConquestLocation loc = new ConquestLocation(plane, region, row, col);
        if (loc.equals(data.getCurrentLocation()) || route(loc) != null) {
            selection = loc;
        }
    }

    /** Walks to the selection and returns the path walked, or null when there is nowhere to walk. */
    List<ConquestLocation> move() {
        final ConquestData data = model();
        if (selection == null || selection.getPlane() != data.getCurrentPlane() || selection.equals(data.getCurrentLocation())) {
            return null;
        }
        final List<ConquestLocation> walked = route(selection);
        if (walked != null) {
            data.moveTo(selection);
            selection = null;
        }
        return walked;
    }

    /**
     * The shortest walk from the player's position to a place along the map's lines, or null when there is none. Every place between the ends is conquered.
     * The engine's path may step between two places that are not conquered, and the map draws no line there.
     */
    private List<ConquestLocation> route(final ConquestLocation to) {
        final ConquestData data = model();
        final ConquestPlaneData planeData = data.getCurrentPlaneData();
        // ConquestLocation has no hashCode, so the search keys places by their position
        final Map<ConquestPlace, ConquestLocation> cameFrom = new HashMap<>();
        final ArrayDeque<ConquestLocation> queue = new ArrayDeque<>();
        cameFrom.put(place(data.getCurrentLocation()), data.getCurrentLocation());
        queue.add(data.getCurrentLocation());
        while (!queue.isEmpty()) {
            final ConquestLocation at = queue.poll();
            if (at.equals(to)) {
                final List<ConquestLocation> path = new ArrayList<>(List.of(at));
                for (ConquestLocation step = at; !step.equals(data.getCurrentLocation()); ) {
                    step = cameFrom.get(place(step));
                    path.add(step);
                }
                Collections.reverse(path);
                return path;
            }
            for (final ConquestLocation next : at.getNeighbors()) {
                final boolean tied = planeData.hasConquered(at) || planeData.hasConquered(next);
                if (tied && (planeData.hasConquered(next) || next.equals(to)) && cameFrom.putIfAbsent(place(next), at) == null) {
                    queue.add(next);
                }
            }
        }
        return null;
    }

    /** Why a battle cannot start at the player's position, or null if it can. */
    String battleProblem() {
        return model().getSelectedCommander().getDeckProblem();
    }

    ConquestBattle battle() {
        final ConquestLocation loc = model().getCurrentLocation();
        return loc.getEvent().createBattle(loc, 0);
    }

    private static void startBattle(final ConquestBattle battle, final Host host) {
        host.startMatch(() -> FModel.getConquest().prepareBattle(battle, null), () -> FModel.getConquest().cancelBattle());
    }

    /** A command to this conquest. save is the open save, or null when the command comes from the list of saves. */
    private record Call(ConquestGame game, String save, Host host) {
    }

    private static <T extends Record> Handler<Call, T> onShelf(final Handler<Call, T> handler) {
        return (c, ch, command) -> {
            if (c.save() == null) {
                handler.run(c, ch, command);
            }
        };
    }

    private static <T extends Record> Handler<Call, T> inSave(final Handler<Call, T> handler) {
        return (c, ch, command) -> {
            if (c.save() != null) {
                handler.run(c, ch, command);
            }
        };
    }

    public static final Commands<Call> COMMANDS = new Commands<Call>()
            // The new-conquest form is the only part of the saves list that belongs to Conquest
            .on(ConquestOptionsQuery.class, onShelf((c, ch, q) -> ch.send(options(q.plane(), q.commander()))))
            .on(ConquestCreate.class, onShelf((c, ch, create) -> {
                final String problem = create(create.name(), create.plane(), create.commander(), create.planeswalker());
                if (problem != null) {
                    ch.send(c.host().error(problem));
                    return;
                }
                c.game().opened();
                c.host().opened(model().getName());
            }))
            .on(ConquestSelect.class, inSave((c, ch, at) -> {
                c.game().select(at.region(), at.row(), at.col());
                ch.send(c.game().state(List.of()));
            }))
            .on(CampaignPlain.conquestMove, inSave((c, ch, m) -> {
                // A move with nowhere to go gets no answer. A map with no path would end a walk in progress
                final List<ConquestLocation> walked = c.game().move();
                if (walked != null) {
                    ch.send(c.game().state(walked));
                }
            }))
            .on(CampaignPlain.conquestBattle, inSave((c, ch, b) -> {
                // A reward still to be shown may end in a chaos battle, which must come before any other battle
                if (c.game().reward != null) {
                    return;
                }
                final String problem = c.game().battleProblem();
                if (problem != null) {
                    ch.send(c.host().error(problem));
                    return;
                }
                startBattle(c.game().battle(), c.host());
            }))
            .on(CampaignPlain.conquestParty, inSave((c, ch, p) -> ch.send(c.game().party())))
            .on(ConquestAetherQuery.class, inSave((c, ch, q) -> {
                ch.send(c.game().aether(q));
                if (q.pull()) {
                    ch.send(c.game().bar());
                }
            }))
            .on(CampaignPlain.conquestPlanes, inSave((c, ch, p) -> ch.send(c.game().planes())))
            .on(ConquestPlaneswalk.class, inSave((c, ch, go) -> {
                final String problem = c.game().planeswalk(go.plane(), go.unlock());
                if (problem != null) {
                    ch.send(c.host().error(problem));
                    return;
                }
                ch.send(c.game().bar());
                ch.send(c.game().state(List.of()));
                ch.send(c.game().planes());
            }))
            .on(ConquestWalker.class, inSave((c, ch, w) -> {
                if (setPlaneswalker(w.planeswalker())) {
                    ch.send(c.game().party());
                    ch.send(c.game().state(List.of()));
                }
            }))
            .on(ConquestCommanderCommand.class, inSave((c, ch, command) -> {
                final ConquestCommander commander = commander(command.commander());
                if (commander == null) {
                    return;
                }
                switch (command.t()) {
                    case conquestLead -> {
                        model().setSelectedCommander(commander);
                        model().saveData();
                        ch.send(c.game().party());
                        ch.send(c.game().state(List.of()));
                    }
                    case conquestViewDeck -> ch.send(new DeckDetailsMessage(deckDetails(commander)));
                    case conquestEditDeck -> c.host().decks().openCollectionDeck(commander.getDeck(), FModel.getConquest().getDecks(),
                            GameType.PlanarConquest, collection(commander), true, ch);
                }
            }));

    @Override
    public void handle(final BrowserChannel channel, final Record command, final String save, final Host host) {
        COMMANDS.run(new Call(this, save, host), channel, command);
    }
}
