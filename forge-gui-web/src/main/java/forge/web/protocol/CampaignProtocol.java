package forge.web.protocol;

import forge.web.protocol.ToBrowser.CatalogueRow;
import forge.web.protocol.MessageJson.Command;
import forge.web.protocol.MessageJson.Message;
import forge.web.protocol.MessageJson.Nullable;

import java.util.List;
import java.util.Map;

/** The campaign modes' messages in both directions. They are kept apart from the other messages so a build can leave the campaigns out. */
public final class CampaignProtocol {
    private CampaignProtocol() {
    }

    /** Campaign commands that are only their name. */
    public enum CampaignPlain { campaignLeave, conquestMove, conquestBattle, rewardClaim, conquestParty, trading, conquestPlanes, campaignPrefs, campaignPrefsReset }

    @Command
    public record CampaignBare(CampaignPlain t) {
    }

    /** Opens a campaign mode's saved games. With resume, it opens the save played last directly. */
    @Command("campaignOpen")
    public record CampaignOpen(String mode, boolean resume) {
    }

    @Command("campaignLoad")
    public record CampaignLoad(String name) {
    }

    @Command("campaignRename")
    public record CampaignRename(String name, String to) {
    }

    @Command("campaignDelete")
    public record CampaignDelete(String name) {
    }

    /** Asks for a campaign's statistics. scope narrows them to one scope the mode offers. Null asks for all of them. */
    @Command("campaignStats")
    public record CampaignStatsQuery(@Nullable String scope) {
    }

    /** Sets one of a mode's preferences. key is the key its row was sent with. */
    @Command("campaignPref")
    public record CampaignPref(String key, String value) {
    }

    /** A card picked from one of a campaign's lists. key is its image key, which names a printing. */
    public record TradePick(String key, int count) {
    }

    /** Trades the picks out of a list. source is the list's name, the same name a catalogue query uses. */
    @Command("trade")
    public record Trade(String source, List<TradePick> picks) {
    }

    /** Selects a place on the map. The player does not move. */
    @Command("conquestSelect")
    public record ConquestSelect(int region, int row, int col) {
    }

    /** The Aether's filters. An empty type asks for the starting filters of a visit. pull spends the shards to take a card. */
    @Command("conquestAether")
    public record ConquestAetherQuery(String colors, String type, String rarity, String cmc, boolean pull) {
    }

    /** Travels to an unlocked plane. With unlock, it first spends the emblems to unlock the plane. */
    @Command("conquestPlaneswalk")
    public record ConquestPlaneswalk(String plane, boolean unlock) {
    }

    /** Asks what a new conquest may start with. The answer lists the planes, the plane's commanders if a plane is given, and the planeswalkers if a commander is given. */
    @Command("conquestOptions")
    public record ConquestOptionsQuery(@Nullable String plane, @Nullable String commander) {
    }

    @Command("conquestCreate")
    public record ConquestCreate(String name, String plane, String commander, String planeswalker) {
    }

    public enum CommanderAction { conquestLead, conquestViewDeck, conquestEditDeck }

    /** An action on a commander: make it the battle leader, ask for its deck, or open its deck in the editor. */
    @Command
    public record ConquestCommanderCommand(CommanderAction t, String commander) {
    }

    /** Makes an owned planeswalker the one the player travels as. */
    @Command("conquestWalker")
    public record ConquestWalker(String planeswalker) {
    }

    /** Dev mode: the outcome the next wheel stops on. It is a ChaosWheelOutcome name, or empty for a random outcome. */
    @Command("devConquestWheel")
    public record DevConquestWheel(String outcome) {
    }

    /** Chooses a quest pet for a slot. The plant uses slot 0. A null name chooses no pet. */
    @Command("questPet")
    public record QuestPet(int slot, @Nullable String name) {
    }

    @Command("questMatchLength")
    public record QuestMatchLength(int games) {
    }

    public enum QuestDeckAction { questDeckCurrent, questDeckView, questDeckEdit, questDeckDelete }

    /** An action on a quest deck, by name: make it the deck duels use, ask for its list, open it in the editor, or delete it. */
    @Command
    public record QuestDeckCommand(QuestDeckAction t, String deck) {
    }

    /** Makes an empty quest deck with the given name and opens it in the editor. */
    @Command("questDeckNew")
    public record QuestDeckNew(String name) {
    }

    @Command("questDeckRename")
    public record QuestDeckRename(String deck, String to) {
    }

    @Command("questOptions")
    public record QuestOptionsQuery() {
    }

