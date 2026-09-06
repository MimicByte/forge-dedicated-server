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
    @Test public void constructedUsesNormalRoomCapacity() {
        Assert.assertEquals(ServerConfig.from(Map.of("FORGE_SERVER_MODE", "CONSTRUCTED")).maxPlayers(), 4);
    }
    @Test public void supportsAllConfiguredFormatsAndVariants() {
        ServerConfig c = ServerConfig.from(Map.of(
                "FORGE_SERVER_MODE", "tiny_leaders",
                "FORGE_SERVER_MAX_PLAYERS", "8",
                "FORGE_SERVER_VARIANTS", "planechase, vanguard, archenemy"));
        Assert.assertEquals(c.mode(), ServerConfig.Mode.TINY_LEADERS);
        Assert.assertEquals(c.maxPlayers(), 8);
        Assert.assertEquals(c.variants(), java.util.Set.of(ServerConfig.Variant.PLANECHASE,
                ServerConfig.Variant.VANGUARD, ServerConfig.Variant.ARCHENEMY));
    }
    @Test(expectedExceptions = IllegalArgumentException.class) public void rejectsUnknownVariant() {
        ServerConfig.from(Map.of("FORGE_SERVER_VARIANTS", "PLANECHASE,UNKNOWN"));
    }
    @Test(expectedExceptions = IllegalArgumentException.class) public void rejectsDuplicateVariant() {
        ServerConfig.from(Map.of("FORGE_SERVER_VARIANTS", "PLANECHASE,PLANECHASE"));
    }
    @Test(expectedExceptions = IllegalArgumentException.class) public void rejectsBadPort() {
        ServerConfig.from(Map.of("FORGE_SERVER_PORT", "0"));
    }
    @Test(expectedExceptions = IllegalArgumentException.class) public void rejectsZeroDeadline() {
        ServerConfig.from(Map.of("FORGE_SERVER_RECONNECT_SECONDS", "0"));
    }
}
