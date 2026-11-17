package forge.server;

import forge.deck.Deck;
import forge.gamemodes.net.event.UpdateLobbyPlayerEvent;
import forge.gamemodes.match.LobbySlotType;
import org.testng.Assert;
import org.testng.annotations.Test;

public class DedicatedLobbyControllerTest {
    @Test public void limitedBuildUpdatesDiscardLobbyConfigurationFields() {
        UpdateLobbyPlayerEvent event = UpdateLobbyPlayerEvent.create(LobbySlotType.AI, "Not Allowed", 3, 4,
                1, true, true, java.util.Set.of(), "Default");
        event.clearFieldsExceptDeckAndReady();
        Assert.assertNull(event.getType());
        Assert.assertNull(event.getName());
        Assert.assertEquals(event.getAvatarIndex(), -1);
        Assert.assertEquals(event.getSleeveIndex(), -1);
        Assert.assertEquals(event.getTeam(), -1);
        Assert.assertNull(event.getArchenemy());
        Assert.assertNull(event.getAiOptions());

        UpdateLobbyPlayerEvent deckUpdate = UpdateLobbyPlayerEvent.deckUpdate(new Deck("Limited Deck"));
        deckUpdate.clearFieldsExceptDeckAndReady();
        Assert.assertNotNull(deckUpdate.getDeck());
        UpdateLobbyPlayerEvent readyUpdate = UpdateLobbyPlayerEvent.isReadyUpdate(true);
        readyUpdate.clearFieldsExceptDeckAndReady();
        Assert.assertEquals(readyUpdate.getReady(), Boolean.TRUE);
    }

    @Test public void announcesEachOfTheFinalFiveSecondsOnce() {
        long deadline = 10_000L;
        Assert.assertEquals(DedicatedLobbyController.nextCountdownAnnouncement(deadline, 4_999L, 6), 0);
        Assert.assertEquals(DedicatedLobbyController.nextCountdownAnnouncement(deadline, 5_000L, 6), 5);
        Assert.assertEquals(DedicatedLobbyController.nextCountdownAnnouncement(deadline, 5_001L, 5), 0);
        Assert.assertEquals(DedicatedLobbyController.nextCountdownAnnouncement(deadline, 6_000L, 5), 4);
        Assert.assertEquals(DedicatedLobbyController.nextCountdownAnnouncement(deadline, 7_000L, 4), 3);
        Assert.assertEquals(DedicatedLobbyController.nextCountdownAnnouncement(deadline, 8_000L, 3), 2);
        Assert.assertEquals(DedicatedLobbyController.nextCountdownAnnouncement(deadline, 9_000L, 2), 1);
        Assert.assertEquals(DedicatedLobbyController.nextCountdownAnnouncement(deadline, 10_000L, 1), 0);
    }
}