    /** A new quest, as in NewQuestRules.Choices. Enums are sent by name, and the distribution's colours as WUBRGC letters. */
    @Command("questCreate")
    public record QuestCreate(String name, int difficulty, boolean fantasy, boolean commander, String world, String pool, @Nullable String format,
            @Nullable String precon, @Nullable String savedDeck, String poolType, String colors, boolean artifacts, boolean completeSet,
            boolean duplicates, int boosters, @Nullable String prizes, @Nullable String prizeFormat, boolean allowUnlocks) {
    }

    /** Opens a stall of the bazaar. With no name, it opens the first stall. */
    @Command("questStall")
    public record QuestStall(@Nullable String name) {
    }

    @Command("questBuy")
    public record QuestBuy(String stall, String item) {
    }

    @Command("questChallenge")
    public record QuestChallenge(String id) {
    }

    /** Flies the zeppelin. The challenges are drawn again. This is allowed once between two matches. */
    @Command("questZeppelin")
    public record QuestZeppelin() {
    }

    /** Enters a tournament on offer, by its title. This pays its fee and opens its draft. */
    @Command("questEnter")
    public record QuestEnter(String title) {
    }

    /** Spends a draft token on a tournament of a format the host asks for. */
    @Command("questToken")
    public record QuestToken() {
    }

    /** Starts the entered tournament once its deck is built. */
    @Command("questTournamentStart")
    public record QuestTournamentStart() {
    }

    /** Plays the player's next tournament match. The AI matches before it are decided first. */
    @Command("questTournamentNext")
    public record QuestTournamentNext() {
    }

    /** Opens the tournament's deck in the editor. */
    @Command("questTournamentDeck")
    public record QuestTournamentDeck() {
    }

    /** Leaves the entered tournament. If it has started, its prizes are collected. */
    @Command("questTournamentLeave")
    public record QuestTournamentLeave() {
    }

    /** Travels to another world. The host asks which one. */
    @Command("questTravel")
    public record QuestTravel() {
    }

    /** Unlocks a set for the quest's format. The host asks which one. */
    @Command("questUnlock")
    public record QuestUnlock() {
    }

    /** Fights a duel from the Duels page, by its index in the list sent to that page. */
    @Command("questDuel")
    public record QuestDuel(int index) {
    }

    /** One of a campaign's currencies: its skin icon's name, the amount the player has, and a text name for a reader who cannot see the icon. */
    public record Balance(String icon, int amount, String label) {
    }

    /** What the bar above every campaign page shows: the save's name, a line under it, and its balances. fresh names the new items on each tab, by tab. */
    @Message("campaignBar")
    public record CampaignBar(String name, String line, List<Balance> balances, Map<String, List<String>> fresh) {
    }

    /** A button of the result screen. action is nextGame, leave, restart, quit or forfeit. quit leaves a match that is not over. forfeit is a quit the player must confirm first. */
    public record ResultButton(String label, String action, boolean primary) {
    }

    /** A game of a campaign's match ended. line is the text under the result screen's heading, if the mode has any. */
    @Message("campaignResult")
    public record CampaignResult(boolean won, boolean matchOver, @Nullable String line, List<ResultButton> buttons) {
    }

    /** A statistics figure: its amount, and the total it is out of when it is a share. */
    public record Figure(String label, int amount, @Nullable Integer of) {
    }

    public record StatTable(List<String> headers, List<List<String>> rows) {
    }

    /** scopes lists what the figures can be narrowed to. scope is the one they are for, or null for all. allScopes is the label for all. */
    @Message("campaignStats")
    public record CampaignStats(String title, List<Figure> figures, List<StatTable> tables, List<String> scopes, @Nullable String scope,
            @Nullable String allScopes) {
    }

    /** A preference. column, when given, says which of several values in one row this is, as with a difficulty's own value. */
    public record PrefRow(String key, String label, String group, String value, @Nullable String column) {
    }

    /** A mode's preferences. Each is saved when it changes. note says whose they are. problem says why the last change was refused. */
    @Message("campaignPrefs")
    public record CampaignPrefs(String title, List<PrefRow> rows, String note, @Nullable String problem, boolean canReset) {
    }

    /** One of a campaign's lists of cards: the source used to ask for its cards, and how many cards it holds. */
    public record TradeList(String source, int count) {
    }

    /** The lists a campaign trades between, a line about what a trade pays, and any other ways the lists can be narrowed. */
    @Message("trading")
    public record Trading(List<TradeList> lists, String note, @Nullable List<String> groups, @Nullable List<Product> products,
            @Nullable List<CatalogueRow> extras) {
    }

    /** Something for sale that is not a card, such as a booster, a box or a deck. Its key is "p:" plus its name. A trade's pick uses that key. */
    public record Product(String key, String kind, String name, String image, int price) {
    }

