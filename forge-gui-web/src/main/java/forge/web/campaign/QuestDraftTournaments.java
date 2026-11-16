package forge.web.campaign;

import forge.card.CardEdition;
import forge.deck.DeckGroup;
import forge.game.GameType;
import forge.gamemodes.limited.BoosterDraft;
import forge.gamemodes.quest.QuestEventDraft;
import forge.gamemodes.quest.QuestEventDraft.QuestDraftFormat;
import forge.gamemodes.quest.QuestEventDraft.QuestDraftPrizes;
import forge.gamemodes.quest.QuestUtil;
import forge.gamemodes.quest.data.QuestAchievements;
import forge.gui.GuiBase;
import forge.gui.util.SGuiChoose;
import forge.gui.util.SOptionPane;
import forge.item.BoosterPack;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.localinstance.skin.FSkinProp;
import forge.model.FModel;
import forge.util.Localizer;
import forge.web.WebGuiBase;
import forge.web.protocol.CampaignProtocol.*;
import forge.web.protocol.ToBrowser.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

/** Quest's draft tournaments: the page that lists them, entering one, its bracket, starting it, and its prizes. QuestGame plays the tournament's matches. */
final class QuestDraftTournaments {
    private QuestDraftTournaments() {
    }

    /** The tournaments on offer, or the entered one with its bracket. Expired offers are replaced, as on desktop's screen. */
    static QuestTournaments tournamentsPage() {
        final QuestAchievements record = QuestGame.quest().getAchievements();
        if (record.getCurrentDraft() == null) {
            record.generateDrafts();
        }
        final QuestEventDraft entered = record.getCurrentDraft();
        final List<QuestTournamentRow> offered = new ArrayList<>();
        if (entered == null) {
            for (final QuestEventDraft d : record.getDraftEvents()) {
                // canEnter is true when the credits are short of the fee, so its result is negated
                final List<CardEdition> sets = Arrays.stream(d.getBoosterConfiguration()).map(FModel.getMagicDb().getEditions()::get).toList();
                offered.add(new QuestTournamentRow(d.getTitle(), sets.stream().map(CardEdition::getName).toList(),
                        sets.stream().map(WebGuiBase::boosterImage).toList(), d.getEntryFee(), !d.canEnter()));
            }
        }
        final List<Integer> placings = IntStream.rangeClosed(1, 4).map(record::getWinsForPlace).boxed().toList();
        return new QuestTournaments(offered, record.getDraftTokens(), placings, entered == null ? null : bracket(entered));
    }

    /** The standings as rounds. Places 0-7 are the first round's seats, 8-11 the second round's, 12-13 the final's, and 14 the winner's. The winner at place p moves to place 8 + p / 2. */
    private static QuestBracket bracket(final QuestEventDraft draft) {
        final String[] standings = draft.getStandings();
        final List<List<QuestSeat>> rounds = new ArrayList<>();
        for (final int[] round : new int[][] {{0, 8}, {8, 12}, {12, 14}, {14, 15}}) {
            final List<QuestSeat> seats = new ArrayList<>();
            for (int p = round[0]; p < round[1]; p++) {
                seats.add(seat(draft, standings, p));
            }
            rounds.add(seats);
        }
        String next = null;
        final int you = Arrays.asList(standings).lastIndexOf(QuestEventDraft.HUMAN);
        if (draft.isStarted() && draft.playerHasMatchesLeft() && you >= 0) {
            // The seat paired with the player's. An AI match may still decide who holds it
            final String opponent = standings[you ^ 1];
            next = QuestEventDraft.UNDETERMINED.equals(opponent) ? "" : seat(draft, standings, you ^ 1).name();
        }
        return new QuestBracket(draft.getTitle(), rounds, next, draft.getPlacementString(), draft.isStarted(),
                QuestGame.quest().getDraftDecks().get(QuestEventDraft.DECK_NAME) != null);
    }

    private static QuestSeat seat(final QuestEventDraft draft, final String[] standings, final int place) {
        final String held = standings[place];
        if (QuestEventDraft.UNDETERMINED.equals(held)) {
            return new QuestSeat(null, false, "open");
        }
        final boolean you = QuestEventDraft.HUMAN.equals(held);
        // The browser fills in the player's name, because it knows the name the player's matches are played under
        final String name = you ? "" : draft.getAINames()[Integer.parseInt(held) - 1];
        final String after = place == 14 ? held : standings[8 + place / 2];
        return new QuestSeat(name, you, QuestEventDraft.UNDETERMINED.equals(after) ? "open" : after.equals(held) ? "won" : "out");
    }

    /** Enters a tournament on offer, as desktop's Enter does. It pays the fee, deals the packs and opens the draft. Returns why it cannot, or null. */
    static String enter(final String title, final Campaign.Host host) {
        final QuestAchievements record = QuestGame.quest().getAchievements();
        if (title == null || record.getCurrentDraft() != null || record.getDraftEvents() == null) {
            return null;
        }
        for (final QuestEventDraft offered : record.getDraftEvents()) {
            if (title.equals(offered.getTitle())) {
                if (offered.canEnter()) {
                    return Localizer.getInstance().getMessage("lblWebQuestCreditsShort", offered.getEntryFee() - QuestGame.quest().getAssets().getCredits());
                }
                final BoosterDraft draft = offered.enter();
                QuestGame.quest().save();
                host.startDraft(draft);
                return null;
            }
        }
        return null;
    }

