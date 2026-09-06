package forge.server;

import java.util.Map;
import org.testng.Assert;
import org.testng.annotations.Test;

public class ServerConfigTest {
    @Test public void defaults() {
        ServerConfig c = ServerConfig.from(Map.of());
        Assert.assertEquals(c.mode(), ServerConfig.Mode.COMMANDER);
        Assert.assertEquals(c.maxPlayers(), 4);
        Assert.assertEquals(c.startDelaySeconds(), 15);
        Assert.assertEquals(c.reconnectSeconds(), 300);
    }
    @Test public void constructedDefaultsToTwo() {
        Assert.assertEquals(ServerConfig.from(Map.of("FORGE_SERVER_MODE", "CONSTRUCTED")).maxPlayers(), 2);
    }
    @Test(expectedExceptions = IllegalArgumentException.class) public void rejectsConstructedMultiplayer() {
        ServerConfig.from(Map.of("FORGE_SERVER_MODE", "CONSTRUCTED", "FORGE_SERVER_MAX_PLAYERS", "4"));
    }
    @Test(expectedExceptions = IllegalArgumentException.class) public void rejectsBadPort() {
        ServerConfig.from(Map.of("FORGE_SERVER_PORT", "0"));
    }
    @Test(expectedExceptions = IllegalArgumentException.class) public void rejectsZeroDeadline() {
        ServerConfig.from(Map.of("FORGE_SERVER_RECONNECT_SECONDS", "0"));
    }
}
