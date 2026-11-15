package forge.web.deck;

import forge.StaticData;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.deck.io.DeckStorage;
import forge.game.GameType;
import forge.item.PaperCard;
import forge.util.storage.IStorage;
import forge.util.storage.StorageImmediatelySerialized;
import forge.web.WebTestSupport;
import forge.web.protocol.ToBrowser;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DeckEditorTest {
    private File dir;
    private final Map<GameType, IStorage<Deck>> made = new HashMap<>();
    private final DeckStore.Storages storages = format -> made.computeIfAbsent(DeckStore.family(format), f -> {
        final File folder = new File(dir, f.name());
        folder.mkdirs();
        return new StorageImmediatelySerialized<>(f.name(), new DeckStorage(folder, folder.getPath()));
    });

    @BeforeClass
    public void setUp() {
        WebTestSupport.initModel();
    }

    // Fails if a card's printings are not listed newest first, or do not carry the style that the printing picker filters by
    @Test
    public void printingsAreNewestFirstAndCarryTheirStyle() {
        final List<ToBrowser.Printing> printings = CardCatalog.printings("Llanowar Elves", null);
        for (int i = 1; i < printings.size(); i++) {
            Assert.assertTrue(printings.get(i - 1).year() >= printings.get(i).year(), "printing " + i + " is newer than the one before it");
        }
        Assert.assertTrue(printings.stream().anyMatch(p -> "cards".equals(p.style())), "no printing is in its set's main run");
        Assert.assertTrue(printings.stream().anyMatch(p -> p.style() != null && !"cards".equals(p.style())), "no printing has a style of its own");
    }

    @BeforeMethod
    public void freshFolders() throws IOException {
        dir = Files.createTempDirectory("forge-editor").toFile();
        made.clear();
    }

    private DeckEditor editor(final Deck deck, final GameType format) {
        return new DeckEditor(deck, false, false, new DeckEditor.Stored(storages.of(format)), LegalityScope.of(format, null), storages,
                false, (id, text, f) -> { }, null);
    }

    private static PaperCard card(final String name) {
        return StaticData.instance().getCommonCards().getCard(name);
    }

    // Fails if editing a precon writes to the precon rather than a copy
    @Test
    public void readOnlySourceIsCopied() {
        final Deck precon = new Deck("Precon");
        precon.getMain().add(card("Forest"), 10);
        final DeckEditor e = new DeckEditor(precon, true, false, new DeckEditor.Stored(storages.of(GameType.Constructed)),
                LegalityScope.of(GameType.Constructed, null), storages, false, (id, text, f) -> { }, null);
        Assert.assertEquals(e.copyOf(), "Precon");
        Assert.assertNull(e.add("Llanowar Elves", DeckSection.Main, 1));
        Assert.assertEquals(precon.getMain().countByName("Llanowar Elves"), 0);
        Assert.assertEquals(e.deck().getName(), "Precon (copy)");
        Assert.assertNull(e.copyOf());
        Assert.assertTrue(storages.of(GameType.Constructed).contains("Precon (copy)"));
    }

    // Fails if a new deck saves over a deck the player already has under the same name
    @Test
    public void newDeckDoesNotOverwriteAStoredNewDeck() {
        final Deck existing = new Deck(DeckEditor.newDeckName());
        existing.getMain().add(card("Swamp"), 30);
        storages.of(GameType.Constructed).add(existing);
        final DeckEditor e = editor(new Deck(DeckEditor.newDeckName()), GameType.Constructed);
        Assert.assertNull(e.add("Lightning Bolt", DeckSection.Main, 1));
        Assert.assertEquals(e.deck().getName(), DeckEditor.newDeckName() + " (2)");
        Assert.assertEquals(storages.of(GameType.Constructed).get(DeckEditor.newDeckName()).getMain().countByName("Swamp"), 30);
    }

    // Fails if a card added in a chosen printing is added in another, or a printing of a different card is accepted for it
    @Test
    public void aCardIsAddedInTheChosenPrinting() {
        final DeckEditor e = editor(new Deck("Printings"), GameType.Constructed);
        final List<ToBrowser.Printing> printings = CardCatalog.printings("Llanowar Elves", null);
        final String oldest = printings.get(printings.size() - 1).key();
        Assert.assertNull(e.addPrinting("Llanowar Elves", DeckSection.Main, oldest));
        final Map.Entry<PaperCard, Integer> held = e.deck().getMain().iterator().next();
        Assert.assertEquals(held.getKey().getImageKey(false), oldest);
        Assert.assertEquals(held.getValue().intValue(), 1);
        Assert.assertNotNull(e.addPrinting("Forest", DeckSection.Main, oldest), "a printing of another card was accepted");
        Assert.assertEquals(e.deck().getMain().countAll(), 1);
    }

    // Fails if a rename onto another deck's file overwrites it
    @Test
    public void renameOntoTakenNameRefused() {
        final Deck other = new Deck("Zoo");
        other.getMain().add(card("Forest"), 1);
        storages.of(GameType.Constructed).add(other);
        final DeckEditor e = editor(new Deck("Burn"), GameType.Constructed);
        e.add("Lightning Bolt", DeckSection.Main, 1);
        Assert.assertNotNull(e.rename("zoo"));
        Assert.assertEquals(storages.of(GameType.Constructed).get("Zoo").getMain().countByName("Forest"), 1);
    }

    // Fails if a guest's change is saved to the host's storage and not sent to the guest's browser
    @Test
    public void guestReadOnlyCopyGoesToDevice() {
        final Deck host = new Deck("Host deck");
        host.getMain().add(card("Forest"), 10);
        final List<String> sent = new ArrayList<>();
        final DeckEditor e = new DeckEditor(host, true, false, new DeckEditor.Stored(storages.of(GameType.Constructed)),
                LegalityScope.of(GameType.Constructed, null), storages, true, (id, text, f) -> sent.add(text), null);
        Assert.assertNull(e.add("Llanowar Elves", DeckSection.Main, 1));
        Assert.assertTrue(e.target() instanceof DeckEditor.Device);
        Assert.assertEquals(sent.size(), 1);
        Assert.assertTrue(sent.get(0).contains("Llanowar Elves"));
        Assert.assertFalse(storages.of(GameType.Constructed).contains("Host deck (copy)"));
    }

    /** An editor for a collection of exactly these cards, given by name and set. */
    private DeckEditor collectionEditor(final GameType format, final boolean mainOnly, final Object... nameSetCount) {
        final CardPool owned = new CardPool();
        for (int i = 0; i < nameSetCount.length; i += 3) {
            owned.add(StaticData.instance().getCommonCards().getCard((String) nameSetCount[i], (String) nameSetCount[i + 1]), (Integer) nameSetCount[i + 2]);
        }
        return new DeckEditor(new Deck("Owned deck"), false, false, new DeckEditor.Stored(storages.of(format)), LegalityScope.of(format, null), storages,
                false, (id, text, f) -> { }, new DeckEditor.Collection("Test", () -> owned, c -> false, () -> { }, d -> List.of(), () -> { }, mainOnly));
    }

    // Fails if a deck built from a collection can hold more copies of a printing than are owned
    @Test
    public void aCollectionsPrintingsAreTakenInTurn() {
        final DeckEditor e = collectionEditor(GameType.Constructed, false, "Shock", "10E", 1, "Shock", "M19", 3);
        Assert.assertNull(e.add("Shock", DeckSection.Main, 4));
        final Map<String, Integer> bySet = new HashMap<>();
        e.deck().getMain().forEach(c -> bySet.merge(c.getKey().getEdition(), c.getValue(), Integer::sum));
        Assert.assertEquals(bySet, Map.of("10E", 1, "M19", 3));
        Assert.assertNotNull(e.add("Shock", DeckSection.Sideboard, 1), "a fifth Shock was taken from a collection of four");
    }

    // Fails if a collection that does not fix the sideboard refuses a sideboard edit, or one that does accepts it
    @Test
    public void onlyAMainOnlyCollectionFixesTheSideboard() {
        Assert.assertNull(collectionEditor(GameType.Constructed, false, "Shock", "M19", 2).add("Shock", DeckSection.Sideboard, 1));
        Assert.assertNotNull(collectionEditor(GameType.Constructed, true, "Shock", "M19", 2).add("Shock", DeckSection.Sideboard, 1));
    }

    // Fails if a basic land outside the commander's colours is offered, or Wastes, which has no colour identity, is not
    @Test
    public void theLandRowFollowsTheCommandersColourIdentity() {
        final Deck deck = new Deck("Krenko");
        deck.getOrCreate(DeckSection.Commander).add(card("Krenko, Mob Boss"));
        final Map<String, Boolean> allowed = new HashMap<>();
        editor(deck, GameType.Commander).state().lands().forEach(l -> allowed.put(l.name(), l.allowed()));
        Assert.assertEquals(allowed.get("Mountain"), Boolean.TRUE);
        Assert.assertEquals(allowed.get("Plains"), Boolean.FALSE);
        Assert.assertEquals(allowed.get("Wastes"), Boolean.TRUE);
    }

    // Fails if a Commander deck built from a collection can take a commander that is not owned
    @Test
    public void aCommanderMustBeOwned() {
        final DeckEditor e = collectionEditor(GameType.Commander, false, "Isamaru, Hound of Konda", "CHK", 1);
        Assert.assertNotNull(e.makeCommander("Krenko, Mob Boss", null));
        Assert.assertNull(e.makeCommander("Isamaru, Hound of Konda", null));
        Assert.assertEquals(e.deck().getCommanders().get(0).getEdition(), "CHK");
    }

    // Fails if a Commander Draft deck replaces a one-colour commander with another. Desktop's draft editor pairs them
    @Test
    public void aCommanderDraftPairsTwoOneColourLegends() {
        final Deck pool = new Deck("Draft");
        pool.getOrCreate(DeckSection.Sideboard).add(StaticData.instance().getCommonCards().getCard("Isamaru, Hound of Konda"), 1);
        pool.getOrCreate(DeckSection.Sideboard).add(StaticData.instance().getCommonCards().getCard("Krenko, Mob Boss"), 1);
        final DeckEditor e = editor(pool, GameType.CommanderDraft);
        Assert.assertNull(e.makeCommander("Isamaru, Hound of Konda", null));
        Assert.assertNull(e.makeCommander("Krenko, Mob Boss", null));
        Assert.assertEquals(e.deck().getCommanders().size(), 2);
    }
}