    /** Spends a token on a tournament of a format the player chooses, as desktop's Spend Token does. */
    static void spendToken() {
        final QuestAchievements record = QuestGame.quest().getAchievements();
        if (record.getDraftTokens() <= 0) {
            return;
        }
        final Localizer text = Localizer.getInstance();
        final List<QuestDraftFormat> formats = QuestEventDraft.getAvailableFormats(QuestGame.quest());
        if (formats.isEmpty()) {
            SOptionPane.showErrorDialog(text.getMessage("lblNoAvailableDraftsMessage"), text.getMessage("lblNoAvailableDrafts"));
            return;
        }
        final QuestDraftFormat format = SGuiChoose.oneOrNone(text.getMessage("lblChooseDraftFormat"), formats);
        if (format == null) {
            return;
        }
        // Created only to show its fee, as desktop does. The token creates the tournament itself
        final QuestEventDraft shown = QuestEventDraft.getDraftOrNull(QuestGame.quest(), format);
        if (shown != null && SOptionPane.showConfirmDialog(text.getMessage("lblEntryFeeOfDraftTournament") + shown.getEntryFee()
                + text.getMessage("lblWouldLikeCreateTournament"), text.getMessage("lblCreatingDraftTournament"))) {
            record.spendDraftToken(format);
        }
    }

    /** Starts the entered tournament once its deck is legal, as desktop's Start Tournament does. Returns why it cannot, or null. */
    static String startTournament() {
        final QuestEventDraft draft = QuestGame.quest().getAchievements().getCurrentDraft();
        final DeckGroup decks = QuestGame.quest().getDraftDecks().get(QuestEventDraft.DECK_NAME);
        if (draft == null || draft.isStarted()) {
            return null;
        }
        if (decks == null) {
            return Localizer.getInstance().getMessage("lblWebQuestDraftLost");
        }
        final String problem = GameType.QuestDraft.getDeckFormat().getDeckConformanceProblem(decks.getHumanDeck());
        if (problem != null && FModel.getPreferences().getPrefBoolean(FPref.ENFORCE_DECK_LEGALITY)) {
            return Localizer.getInstance().getMessage("lblDeck") + " " + problem;
        }
        draft.start();
        return null;
    }

    /** Gives the prizes for the player's placing, as desktop's endTournamentAndAwardPrizes does, and shows them as reward steps. */
    static void prizes(final QuestEventDraft draft, final WebQuestView shown) {
        final Localizer text = Localizer.getInstance();
        final String placing = text.getMessage("lblForPlacing") + draft.getPlacementString();
        final QuestDraftPrizes prizes = draft.collectPrizes();
        // Only the first four places win anything
        if (prizes == null) {
            return;
        }
        if (prizes.hasCredits()) {
            shown.showMessage(placing + text.getMessage("lblHaveBeAward") + QuestUtil.formatCredits(prizes.credits) + " " + text.getMessage("lblCredits") + "!",
                    text.getMessage("lblCreditsAwarded"), FSkinProp.ICO_QUEST_GOLD);
        }
        if (prizes.hasIndividualCards()) {
            shown.showCards(text.getMessage("lblTournamentReward"), prizes.individualCards);
        }
        if (prizes.hasBoosterPacks()) {
            final String plural = prizes.boosterPacks.size() == 1 ? "" : "s";
            shown.showMessage(placing + text.getMessage("lblHaveBeAward") + prizes.boosterPacks.size() + " " + text.getMessage("lblBoosterPack") + plural + "!",
                    text.getMessage("lblBoosterPack") + plural + " " + text.getMessage("lblAwarded"), FSkinProp.ICO_QUEST_BOX);
            for (final BoosterPack pack : prizes.boosterPacks) {
                shown.steps.add(WebQuestView.packStep(pack.getName(), pack.getCards(), pack.getImageKey(false)));
            }
        }
        if (prizes.selectRareFromSets()) {
            final PaperCard card = GuiBase.getInterface().chooseCard(text.getMessage("lblSelectACard"), text.getMessage("lblSelectKeepCard"), prizes.selectRareCards);
            if (card != null) {
                prizes.addSelectedCard(card);
                shown.showMessage("'" + card.getDisplayName() + "' " + text.getMessage("lblAddToCollection"), text.getMessage("lblCardAdded"), FSkinProp.ICO_QUEST_STAKES);
            }
        }
        if (draft.getPlayerPlacement() == 1) {
            shown.showMessage(placing + text.getMessage("lblHaveBeAwardToken"), text.getMessage("lblBonusToken"), FSkinProp.ICO_QUEST_NOTES);
            QuestGame.quest().getAchievements().addDraftToken();
        }
        QuestGame.quest().save();
    }
}
