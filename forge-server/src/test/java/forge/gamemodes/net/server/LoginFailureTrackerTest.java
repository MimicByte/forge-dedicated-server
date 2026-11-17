package forge.gamemodes.net.server;

import org.testng.Assert;
import org.testng.annotations.Test;

public class LoginFailureTrackerTest {
    @Test public void blocksAfterConfiguredFailuresAndExpires() {
        LoginFailureTracker tracker = new LoginFailureTracker(3, 60, 900);
        tracker.recordFailure("127.0.0.1", 0);
        tracker.recordFailure("127.0.0.1", 1);
        Assert.assertFalse(tracker.isBlocked("127.0.0.1", 2));
        tracker.recordFailure("127.0.0.1", 2);
        Assert.assertTrue(tracker.isBlocked("127.0.0.1", 3));
        Assert.assertFalse(tracker.isBlocked("127.0.0.1", 900_002));
    }

    @Test public void successfulLoginClearsFailures() {
        LoginFailureTracker tracker = new LoginFailureTracker(2, 60, 900);
        tracker.recordFailure("127.0.0.1", 0);
        tracker.clear("127.0.0.1");
        tracker.recordFailure("127.0.0.1", 1);
        Assert.assertFalse(tracker.isBlocked("127.0.0.1", 2));
    }

    @Test public void expiredBlockDoesNotReuseFailuresFromItsWindow() {
        LoginFailureTracker tracker = new LoginFailureTracker(2, 60, 10);
        tracker.recordFailure("127.0.0.1", 0);
        tracker.recordFailure("127.0.0.1", 1);
        Assert.assertTrue(tracker.isBlocked("127.0.0.1", 2));
        Assert.assertFalse(tracker.isBlocked("127.0.0.1", 10_001));
        tracker.recordFailure("127.0.0.1", 10_001);
        Assert.assertFalse(tracker.isBlocked("127.0.0.1", 10_002));
    }
}
