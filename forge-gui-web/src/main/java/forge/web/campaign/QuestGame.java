package forge.web.campaign;

import com.google.common.primitives.Doubles;
import com.google.common.primitives.Ints;
import forge.card.CardType;
import forge.card.MagicColor;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckGroup;
import forge.deck.DeckSection;
import forge.game.GameType;
import forge.game.GameView;
import forge.gamemodes.match.PreparedMatch;
import forge.gamemodes.quest.NewQuestRules;
import forge.gamemodes.quest.QuestController;
import forge.gamemodes.quest.QuestDraftUtils;
import forge.gamemodes.quest.QuestEvent;
import forge.gamemodes.quest.QuestEventChallenge;
import forge.gamemodes.quest.QuestEventDraft;
import forge.gamemodes.quest.QuestEventDuel;
import forge.gamemodes.quest.QuestMode;
import forge.gamemodes.quest.QuestUtil;
import forge.gamemodes.quest.QuestWinLoseController;
import forge.gamemodes.quest.StartingPoolPreferences;
import forge.gamemodes.quest.StartingPoolPreferences.PoolType;
import forge.gamemodes.quest.StartingPoolType;
import forge.gamemodes.quest.bazaar.IQuestBazaarItem;
import forge.gamemodes.quest.bazaar.QuestItemBasic;
import forge.gamemodes.quest.bazaar.QuestItemType;
import forge.gamemodes.quest.bazaar.QuestPetController;
import forge.gamemodes.quest.data.DeckConstructionRules;
import forge.gamemodes.quest.data.QuestAchievements;
import forge.gamemodes.quest.data.QuestAssets;
import forge.gamemodes.quest.data.QuestData;
import forge.gamemodes.quest.data.QuestPreferences;
import forge.gamemodes.quest.data.QuestPreferences.DifficultyPrefs;
import forge.gamemodes.quest.data.QuestPreferences.QPref;
import forge.gamemodes.quest.io.QuestDataIO;
import forge.gui.util.SOptionPane;
import forge.item.InventoryItem;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgeConstants;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.util.IHasForgeLog;
import forge.util.Localizer;
import forge.util.storage.IStorage;
import forge.web.WebGuiBase;
import forge.web.deck.CardCatalog;
import forge.web.deck.DeckCatalog;
import forge.web.deck.DeckFacts;
import forge.web.deck.DeckEditor;
import forge.web.deck.DeckStore;
import forge.web.protocol.BrowserChannel;
import forge.web.protocol.CampaignProtocol.*;
import forge.web.protocol.Commands;
import forge.web.protocol.Commands.Handler;
import forge.web.protocol.FromBrowser.*;
import forge.web.protocol.ToBrowser.*;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Quest mode as one session plays it: the saves, the duels and challenges, the decks, the tournaments, travel and the shops. The quest itself is Forge's, with one per process, so this class holds only what the session knows that the save does not. */
public final class QuestGame implements Campaign, IHasForgeLog {
    private static final String[] DIFFICULTIES = {"questDifficultyEasy", "questDifficultyMedium", "questDifficultyHard", "questDifficultyExpert"};
    private List<QuestEventDuel> duels;
    /** The match being played. Its view keeps every game's steps. The latest game's controller records the match. */
    private volatile WebQuestView view;
    private volatile QuestWinLoseController controller;
    /** The result of the game just ended, while its match is still open. */
    private volatile CampaignResult result;
    /** What the last match gave. It is kept until the browser says it has shown it. */
    private volatile Reward reward;
    /** The AI opponent of the player's tournament match, while one is being played. */
    private volatile String tournamentOpponent;
    /** Whether that match has been decided. If so, leaving it is not a forfeit. */
    private volatile boolean tournamentDecided;

    static QuestController quest() {
        return FModel.getQuest();
    }

    @Override
    public QuestSaves saves() {
        final List<QuestSave> rows = new ArrayList<>();
        final File[] files = new File(ForgeConstants.QUEST_SAVE_DIR).listFiles((dir, name) -> name.endsWith(".dat"));
        for (final File file : files == null ? new File[0] : files) {
            final QuestData data = read(file);
            if (data != null) {
                rows.add(row(data, file));
            }
        }
        rows.sort(Comparator.comparing(QuestSave::name, String.CASE_INSENSITIVE_ORDER));
        return new QuestSaves(rows, current());
    }

    private static QuestData read(final File file) {
        try {
            return QuestDataIO.loadData(file);
        } catch (final IOException | RuntimeException e) {
            netLog.warn("Could not read the quest {}: {}", file, e.getMessage());
            return null;
        }
    }

    private static QuestSave row(final QuestData data, final File file) {
        final int difficulty = data.getAchievements().getDifficulty();
        return new QuestSave(data.getName(), data.getMode().toString(),
                difficulty >= 0 && difficulty < DIFFICULTIES.length ? Localizer.getInstance().getMessage(DIFFICULTIES[difficulty]) : "",
                quest().getRank(data.getAchievements().getLevel()), data.getAchievements().getWin(), data.getAchievements().getLost(),
                data.getAssets().getCardPool().countAll(), data.getAssets().getCredits(), world(data.getWorldId()),
                Campaign.savedOn(file));
    }

    private static String world(final String id) {
        return id == null ? Localizer.getInstance().getMessage("lblNone") : id;
    }

    /** The quest played last, if its file is still there. */
    @Override
    public String current() {
        final String file = FModel.getQuestPreferences().getPref(QPref.CURRENT_QUEST);
        if (file == null || !file.endsWith(".dat")) {
            return null;
        }
        final String name = file.substring(0, file.length() - ".dat".length());
        return find(name) != null ? name : null;
    }

    @Override
    public String open(final String save) {
        final File file = find(save);
        final QuestData data = file == null ? null : read(file);
        if (data == null) {
            return null;
        }
        quest().load(data);
        duels = null;
        // A reward not yet shown is already saved, and it belongs to the quest that was open before
        reward = null;
        // Desktop's quest chooser does this too, so desktop and mobile open the same quest next
        final QuestPreferences prefs = FModel.getQuestPreferences();
        prefs.setPref(QPref.CURRENT_QUEST, file.getName());
        prefs.save();
        return data.getName();
    }

    public static File find(final String name) {
        if (name == null) {
            return null;
        }
        final File file = new File(ForgeConstants.QUEST_SAVE_DIR, name + ".dat");
        return file.isFile() && Campaign.isSaveNamed(ForgeConstants.QUEST_SAVE_DIR, file, name + ".dat") ? file : null;
    }