    /** The saved conquests, and the one played last. */
    @Message("conquestSaves")
    public record ConquestSaves(List<ConquestSave> saves, @Nullable String current) {
    }

    /** conquered and total count the events of the save's plane. saved is the day its file last changed, as yyyy-MM-dd. */
    public record ConquestSave(String name, String planeswalker, String walkerImage, String plane, String art, int conquered, int total, int cards,
            int shards, int emblems, @Nullable String saved) {
    }

    public record ConquestPlaneOption(String name, String art, String description, int events) {
    }

    /** A commander or a planeswalker a new conquest may start with. region is the commander's own. */
    public record ConquestCardOption(String name, String image, String colors, @Nullable String region) {
    }

    /** plane and commander are the ones asked about, so the browser can tell this answer from an older one. */
    @Message("conquestOptions")
    public record ConquestOptions(List<ConquestPlaneOption> planes, @Nullable List<ConquestCardOption> commanders,
            @Nullable List<ConquestCardOption> planeswalkers, int startShards, @Nullable String plane, @Nullable String commander) {
    }

    public record ConquestPlace(int region, int row, int col) {
    }

    /** A region of the plane. art is the image key of its art card. */
    public record ConquestRegionRow(String name, String art, String colors, int conquered, int total) {
    }

    /** state is won, open (it can be fought) or fog. A fogged cell has no other fields set. */
    public record ConquestCell(int region, int row, int col, String state, @Nullable String name, @Nullable String opponent,
            @Nullable String avatar, @Nullable List<String> variants, int wins, int losses, @Nullable String opens) {
    }

    /** The selected commander. problem says why its deck cannot be played, if it cannot. */
    public record ConquestLead(String name, String image, int deckSize, @Nullable String problem) {
    }

    /** steps is the distance from the player's position to the selection. path is empty in every map except the one that answers a move. */
    @Message("conquestState")
    public record ConquestState(String plane, int rows, int cols, List<ConquestRegionRow> regions, List<ConquestCell> cells,
            ConquestPlace at, String walker, String walkerImage, ConquestPlace selected, int steps, List<ConquestPlace> path,
            ConquestLead commander) {
    }

    /** A commander as the Commanders page lists it. problem is why its deck cannot be played. */
    public record ConquestCommanderRow(String name, String image, String colors, String origin, int wins, int losses, int deckSize,
            @Nullable String problem, boolean selected) {
    }

    public record ConquestWalkerRow(String name, String image, String colors, boolean selected) {
    }

    @Message("conquestParty")
    public record ConquestParty(List<ConquestCommanderRow> commanders, List<ConquestWalkerRow> planeswalkers) {
    }

    /** An option of one of the Aether's filters. cost is what a pull at that rarity costs. */
    public record ConquestOption(String key, String label, @Nullable Integer cost) {
    }

    /** strict counts the cards of exactly the rarity asked for. Nothing can be pulled when it is 0. cost is 0 when nothing matches. */
    @Message("conquestAether")
    public record ConquestAetherState(int locked, int matching, int strict, List<Integer> byRarity, int cost, List<ConquestOption> types,
            List<ConquestOption> rarities, List<ConquestOption> cmcs, String colors, String type, String rarity, String cmc,
            String commanderColors, @Nullable PackCard pulled, @Nullable String problem) {
    }

    public record ConquestPlaneRow(String name, String art, String description, boolean unlocked, boolean current, int conquered,
            int events, List<String> regions) {
    }

    /** Every plane that can be reached, the cost of the next unlock, and the emblems held. */
    @Message("conquestPlanes")
    public record ConquestPlanes(List<ConquestPlaneRow> planes, int unlockCost, int emblems) {
    }

    /** A card from a booster. shards is what a duplicate was turned into, or 0 for a new card. */
    public record PackCard(String name, String image, String rarity, int shards) {
    }

    /** One step a reward shows. kind is the mode's name for it, and icon is the balance it pays into. MESSAGE and CARDS steps carry their own text. outcome "lost" marks cards lost to ante.
     *  packImage is a BOOSTER step's booster picture, for a pack from one set. */
    public record RewardStep(String kind, int amount, @Nullable String outcome, @Nullable List<PackCard> cards,
            int number, int total, boolean chaos, @Nullable String pack, @Nullable String art, @Nullable String icon,
            @Nullable String title, @Nullable String text, @Nullable List<String> lines, @Nullable String packImage) {
    }

    /** What a match gave. It is already saved, and the browser shows it step by step. */
    @Message("reward")
    public record Reward(List<RewardStep> steps) {
    }

