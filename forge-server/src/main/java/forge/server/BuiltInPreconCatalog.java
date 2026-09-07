package forge.server;

import forge.deck.Deck;
import forge.item.PreconDeck;
import forge.model.FModel;
import forge.gamemodes.quest.QuestController;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Read-only deck choices bundled with the dedicated image. */
final class BuiltInPreconCatalog {
    record Entry(String id, String name, Deck deck) { }

    private BuiltInPreconCatalog() { }

    static List<Entry> available(ServerConfig.Mode mode) {
        List<Entry> entries = new ArrayList<>();
        switch (mode) {
        case COMMANDER -> {
            for (Deck deck : FModel.getDecks().getCommanderPrecons()) {
                entries.add(new Entry("commander:" + deck.getName(), deck.getName(), deck));
            }
        }
        case CONSTRUCTED -> {
            for (PreconDeck deck : QuestController.getPrecons()) {
                entries.add(new Entry("constructed:" + deck.getName(), deck.getName(), deck.getDeck()));
            }
        }
        default -> { }
        }
        entries.sort(Comparator.comparing(Entry::name));
        return entries;
    }

    static Entry find(ServerConfig.Mode mode, String id) {
        return available(mode).stream().filter(entry -> entry.id().equals(id)).findFirst().orElse(null);
    }
}