    /** Renames a save and its backup. If Forge has this quest open, it is loaded again under the new name. Otherwise its next save would create the old file again. */
    @Override
    public String rename(final String name, final String to) {
        final File file = find(name);
        final String clean = NewQuestRules.cleanName(to);
        if (file == null) {
            return null;
        }
        if (clean.isEmpty()) {
            return Localizer.getInstance().getMessage("lblQuestNameEmpty");
        }
        if (new File(ForgeConstants.QUEST_SAVE_DIR, clean + ".dat").exists()) {
            return Localizer.getInstance().getMessage("lblQuestExists");
        }
        final QuestData data = read(file);
        if (data == null) {
            return null;
        }
        final boolean open = name.equals(quest().getName());
        data.rename(clean);
        if (open) {
            quest().load(data);
        }
        // Desktop and mobile leave the preference pointing at the old file name, which no longer exists
        final QuestPreferences prefs = FModel.getQuestPreferences();
        if (file.getName().equals(prefs.getPref(QPref.CURRENT_QUEST))) {
            prefs.setPref(QPref.CURRENT_QUEST, clean + ".dat");
            prefs.save();
        }
        return null;
    }

    @Override
    public void delete(final String name) {
        final File file = find(name);
        if (file == null) {
            return;
        }
        if (name.equals(quest().getName())) {
            quest().load(null);
        }
        file.delete();
        new File(file.getPath() + ".bak").delete();
        final QuestPreferences prefs = FModel.getQuestPreferences();
        if (file.getName().equals(prefs.getPref(QPref.CURRENT_QUEST))) {
            prefs.setPref(QPref.CURRENT_QUEST, QPref.CURRENT_QUEST.getDefault());
            prefs.save();
        }
    }

    /** What the new-quest form offers. */
    private static QuestOptions options() {
        final Localizer text = Localizer.getInstance();
        final QuestPreferences prefs = FModel.getQuestPreferences();
        final List<String> worlds = new ArrayList<>();
        FModel.getWorlds().forEach(w -> worlds.add(w.getName()));
        final List<String> sanctioned = new ArrayList<>();
        FModel.getFormats().getSanctionedList().forEach(f -> sanctioned.add(f.getName()));
        final List<String> sealed = new ArrayList<>();
        FModel.getDecks().getSealed().forEach(d -> sealed.add(d.getName()));
        final List<String> draft = new ArrayList<>();
        FModel.getDecks().getDraft().forEach(d -> draft.add(d.getName()));
        final List<String> cubes = new ArrayList<>();
        FModel.getDecks().getCubes().forEach(d -> cubes.add(d.getName()));
        final List<QuestDifficultyRow> difficulties = new ArrayList<>();
        for (int i = 0; i < DIFFICULTIES.length; i++) {
            difficulties.add(new QuestDifficultyRow(text.getMessage(DIFFICULTIES[i]), prefs.getPrefInt(DifficultyPrefs.STARTING_CREDITS, i),
                    prefs.getPrefInt(DifficultyPrefs.STARTING_COMMONS, i), prefs.getPrefInt(DifficultyPrefs.STARTING_UNCOMMONS, i),
                    prefs.getPrefInt(DifficultyPrefs.STARTING_RARES, i)));
        }
        return new QuestOptions(worlds, NewQuestRules.formatWorlds(), sanctioned, NewQuestRules.casualFormats(), NewQuestRules.startingPrecons(),
                sealed, draft, cubes, difficulties);
    }

    /** The form's answers in the form the shared rules take. Null for a pool type the form does not offer. */
    private static NewQuestRules.Choices choices(final QuestCreate c) {
        final StartingPoolType pool = poolType(c.pool());
        if (pool == null || pool == StartingPoolType.CustomFormat) {
            return null;
        }
        final List<Byte> colours = new ArrayList<>();
        final String letters = c.colors() == null ? "" : c.colors();
        for (int i = 0; i < MagicColor.WUBRGC.length; i++) {
            if (letters.indexOf("WUBRGC".charAt(i)) >= 0) {
                colours.add(MagicColor.WUBRGC[i]);
            }
        }
        PoolType distribution;
        try {
            distribution = PoolType.valueOf(c.poolType());
        } catch (final IllegalArgumentException | NullPointerException e) {
            distribution = PoolType.BALANCED;
        }
        return new NewQuestRules.Choices(c.name(), Math.max(0, Math.min(DIFFICULTIES.length - 1, c.difficulty())),
                c.fantasy() ? QuestMode.Fantasy : QuestMode.Classic, c.commander(), c.world(), pool, c.format(), c.precon(), c.savedDeck(),
                new StartingPoolPreferences(distribution, colours, c.artifacts(), c.completeSet(), c.duplicates(), Math.max(0, c.boosters())),
                poolType(c.prizes()), c.prizeFormat(), c.allowUnlocks());
    }