    /** A saved quest: its mode, difficulty and rank, its record, cards and credits, its world, and when it was saved. */
    public record QuestSave(String name, String mode, String difficulty, String rank, int wins, int losses, int cards, long credits,
            String world, @Nullable String saved) {
    }

    /** The saved quests, and the one played last. */
    @Message("questSaves")
    public record QuestSaves(List<QuestSave> saves, @Nullable String current) {
    }

    /** A duel on offer: its index in the list, which the browser sends back, and a card from the opponent's deck to show as its picture. */
    public record QuestDuelRow(int index, String title, int difficulty, String description, String colors, @Nullable String face, boolean random) {
    }

    /** A pet slot's choice: the owned pets by name, and the one chosen, or null for none. */
    public record QuestPetChoice(int slot, List<String> pets, @Nullable String chosen) {
    }

    /** The Duels page: the duels, the player's record, the line about the next challenge, the pets and the match lengths the charms allow. */
    @Message("questDuels")
    public record QuestDuels(List<QuestDuelRow> duels, int wins, int losses, int streak, int bestStreak, @Nullable String nextChallenge,
            List<QuestPetChoice> pets, List<Integer> matchLengths, int matchLength, String deck, @Nullable String deckProblem, boolean canUnlock) {
    }

    /** A quest deck: its picture, colours and size, why it cannot be played if it cannot, and whether duels use it. */
    public record QuestDeckRow(String name, @Nullable String image, String colors, int size, int sideboard, @Nullable String problem, boolean current) {
    }

    @Message("questDecks")
    public record QuestDecks(List<QuestDeckRow> decks) {
    }

    /** A challenge on offer: its terms as set in its file, the cards each side starts with in play, and whether the challenge supplies the player's deck. */
    public record QuestChallengeRow(String id, String title, int difficulty, String description, @Nullable String face, int aiLife, @Nullable Integer humanLife,
            int credits, List<String> cardReward, boolean repeatable, List<String> humanCards, List<String> aiCards, boolean fixedDeck) {
    }

    /** The Challenges page: the challenges, how many are open out of the number the wins allow, the line about the next one, and the zeppelin. */
    @Message("questChallenges")
    public record QuestChallenges(List<QuestChallengeRow> challenges, int open, int max, @Nullable String nextIn, boolean zeppelin, boolean zeppelinUsed) {
    }

    /** A bazaar stall: its name, which the browser sends back, the merchant's name, a line of text about it, and its skin icon. */
    public record QuestStallRow(String name, String displayName, String fluff, String icon) {
    }

    /** Something for sale at a stall: a skin icon or a card to show it, its price, its level and highest level, and for a pet, what it does now and what the next level gives. */
    public record QuestItemRow(String name, String description, @Nullable String icon, @Nullable String card, int price, int level, int maxLevel,
            @Nullable String now, @Nullable String next) {
    }

    /** The bazaar: its stalls, the one open, and what that stall sells. */
    @Message("questBazaar")
    public record QuestBazaar(List<QuestStallRow> stalls, @Nullable String stall, List<QuestItemRow> items) {
    }

    /** A tournament on offer: its title, which the browser sends back, the packs drafted by set name with a picture of each, and its fee. */
    public record QuestTournamentRow(String title, List<String> packs, List<String> packImages, int fee, boolean affordable) {
    }

    /** A place in the bracket. name is who holds it: null while undecided, empty for the player. you is true for the player. state is won (went on to the next round), out (lost there) or open (still to be played). */
    public record QuestSeat(@Nullable String name, boolean you, String state) {
    }

    /** The entered tournament. rounds go from the first eight seats to the winner. next is the player's next opponent while the player has a match left, or empty when not yet known. placing is the player's placing. drafted says whether its draft was finished into a pool. */
    public record QuestBracket(String title, List<List<QuestSeat>> rounds, @Nullable String next, String placing, boolean started, boolean drafted) {
    }

    /** The Tournaments page: the tournaments on offer while none is entered, the tokens to make one, the past placings from first to fourth, and the entered one. */
    @Message("questTournaments")
    public record QuestTournaments(List<QuestTournamentRow> offered, int tokens, List<Integer> placings, @Nullable QuestBracket bracket) {
    }

    /** A difficulty and what a quest started at it gets: credits, and the starting pool's commons, uncommons and rares. */
    public record QuestDifficultyRow(String name, int credits, int commons, int uncommons, int rares) {
    }

    /** What the new-quest form offers, each list by name. */
    @Message("questOptions")
    public record QuestOptions(List<String> worlds, List<String> formatWorlds, List<String> sanctioned, List<String> casual, List<String> precons,
            List<String> sealedDecks, List<String> draftDecks, List<String> cubes, List<QuestDifficultyRow> difficulties) {
    }
}
