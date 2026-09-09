package forge.server;

import org.testng.Assert;
import org.testng.annotations.Test;

public class DedicatedLobbyControllerTest {
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