    private static StartingPoolType poolType(final String name) {
        try {
            return name == null ? null : StartingPoolType.valueOf(name);
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public List<Record> page() {
        return List.of(bar(), duelsPage(), decksPage(), challengesPage(), QuestDraftTournaments.tournamentsPage());
    }

    /** The quest's decks, by name. */
    private QuestDecks decksPage() {
        final String current = quest().getCurrentDeck();
        final List<QuestDeckRow> rows = new ArrayList<>();
        for (final Deck deck : quest().getMyDecks()) {
            final CardPool side = deck.get(DeckSection.Sideboard);
            rows.add(new QuestDeckRow(deck.getName(), face(deck), DeckFacts.colors(deck), deck.getMain().countAll(), side == null ? 0 : side.countAll(),
                    QuestUtil.getDeckConformanceProblemsBeforeGame(deck), deck.getName().equals(current)));
        }
        rows.sort(Comparator.comparing(QuestDeckRow::name, String.CASE_INSENSITIVE_ORDER));
        return new QuestDecks(rows);
    }

    /** What the deck editor builds a quest deck from: the quest's cards, with every printing counted, and a sideboard of its own. */
    private static DeckEditor.Collection collection() {
        return new DeckEditor.Collection(quest().getName(), () -> {
            final CardPool owned = new CardPool();
            owned.addAll(quest().getCards().getCardpool());
            return owned;
        }, card -> quest().getCards().isNew(card), () -> {
            quest().getCards().resetNewList();
            quest().save();
        }, deck -> quest().getAvailableLandSets(), () -> quest().save(), false);
    }

    /** A Commander quest's decks are checked as Commander decks. Every other quest's decks are checked as Quest decks. */
    private static GameType deckType() {
        return quest().getDeckConstructionRules() == DeckConstructionRules.Commander ? GameType.Commander : GameType.Quest;
    }

    private static String deckNameProblem(final String name) {
        final String problem = DeckStore.nameProblem(name);
        if (problem != null) {
            return problem;
        }
        return quest().getMyDecks().contains(name.trim()) ? Localizer.getInstance().getMessage("lblWebEditorNameTaken", name.trim()) : null;
    }

    /** The duels on offer. They are kept so that an index the browser sends back still names the duel it was shown. */
    private List<QuestEventDuel> duels() {
        if (duels == null) {
            final List<QuestEventDuel> made = quest().getDuelsManager().generateDuels();
            duels = made == null ? List.of() : made;
        }
        return duels;
    }

    private QuestDuels duelsPage() {
        final QuestController quest = quest();
        final Localizer text = Localizer.getInstance();
        final List<QuestDuelRow> rows = new ArrayList<>();
        final List<QuestEventDuel> offered = duels();
        for (int i = 0; i < offered.size(); i++) {
            final QuestEventDuel duel = offered.get(i);
            // The random opponent is a surprise, so none of its deck is shown
            final boolean random = !duel.showDifficulty();
            rows.add(new QuestDuelRow(i, duel.getTitle(), random ? 0 : duel.getDifficulty().ordinal() + 1, duel.getDescription(),
                    random ? "" : DeckFacts.colors(duel.getEventDeck()), random ? null : face(duel.getEventDeck()), random));
        }
        final QuestAchievements record = quest.getAchievements();
        final boolean fantasy = quest.getMode() == QuestMode.Fantasy;
        final List<QuestPetChoice> pets = new ArrayList<>();
        final List<Integer> lengths = new ArrayList<>();
        String next = null;
        // Pets, the plant, charms and challenges exist only in Fantasy mode, as on desktop's Duels screen
        if (fantasy) {
            for (int slot = 0; slot < QuestController.MAX_PET_SLOTS; slot++) {
                final List<String> owned = quest.getPetsStorage().getAvaliablePets(slot, quest.getAssets()).stream().map(QuestPetController::getName).toList();
                if (!owned.isEmpty()) {
                    pets.add(new QuestPetChoice(slot, owned, quest.getSelectedPet(slot)));
                }
            }
            lengths.addAll(matchLengths());
            final int wins = QuestUtil.nextChallengeInWins();
            next = wins == 0 ? text.getMessage("lblnextChallengeInWins0") : wins == 1 ? text.getMessage("lblnextChallengeInWins1")
                    : text.getMessage("lblnextChallengeInWins2").replace("%n", String.valueOf(wins));
        }
        final Deck deck = QuestUtil.getCurrentDeck();
        String problem = deck == null ? text.getMessage("lblBuildAndSelectaDeck") : null;
        if (deck != null && FModel.getPreferences().getPrefBoolean(FPref.ENFORCE_DECK_LEGALITY)) {
            problem = QuestUtil.getDeckConformanceProblemsBeforeGame(deck);
        }
        return new QuestDuels(rows, record.getWin(), record.getLost(), record.getWinStreakCurrent(), record.getWinStreakBest(), next,
                pets, lengths, quest.getMatchLength(), deck == null ? "" : deck.getName(), problem, canUnlock());
    }

    /** Whether Unlock Sets is offered, by the same rule desktop uses to show its button. */
    private static boolean canUnlock() {
        return quest().getUnlocksTokens() > 0 && quest().getWorldFormat() == null;
    }

    /** The match lengths the charms allow: one with the Charm of Vim, five with the Charm of Vigor. */
    private static List<Integer> matchLengths() {
        final QuestAssets assets = quest().getAssets();
        final List<Integer> lengths = new ArrayList<>();
        if (assets.hasItem(QuestItemType.CHARM_VIM)) {
            lengths.add(1);
        }
        lengths.add(3);
        if (assets.hasItem(QuestItemType.CHARM)) {
            lengths.add(5);
        }
        return lengths;
    }

    /** The picture of a deck or duel: the deck's creature with the highest mana value, or else its first spell. */
    private static String face(final Deck deck) {
        PaperCard best = null;
        PaperCard firstSpell = null;
        for (final PaperCard card : deck.getMain().toFlatList()) {
            final CardType type = card.getRules().getType();
            if (firstSpell == null && !type.isLand()) {
                firstSpell = card;
            }
            if (type.isCreature() && (best == null || card.getRules().getManaCost().getCMC() > best.getRules().getManaCost().getCMC())) {
                best = card;
            }
        }
        final PaperCard face = best != null ? best : firstSpell;
        return face == null ? null : face.getImageKey(false);
    }

    CampaignBar bar() {
        final QuestController quest = quest();
        final Localizer text = Localizer.getInstance();
        final List<Balance> balances = new ArrayList<>();
        balances.add(new Balance("ICO_QUEST_COINSTACK", (int) quest.getAssets().getCredits(), text.getMessage("lblCredits")));
        // Life is bought and lost only in Fantasy mode
        if (quest.getMode() == QuestMode.Fantasy) {
            balances.add(new Balance("ICO_QUEST_LIFE", quest.getAssets().getLife(quest.getMode()), text.getMessage("lblLife")));
        }
        return new CampaignBar(quest.getName(), quest.getRank() + " · " + world(quest.getWorld() == null ? null : quest.getWorld().getName()), balances, Map.of());
    }

    @Override
    public Reward reward() {
        return reward;
    }

    @Override
    public void claim(final Host host) {
        reward = null;
    }

    /** Starts the duel at an index in the list sent to the page, as desktop's Start does. */
    private void duel(final int index, final Host host) {
        final List<QuestEventDuel> offered = duels();
        if (index >= 0 && index < offered.size()) {
            fight(offered.get(index), host);
        }
    }

    /** The challenges on offer. Like desktop's page, it draws challenges again each time, but only where there is room. */
    private QuestChallenges challengesPage() {
        final QuestController quest = quest();
        final QuestAchievements record = quest.getAchievements();
        final List<QuestChallengeRow> rows = new ArrayList<>();
        quest.regenerateChallenges();
        for (final String id : record.getCurrentChallenges()) {
            final QuestEventChallenge c = quest.getChallenges().get(id);
            if (c == null) {
                continue;
            }
            rows.add(new QuestChallengeRow(c.getId(), c.getTitle(), c.getDifficulty() == null ? 0 : c.getDifficulty().ordinal() + 1,
                    c.getDescription(), face(c.getEventDeck()), c.getAILife(), c.getHumanLife(), c.getCreditsReward(),
                    c.getCardRewardList() == null ? List.of() : c.getCardRewardList().stream().map(InventoryItem::getName).toList(),
                    c.isRepeatable(), c.getHumanExtraCards(), c.getAiExtraCards(), c.getHumanDeck() != null));
        }
        // Desktop hides the zeppelin and the next-challenge line in Classic mode, but still offers the challenges
        final boolean fantasy = quest.getMode() == QuestMode.Fantasy;
        final int max = Math.max(0, Math.min(5, record.getWin() / quest.getTurnsToUnlockChallenge() - record.getChallengesPlayed()));
        final int wins = QuestUtil.nextChallengeInWins();
        final Localizer text = Localizer.getInstance();
        final String next = fantasy && rows.isEmpty() ? wins == 1 ? text.getMessage("lblnextChallengeInWins1")
                : text.getMessage("lblnextChallengeInWins2").replace("%n", String.valueOf(wins)) : null;
        return new QuestChallenges(rows, rows.size(), max, next, fantasy && quest.getAssets().hasItem(QuestItemType.ZEPPELIN),
                quest.getAssets().getItemLevel(QuestItemType.ZEPPELIN) == 2);
    }

    /** Leaves a tournament whose draft was not finished, as desktop does. The fee is refunded and the tournament is no longer offered. */
    public void cancelDraft() {
        final QuestEventDraft draft = quest().getAchievements().getCurrentDraft();
        if (draft == null) {
            return;
        }
        quest().getAssets().addCredits(draft.getEntryFee());
        quest().getAchievements().deleteDraft(draft);
        quest().save();
    }

    /** Keeps a tournament's finished draft the way Quest does, and returns the storage its deck is built in. */
    public IStorage<DeckGroup> drafted(final DeckGroup group) {
        QuestDraftUtils.completeDraft(group);
        return quest().getDraftDecks();
    }

    /** Decides the AI matches before the player's without playing them, as mobile does. Then it starts the player's match. Returns why it cannot, or null. */
    private String nextMatch(final Host host) {
        final QuestEventDraft draft = quest().getAchievements().getCurrentDraft();
        if (draft == null || !draft.isStarted() || reward != null) {
            return null;
        }
        // injectRandomMatchOutcome returns true forever once the player is out, so the loop ends when the player has no matches left
        while (draft.playerHasMatchesLeft() && QuestDraftUtils.injectRandomMatchOutcome(false)) {
        }
        quest().save();
        if (!draft.playerHasMatchesLeft()) {
            return null;
        }
        final String illegal = QuestDraftUtils.getDeckLegality();
        if (illegal != null) {
            return illegal;
        }
        view = null;
        controller = null;
        tournamentDecided = false;
        host.startMatch(() -> {
            final PreparedMatch match = QuestDraftUtils.prepareNextMatch();
            if (match != null) {
                tournamentOpponent = match.players().stream().filter(p -> p != match.human()).findFirst().orElseThrow().getPlayer().getName();
            }
            return match;
        }, () -> tournamentOpponent = null);
        return null;
    }

    /** A tournament game, handled as desktop's QuestDraftWinLose does. The bracket gets the winner once the match is over. Nothing goes on the quest's record. */
    private CampaignResult tournamentGameOver(final GameView hostGame, final String opponent) {
        final boolean over = hostGame.isMatchOver();
        final String winner = hostGame.getWinningPlayerName();
        final boolean won = winner != null && !opponent.equals(winner);
        if (over && winner != null) {
            // setWinner treats any name that is not an AI's as the player. But the player may have the same name as an AI
            quest().getAchievements().getCurrentDraft().setWinner(won ? QuestEventDraft.HUMAN : opponent);
            quest().save();
            tournamentDecided = true;
        }
        final Localizer text = Localizer.getInstance();
        final List<ResultButton> buttons = over
                ? List.of(new ResultButton(text.getMessage("lblWebQuestContinueTournament"), "leave", true))
                : List.of(new ResultButton(text.getMessage("btnContinue"), "nextGame", true),
                        new ResultButton(text.getMessage("lblWebQuestForfeitTournament"), "forfeit", false));
        result = new CampaignResult(won, over, null, buttons);
        return result;
    }

    /** Leaves the entered tournament, as desktop's Leave Tournament or Collect Prizes does. It gives prizes if the tournament has started, offers to keep the draft, and adds the pool to the quest. */
    private void endTournament() {
        final QuestEventDraft draft = quest().getAchievements().getCurrentDraft();
        if (draft == null) {
            return;
        }
        if (quest().getDraftDecks().get(QuestEventDraft.DECK_NAME) == null) {
            // Its draft was never finished, so there is no pool to keep
            cancelDraft();
            return;
        }
        final WebQuestView shown = new WebQuestView();
        if (draft.isStarted()) {
            QuestDraftTournaments.prizes(draft, shown);
        }
        final Localizer text = Localizer.getInstance();
        if (SOptionPane.showOptionDialog(text.getMessage("lblWouldLikeSaveDraft"), text.getMessage("lblSaveDraft") + "?", SOptionPane.QUESTION_ICON,
                List.of(text.getMessage("lblYes"), text.getMessage("lblNo")), 0) == 0) {
            draft.saveToRegularDraft();
        }
        draft.addToQuestDecks();
        if (!shown.steps.isEmpty()) {
            final List<RewardStep> steps = new ArrayList<>(reward == null ? List.of() : reward.steps());
            steps.addAll(shown.steps);
            reward = new Reward(steps);
        }
    }

    /** Starts a duel or a challenge, as desktop's Start does. */
    private void fight(final QuestEvent duel, final Host host) {
        // A reward still to be shown belongs to the last match. The next match's reward would replace it
        if (reward != null) {
            return;
        }
        QuestUtil.setEvent(duel);
        if (!QuestUtil.canStartGame()) {
            return;
        }
        // Desktop's start does this in the background before the match
        quest().getDuelsManager().randomizeOpponents();
        quest().setCurrentEvent(duel);
        quest().save();
        view = new WebQuestView();
        controller = null;
        final String face = face(duel.getEventDeck());
        host.startMatch(() -> {
            final PreparedMatch match = QuestUtil.prepareGame();
            match.players().get(1).getPlayer().setAvatarCardImageKey(face);
            return match;
        }, () -> {
            quest().setCurrentEvent(null);
            view = null;
        });
    }

    /** Quest's own result controller is created for each game, as on desktop. The latest one records the match when it is left. */
    @Override
    public CampaignResult gameOver(final GameView hostGame) {
        final String opponent = tournamentOpponent;
        if (opponent != null && hostGame != null) {
            return tournamentGameOver(hostGame, opponent);
        }
        final WebQuestView shown = view;
        if (shown == null || hostGame == null || quest().getCurrentEvent() == null) {
            return null;
        }
        shown.newGame();
        controller = new QuestWinLoseController(hostGame, shown);
        controller.showRewards();
        final boolean over = hostGame.isMatchOver();
        final List<ResultButton> buttons = new ArrayList<>();
        if (shown.getBtnContinue().isVisible()) {
            buttons.add(new ResultButton(shown.getBtnContinue().getText(), "nextGame", true));
        }
        if (shown.getBtnQuit().isVisible()) {
            buttons.add(new ResultButton(shown.getBtnQuit().getText(), over ? "leave" : "quit", buttons.isEmpty()));
        }
        result = new CampaignResult(GamePlayerUtil.getQuestPlayer().getName().equals(hostGame.getWinningPlayerName()), over, null, buttons);
        return result;
    }

    /** The controller's reward logic may ask the player questions. Those questions belong after the result screen. */
    @Override
    public void resultSent() {
        final WebQuestView shown = view;
        final Runnable rest = shown == null ? null : shown.takeScript();
        if (rest != null) {
            rest.run();
        }
    }

    @Override
    public CampaignResult result() {
        return result;
    }

    /** Leaving records the match, as desktop's Quit does. The match's steps become the reward shown over the Duels page. */
    @Override
    public void left() {
        final String opponent = tournamentOpponent;
        if (opponent != null) {
            tournamentOpponent = null;
            result = null;
            // A match left before it is decided is a forfeit, as with desktop's Forfeit Tournament
            if (!tournamentDecided) {
                quest().getAchievements().getCurrentDraft().setWinner(opponent);
                QuestDraftUtils.cancelFurtherMatches();
                quest().save();
            }
            return;
        }
        final QuestWinLoseController last = controller;
        final WebQuestView shown = view;
        controller = null;
        view = null;
        result = null;
        if (last != null) {
            last.actionOnQuit();
        } else if (shown != null) {
            quest().setCurrentEvent(null);
        }
        if (shown != null && !shown.steps.isEmpty()) {
            reward = new Reward(List.copyOf(shown.steps));
        }
        // The opponents were shuffled when the duel started, so the next page offers new ones
        duels = null;
    }

    /** Cards Forge showed the host, such as a booster just bought. They are added to what the reveal has not shown yet. A product the shop sold opens as a pack. */
    public void shown(final String title, final List<PaperCard> cards) {
        final List<RewardStep> steps = new ArrayList<>(reward == null ? List.of() : reward.steps());
        steps.add(WebGuiBase.isProductName(title) ? WebQuestView.packStep(title, cards, WebGuiBase.boosterImageNamed(title)) : WebQuestView.cardsStep(title, cards, false));
        reward = new Reward(steps);
    }

    @Override
    public CataloguePage cards(final CatalogueQuery q) {
        return QuestShop.cards(q);
    }

    @Override
    public Trading trading() {
        return QuestShop.trading();
    }

    @Override
    public String trade(final String source, final List<TradePick> picks) {
        return QuestShop.trade(source, picks);
    }

    /** The quest's record, credits and collection, its tournament placings, and in Fantasy mode the bazaar items it owns. */
    @Override
    public CampaignStats stats(final String scope) {
        final QuestController quest = quest();
        final QuestAchievements record = quest.getAchievements();
        final QuestAssets assets = quest.getAssets();
        final Localizer l = Localizer.getInstance();
        final boolean fantasy = quest.getMode() == QuestMode.Fantasy;
        final List<Figure> figures = new ArrayList<>(List.of(
                new Figure(l.getMessage("lblWins"), record.getWin(), null),
                new Figure(l.getMessage("lblLosses"), record.getLost(), null),
                new Figure(l.getMessage("lblWinStreak"), record.getWinStreakCurrent(), null),
                new Figure(l.getMessage("lblWebQuestBestStreak"), record.getWinStreakBest(), null),
                new Figure(l.getMessage("lblCredits"), (int) Math.min(Integer.MAX_VALUE, assets.getCredits()), null)));
        if (fantasy) {
            figures.add(new Figure(l.getMessage("lblLife"), assets.getLife(quest.getMode()), null));
        }
        figures.add(new Figure(l.getMessage("lblCards"), assets.getCardPool().countAll(), null));
        final int rankUp = FModel.getQuestPreferences().getPrefInt(DifficultyPrefs.WINS_RANKUP, record.getDifficulty());
        figures.add(new Figure(l.getMessage("lblWebQuestWinsToLevel"), rankUp - record.getWin() % rankUp, null));
        final List<StatTable> tables = new ArrayList<>();
        final List<List<String>> placings = new ArrayList<>();
        for (int place = 1; place <= PLACES.length; place++) {
            placings.add(List.of(l.getMessage(PLACES[place - 1]), String.valueOf(record.getWinsForPlace(place))));
        }
        tables.add(new StatTable(List.of(l.getMessage("lblPastResults"), l.getMessage("lblWebQuestTimesHead")), placings));
        if (fantasy) {
            tables.add(new StatTable(List.of(l.getMessage("lblBazaar"), l.getMessage("lblWebQuestLevelHead")), owned(quest)));
        }
        return new CampaignStats(l.getMessage("lblQuestStatistics"), figures, tables, List.of(), null, null);
    }

    private static final String[] PLACES = {"lblWebQuestFirstPlace", "lblWebQuestSecondPlace", "lblWebQuestThirdPlace", "lblWebQuestFourthPlace"};

    /** The bazaar items and pets the quest owns, each with its level and highest level. An item missing from every stall is at its highest level. */
    private static List<List<String>> owned(final QuestController quest) {
        final QuestAssets assets = quest.getAssets();
        final Map<QuestItemType, Integer> most = new HashMap<>();
        for (final String stall : quest.getBazaar().getStallNames()) {
            for (final IQuestBazaarItem item : quest.getBazaar().getItems(quest, stall)) {
                if (item instanceof QuestItemBasic basic) {
                    most.put(basic.getItemType(), basic.getMaxLevel());
                }
            }
        }
        final Localizer l = Localizer.getInstance();
        final List<List<String>> rows = new ArrayList<>();
        for (final QuestItemType type : QuestItemType.values()) {
            if (assets.hasItem(type)) {
                final int level = assets.getItemLevel(type);
                rows.add(List.of(type.getKey(), l.getMessage("lblWebQuestLevelOf", level, most.getOrDefault(type, level))));
            }
        }
        for (int slot = 0; slot < QuestController.MAX_PET_SLOTS; slot++) {
            for (final QuestPetController pet : quest.getPetsStorage().getAvaliablePets(slot, assets)) {
                rows.add(List.of(pet.getName(), l.getMessage("lblWebQuestLevelOf", assets.getPetLevel(pet.getSaveFileKey()), pet.getMaxLevel())));
            }
        }
        return rows;
    }

    /** A preference as desktop's page lists it, by the keys of its label and group. column is the difficulty that a per-difficulty value belongs to. */
    private record PrefField(QPref pref, String label, String group, String column) {
    }

    /** Desktop's preferences in desktop's order (VSubmenuQuestPrefs). Each value in the difficulty table is its own field. */
    private static final List<PrefField> PREFS = prefFields();

    private static List<PrefField> prefFields() {
        final List<PrefField> fields = new ArrayList<>();
        fields.add(new PrefField(QPref.WORLD_RULES_CONFORMANCE, "lblWorldRulesConformance", "lblQuestGameSettings", null));
        final Object[][] rewards = {{QPref.REWARDS_BASE, "lblBaseWinnings"}, {QPref.REWARDS_UNDEFEATED, "lblNoLosses"}, {QPref.REWARDS_POISON, "lblPoisonWin"},
                {QPref.REWARDS_MILLED, "lblMillingWin"}, {QPref.REWARDS_MULLIGAN0, "lblMulligan0Win"}, {QPref.REWARDS_ALTERNATIVE, "lblAlternativeWin"},
                {QPref.REWARDS_WINS_MULTIPLIER, "lblBonusMultiplierperWin"}, {QPref.REWARDS_WINS_MULTIPLIER_MAX, "lblMaxWinsforMultiplier"},
                {QPref.REWARDS_TURN15, "lblWinbyTurn15"}, {QPref.REWARDS_TURN10, "lblWinbyTurn10"}, {QPref.REWARDS_TURN5, "lblWinbyTurn5"},
                {QPref.REWARDS_TURN1, "lblFirstTurnWin"}, {QPref.REWARDS_HEALTH_DIFF_MAX, "lblMaxLifeDiffBonus"},
                {QPref.EXCLUDE_PROMOS_FROM_POOL, "lblExcludePromosFromRewardPool"}};
        add(fields, "lblRewards", rewards);
        final String[][] perDifficulty = {{"WINS_BOOSTER", "lblWinsforBooster"}, {"WINS_RANKUP", "lblWinsforRankIncrease"},
                {"WINS_MEDIUMAI", "lblWinsforMediumAI"}, {"WINS_HARDAI", "lblWinsforHardAI"}, {"WINS_EXPERTAI", "lblWinsforExpertAI"},
                {"STARTING_COMMONS", "lblStartingCommons"}, {"STARTING_UNCOMMONS", "lblStartingUncommons"}, {"STARTING_RARES", "lblStartingRares"},
                {"STARTING_CREDITS", "lblStartingCredits"}};
        final String[] suffixes = {"EASY", "MEDIUM", "HARD", "EXPERT"};
        for (final String[] row : perDifficulty) {
            for (int d = 0; d < suffixes.length; d++) {
                fields.add(new PrefField(QPref.valueOf(row[0] + "_" + suffixes[d]), row[1], "lblDifficultyAdjustments", DIFFICULTIES[d]));
            }
        }
        add(fields, "lblDifficultyAdjustments", new Object[][] {{QPref.WINS_NEW_CHALLENGE, "lblWinsforNewChallenge"},
                {QPref.STARTING_SNOW_LANDS, "lblStartingSnowLands"}, {QPref.STARTING_POOL_COLOR_BIAS, "lblColorBias"},
                {QPref.PENALTY_LOSS, "lblPenaltyforLoss"}, {QPref.MORE_DUEL_CHOICES, "lblMoreDuelChoices"},
                {QPref.WILD_OPPONENTS_MULTIPLIER, "lblWildOpponentMultiplier"}, {QPref.WILD_OPPONENTS_NUMBER, "lblWildOpponentNumber"}});
        add(fields, "lblBoosterPackRatios", new Object[][] {{QPref.BOOSTER_COMMONS, "lblCommon"}, {QPref.BOOSTER_UNCOMMONS, "lblUncommon"},
                {QPref.BOOSTER_RARES, "lblRare"}, {QPref.SPECIAL_BOOSTERS, "lblSpecialBoosters"}});
        add(fields, "lblShopPreferences", new Object[][] {{QPref.SHOP_MAX_PACKS, "lblMaximumPacks"}, {QPref.SHOP_MIN_PACKS, "lblMinimumPacks"},
                {QPref.SHOP_STARTING_PACKS, "lblStartingPacks"}, {QPref.SHOP_WINS_FOR_ADDITIONAL_PACK, "lblWinsforPack"},
                {QPref.WINS_UNLOCK_SET, "lblWinsperSetUnlock"}, {QPref.UNLIMITED_UNLOCKING, "lblAllowFarUnlocks"},
                {QPref.UNLOCK_DISTANCE_MULTIPLIER, "lblUnlockDistanceMultiplier"}, {QPref.SHOP_SINGLES_COMMON, "lblCommonSingles"},
                {QPref.SHOP_SINGLES_UNCOMMON, "lblUncommonSingles"}, {QPref.SHOP_SINGLES_RARE, "lblRareSingles"},
                {QPref.SHOP_SELLING_PERCENTAGE_BASE, "lblCardSalePercentageBase"}, {QPref.SHOP_SELLING_PERCENTAGE_MAX, "lblCardSalePercentageCap"},
                {QPref.SHOP_MAX_SELLING_PRICE, "lblCardSalePriceCap"}, {QPref.SHOP_WINS_FOR_NO_SELL_LIMIT, "lblWinstoUncapSalePrice"},
                {QPref.PLAYSET_SIZE, "lblPlaysetSize"}, {QPref.PLAYSET_BASIC_LAND_SIZE, "lblPlaysetSizeBasicLand"},
                {QPref.PLAYSET_ANY_NUMBER_SIZE, "lblPlaysetSizeAnyNumber"}, {QPref.ITEM_LEVEL_RESTRICTION, "lblItemLevelRestriction"},
                {QPref.FOIL_FILTER_DEFAULT, "lblFoilfilterAlwaysOn"}, {QPref.RATING_FILTER_DEFAULT, "lblRatingsfilterAlwaysOn"}});
        add(fields, "lblDraftTournaments", new Object[][] {{QPref.SIMULATE_AI_VS_AI_RESULTS, "lblSimulateAIvsAIResults"},
                {QPref.WINS_NEW_DRAFT, "lblWinsforNewDraft"}, {QPref.WINS_ROTATE_DRAFT, "lblWinsperDraftRotation"},
                {QPref.DRAFT_ROTATION, "lblRotationType"}});
        return List.copyOf(fields);
    }

    private static void add(final List<PrefField> fields, final String group, final Object[][] rows) {
        for (final Object[] row : rows) {
            fields.add(new PrefField((QPref) row[0], (String) row[1], group, null));
        }
    }

    @Override
    public CampaignPrefs prefs(final String problem) {
        final QuestPreferences prefs = FModel.getQuestPreferences();
        final Localizer l = Localizer.getInstance();
        final List<PrefRow> rows = new ArrayList<>();
        for (final PrefField f : PREFS) {
            rows.add(new PrefRow(f.pref().name(), l.getMessage(f.label()), l.getMessage(f.group()), prefs.getPref(f.pref()),
                    f.column() == null ? null : l.getMessage(f.column())));
        }
        // Neither desktop nor mobile resets Quest's preferences
        return new CampaignPrefs(l.getMessage("lblQuestPreferences"), rows, l.getMessage("lblWebQuestPrefsShared"), problem, false);
    }

    /** Checked as desktop checks it. Two values are decimals and are stored as given. The rest are whole numbers that Quest validates. */
    @Override
    public String setPref(final String key, final String text) {
        final QuestPreferences prefs = FModel.getQuestPreferences();
        final Localizer l = Localizer.getInstance();
        final String value = text.trim();
        for (final PrefField f : PREFS) {
            if (f.pref().name().equals(key)) {
                final String problem;
                if (f.pref() == QPref.UNLOCK_DISTANCE_MULTIPLIER || f.pref() == QPref.WILD_OPPONENTS_MULTIPLIER) {
                    problem = Doubles.tryParse(value) == null ? l.getMessage("lblEnteraDecimal") : null;
                } else {
                    final Integer number = Ints.tryParse(value);
                    problem = number == null ? l.getMessage("lblEnteraNumber") : prefs.validatePreference(f.pref(), number);
                }
                if (problem == null) {
                    prefs.setPref(f.pref(), value);
                    prefs.save();
                }
                return problem;
            }
        }
        return null;
    }

    @Override
    public void resetPrefs() {
    }

    /** A command to this quest. save is the open save, or null when the command comes from the list of saves. */
    private record Call(QuestGame game, String save, Host host) {
    }

    /** Runs a command only on the list of saves. Those commands ask for the form's options and create a new quest. */
    private static <T extends Record> Handler<Call, T> onShelf(final Handler<Call, T> handler) {
        return (c, ch, command) -> {
            if (c.save() == null) {
                handler.run(c, ch, command);
            }
        };
    }

    private static <T extends Record> Handler<Call, T> inSave(final Handler<Call, T> handler) {
        return (c, ch, command) -> {
            if (c.save() != null && quest().getAssets() != null) {
                handler.run(c, ch, command);
            }
        };
    }

    /** A tournament step. The bar and the tournaments page are sent again after it. The step returns why it failed, or null. */
    private interface Step<T> {
        String run(Call call, T command);
    }

    private static <T extends Record> Handler<Call, T> tournament(final Step<T> step) {
        return inSave((c, ch, command) -> {
            final String problem = step.run(c, command);
            if (problem != null) {
                ch.send(new Notice(problem, null, true));
            }
            ch.send(c.game().bar());
            ch.send(QuestDraftTournaments.tournamentsPage());
        });
    }

    /** A change to the world. It brings new opponents, and may change the shop, the bar and every list. */
    private static <T extends Record> Handler<Call, T> newWorld(final Runnable change) {
        return inSave((c, ch, command) -> {
            change.run();
            c.game().duels = null;
            c.game().page().forEach(ch::send);
            // Forge shows an unlocked set's bonus boosters in its usual way, and that adds them to the reveal
            if (c.game().reward != null) {
                ch.send(c.game().reward);
            }
        });
    }

    public static final Commands<Call> COMMANDS = new Commands<Call>()
            .on(QuestOptionsQuery.class, onShelf((c, ch, q) -> ch.send(options())))
            .on(QuestCreate.class, onShelf((c, ch, create) -> {
                final NewQuestRules.Choices choices = choices(create);
                final String problem = choices == null ? Localizer.getInstance().getMessage("lbldckStartPool") : NewQuestRules.problem(choices);
                if (problem != null) {
                    ch.send(c.host().error(problem));
                    return;
                }
                NewQuestRules.start(choices);
                c.game().duels = null;
                c.game().reward = null;
                c.host().opened(quest().getName());
            }))
            .on(QuestDeckNew.class, inSave((c, ch, n) -> {
                final String name = n.name();
                final String problem = name == null ? DeckStore.nameProblem("") : deckNameProblem(name);
                if (problem != null) {
                    ch.send(c.host().error(problem));
                    return;
                }
                // Stored at once, so the deck appears on the Decks page even if no card is added
                final Deck deck = new Deck(name.trim());
                quest().getMyDecks().add(deck);
                quest().save();
                c.host().decks().openCollectionDeck(deck, quest().getMyDecks(), deckType(), collection(), true, ch);
                ch.send(c.game().duelsPage());
                ch.send(c.game().decksPage());
            }))
            .on(QuestDeckRename.class, inSave((c, ch, rename) -> {
                final IStorage<Deck> decks = quest().getMyDecks();
                final Deck deck = rename.deck() == null ? null : decks.get(rename.deck());
                final String problem = deck == null || rename.to() == null ? DeckStore.nameProblem("") : deckNameProblem(rename.to());
                if (problem != null) {
                    ch.send(c.host().error(problem));
                    return;
                }
                final boolean wasCurrent = deck.getName().equals(quest().getCurrentDeck());
                decks.delete(deck.getName());
                deck.setName(rename.to().trim());
                decks.add(deck);
                if (wasCurrent) {
                    quest().setCurrentDeck(deck.getName());
                }
                quest().save();
                ch.send(c.game().duelsPage());
                ch.send(c.game().decksPage());
            }))
            .on(QuestDeckCommand.class, inSave(QuestGame::deckCommand))
            .on(QuestDuel.class, inSave((c, ch, d) -> c.game().duel(d.index(), c.host())))
            .on(QuestChallenge.class, inSave((c, ch, challenge) -> {
                final String id = challenge.id();
                if (id != null && quest().getAchievements().getCurrentChallenges().contains(id)) {
                    c.game().fight(quest().getChallenges().get(id), c.host());
                }
            }))
            .on(QuestEnter.class, tournament((c, e) -> QuestDraftTournaments.enter(e.title(), c.host())))
            .on(QuestTournamentStart.class, tournament((c, s) -> QuestDraftTournaments.startTournament()))
            .on(QuestTournamentNext.class, tournament((c, n) -> c.game().nextMatch(c.host())))
            .on(QuestToken.class, tournament((c, t) -> {
                QuestDraftTournaments.spendToken();
                return null;
            }))
            .on(QuestTournamentLeave.class, inSave((c, ch, l) -> {
                tournament((call, leave) -> {
                    call.game().endTournament();
                    return null;
                }).run(c, ch, l);
                if (c.game().reward != null) {
                    ch.send(c.game().reward);
                }
            }))
            .on(QuestTravel.class, newWorld(QuestUtil::travelWorld))
            .on(QuestUnlock.class, newWorld(() -> {
                if (canUnlock()) {
                    QuestUtil.chooseAndUnlockEdition();
                }
            }))
            .on(QuestTournamentDeck.class, inSave((c, ch, d) -> {
                final DeckGroup decks = quest().getDraftDecks().get(QuestEventDraft.DECK_NAME);
                if (decks != null) {
                    c.host().decks().openPool(decks.getHumanDeck(), quest().getDraftDecks(), GameType.QuestDraft, null, ch);
                }
            }))
            .on(QuestStall.class, inSave((c, ch, stall) -> ch.send(QuestShop.bazaar(stall.name()))))
            .on(QuestBuy.class, inSave((c, ch, buy) -> {
                final String problem = QuestShop.buy(buy.stall(), buy.item());
                if (problem != null) {
                    ch.send(new Notice(problem, null, false));
                }
                // A pet, a charm or the zeppelin changes what the other pages offer
                ch.send(c.game().bar());
                ch.send(c.game().duelsPage());
                ch.send(c.game().challengesPage());
                ch.send(QuestShop.bazaar(buy.stall()));
            }))
            .on(QuestZeppelin.class, inSave((c, ch, z) -> {
                // Level 2 means the zeppelin was flown since the last match. The end of a match sets it back to 1
                if (quest().getAssets().hasItem(QuestItemType.ZEPPELIN) && quest().getAssets().getItemLevel(QuestItemType.ZEPPELIN) != 2) {
                    quest().getAchievements().setCurrentChallenges(null);
                    quest().getAssets().setItemLevel(QuestItemType.ZEPPELIN, 2);
                }
                ch.send(c.game().challengesPage());
            }))
            // The Duels page is sent after a refusal too, so the page's control shows the current choice
            .on(QuestPet.class, inSave((c, ch, pet) -> {
                // The plant's slot takes only the plant, as desktop's switch sets it. A pet must be one the quest owns
                final boolean owned = pet.name() == null || quest().getPetsStorage().getAvaliablePets(pet.slot(), quest().getAssets())
                        .stream().anyMatch(p -> p.getName().equals(pet.name()));
                if (pet.slot() >= 0 && pet.slot() < QuestController.MAX_PET_SLOTS && owned) {
                    quest().selectPet(pet.slot(), pet.name());
                    quest().save();
                }
                ch.send(c.game().duelsPage());
            }))
            .on(QuestMatchLength.class, inSave((c, ch, length) -> {
                if (quest().getMode() == QuestMode.Fantasy && matchLengths().contains(length.games())) {
                    quest().setMatchLength(String.valueOf(length.games()));
                    quest().save();
                }
                ch.send(c.game().duelsPage());
            }));

    /** A deck's own commands, from the Decks page. */
    private static void deckCommand(final Call c, final BrowserChannel channel, final QuestDeckCommand command) {
        final IStorage<Deck> decks = quest().getMyDecks();
        final Deck deck = command.deck() == null ? null : decks.get(command.deck());
        if (deck == null) {
            return;
        }
        switch (command.t()) {
            case questDeckCurrent -> quest().setCurrentDeck(deck.getName());
            case questDeckView -> {
                channel.send(new DeckDetailsMessage(new DeckDetails("quest:" + deck.getName(), deck.getName(),
                        QuestUtil.getDeckConformanceProblemsBeforeGame(deck), DeckFacts.colors(deck), DeckFacts.stats(deck),
                        CardCatalog.groups(deck.getMain(), DeckCatalog.NO_FLAGS), CardCatalog.cards(deck.get(DeckSection.Sideboard), DeckCatalog.NO_FLAGS),
                        null, 0, null)));
                return;
            }
            case questDeckEdit -> {
                c.host().decks().openCollectionDeck(deck, decks, deckType(), collection(), true, channel);
                return;
            }
            case questDeckDelete -> {
                decks.delete(deck.getName());
                if (deck.getName().equals(quest().getCurrentDeck())) {
                    quest().setCurrentDeck(QPref.CURRENT_DECK.getDefault());
                }
            }
        }
        quest().save();
        // The Duels page names the deck duels use
        channel.send(c.game().duelsPage());
        channel.send(c.game().decksPage());
    }

    @Override
    public void handle(final BrowserChannel channel, final Record command, final String save, final Host host) {
        COMMANDS.run(new Call(this, save, host), channel, command);
    }
}
