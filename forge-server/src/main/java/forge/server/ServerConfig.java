package forge.server;

import java.util.Map;

public record ServerConfig(int port, Mode mode, int maxPlayers, int startDelaySeconds,
                           int reconnectSeconds, int postgameSeconds) {
    public enum Mode { CONSTRUCTED, COMMANDER }

    public static ServerConfig from(Map<String, String> env) {
        Mode mode = Mode.valueOf(env.getOrDefault("FORGE_SERVER_MODE", "COMMANDER"));
        int players = number(env, "MAX_PLAYERS", mode == Mode.CONSTRUCTED ? 2 : 4, 2, 4);
        if (mode == Mode.CONSTRUCTED && players != 2) {
            throw new IllegalArgumentException("Constructed requires MAX_PLAYERS=2");
        }
        return new ServerConfig(number(env, "PORT", 36743, 1, 65535), mode, players,
                number(env, "START_DELAY_SECONDS", 15, 1, 300),
                number(env, "RECONNECT_SECONDS", 300, 1, 3600),
                number(env, "POSTGAME_SECONDS", 120, 1, 3600));
    }

    private static int number(Map<String, String> env, String key, int fallback, int min, int max) {
        String fullKey = "FORGE_SERVER_" + key;
        int value = Integer.parseInt(env.getOrDefault(fullKey, Integer.toString(fallback)));
        if (value < min || value > max) { throw new IllegalArgumentException(fullKey + " must be " + min + ".." + max); }
        return value;
    }
}
