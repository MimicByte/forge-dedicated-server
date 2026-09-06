package forge.server;

import forge.gamemodes.net.ReplyPool;
import org.testng.Assert;
import org.testng.annotations.Test;

public class ReplyPoolTest {
    @Test public void lateReplyAfterDisconnectIsIgnored() {
        ReplyPool replies = new ReplyPool();
        replies.initialize(1);
        replies.cancelAll();
        replies.complete(1, "late");
        Assert.assertNull(replies.get(1));
    }
    @Test public void completedRepliesAreReleased() {
        ReplyPool replies = new ReplyPool();
        replies.initialize(7);
        replies.complete(7, "answer");
        Assert.assertEquals(replies.get(7), "answer");
        Assert.assertNull(replies.get(7));
    }
}
