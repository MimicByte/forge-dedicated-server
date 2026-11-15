package forge.web.protocol;

import com.google.gson.JsonObject;
import forge.web.protocol.ToBrowser.ErrorMessage;
import org.testng.Assert;
import org.testng.annotations.Test;

/** Tests the rules used to write and read every protocol record. The tests run with assertions on, as surefire does. */
public class MessageJsonTest {

    // Fails if a record with a null in a field not marked nullable is written without an error
    @Test
    public void aNullInAFieldNotMarkedNullableIsCaught() {
        Assert.expectThrows(AssertionError.class, () -> MessageJson.encode(new ErrorMessage(null)));
    }

    // Fails if two records read the same name. The second would then take every message meant for the first
    @Test
    public void eachNameIsReadByItsOwnRecord() {
        for (final Class<? extends Record> c : FromBrowser.COMMANDS) {
            for (final String name : MessageJson.commandNames(c)) {
                final JsonObject message = new JsonObject();
                message.addProperty("t", name);
                final Record decoded = MessageJson.decode(message);
                Assert.assertEquals(decoded.getClass(), c, name);
                Assert.assertEquals(MessageJson.commandName(decoded), name);
            }
        }
    }
}
