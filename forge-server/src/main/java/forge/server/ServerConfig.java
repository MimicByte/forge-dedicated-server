package forge.server;

import java.util.Map;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

public record ServerConfig(int port, Mode mode, int maxPlayers, int startDelaySeconds,
                           int reconnectSeconds, int postgameSeconds, Set<Variant> variants) {
    public enum Mode { CONSTRUCTED, COMMANDER, OATHBREAKER, TINY_LEADERS, BRAWL }
    public enum Variant { PLANECHASE, VANGUARD, ARCHENEMY }

    public static ServerConfig from(Map<String, String> env) {
        Mode mode = mode(env.getOrDefault("FORGE_SERVER_MODE", "COMMANDER"));
        int players = number(env, "MAX_PLAYERS", 4, 2, 8);
        return new ServerConfig(number(env, "PORT", 36743, 1, 65535), mode, players,
                number(env, "START_DELAY_SECONDS", 15, 1, 300),
                number(env, "RECONNECT_SECONDS", 300, 1, 3600),
                number(env, "POSTGAME_SECONDS", 120, 1, 3600), variants(env));
    }

    private static Mode mode(String value) {
        try {
            return Mode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("FORGE_SERVER_MODE must be one of " + java.util.Arrays.toString(Mode.values()));
        }
    }

    private static Set<Variant> variants(Map<String, String> env) {
        String value = env.getOrDefault("FORGE_SERVER_VARIANTS", "").trim();
        if (value.isEmpty()) { return Set.of(); }
        EnumSet<Variant> parsed = EnumSet.noneOf(Variant.class);
        for (String item : value.split(",")) {
            String normalized = item.trim().toUpperCase(Locale.ROOT);
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException("FORGE_SERVER_VARIANTS contains an empty value");
            }
            try {
                Variant variant = Variant.valueOf(normalized);
                if (!parsed.add(variant)) {
                    throw new IllegalArgumentException("FORGE_SERVER_VARIANTS contains duplicate " + variant);
                }
            } catch (IllegalArgumentException e) {
                if (e.getMessage() != null && e.getMessage().startsWith("FORGE_SERVER_VARIANTS")) { throw e; }
                throw new IllegalArgumentException("FORGE_SERVER_VARIANTS has unsupported value " + item.trim());
            }
        }
        return Set.copyOf(parsed);
    }

    private static int number(Map<String, String> env, String key, int fallback, int min, int max) {
        String fullKey = "FORGE_SERVER_" + key;
        int value = Integer.parseInt(env.getOrDefault(fullKey, Integer.toString(fallback)));
        if (value < min || value > max) { throw new IllegalArgumentException(fullKey + " must be " + min + ".." + max); }
        return value;
    }
}
