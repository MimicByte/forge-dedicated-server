package forge.server;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import forge.game.GameType;

/** Immutable startup configuration. Lobby rules may be replaced in memory by the admin API. */
public record ServerConfig(int port, int adminPort, String adminToken, int maxPlayers,
                           int startDelaySeconds, int reconnectSeconds, int postgameSeconds,
                           LobbyRules rules) {
    public enum Mode { CONSTRUCTED, COMMANDER, OATHBREAKER, TINY_LEADERS, BRAWL, MOMIR_BASIC, MOJHOSTO }
    public enum Variant { PLANECHASE, VANGUARD, ARCHENEMY, ARCHENEMY_RUMBLE }

    public record LobbyRules(Mode mode, Set<Variant> variants, int gamesPerMatch,
                             int commanderBracket, boolean enforceDeckLegality) {
        public LobbyRules {
            variants = Set.copyOf(variants);
            validateCombination(mode, variants);
            if (gamesPerMatch != 1 && gamesPerMatch != 3 && gamesPerMatch != 5) {
                throw new IllegalArgumentException("FORGE_SERVER_GAMES_PER_MATCH must be one of [1, 3, 5]");
            }
            if (commanderBracket < 1 || commanderBracket > 5) {
                throw new IllegalArgumentException("FORGE_SERVER_COMMANDER_BRACKET must be 1..5");
            }
        }
        public GameType baseGameType() {
            return switch (mode) {
        case CONSTRUCTED -> GameType.Constructed;
        case COMMANDER -> GameType.Commander;
        case OATHBREAKER -> GameType.Oathbreaker;
        case TINY_LEADERS -> GameType.TinyLeaders;
        case BRAWL -> GameType.Brawl;
        case MOMIR_BASIC -> GameType.MomirBasic;
        case MOJHOSTO -> GameType.MoJhoSto;
            };
        }
    }

    public Mode mode() { return rules.mode(); }
    public Set<Variant> variants() { return rules.variants(); }
    public GameType baseGameType() { return rules.baseGameType(); }
    public boolean adminEnabled() { return !adminToken.isBlank(); }

    public static ServerConfig from(Map<String, String> env) {
        Mode mode = mode(env.getOrDefault("FORGE_SERVER_MODE", "COMMANDER"));
        int port = number(env, "PORT", 36743, 1, 65535);
        String token = env.getOrDefault("FORGE_SERVER_ADMIN_TOKEN", "").trim();
        int adminPort = number(env, "ADMIN_PORT", 8080, 1, 65535);
        if (!token.isEmpty() && port == adminPort) {
            throw new IllegalArgumentException("FORGE_SERVER_ADMIN_PORT must differ from FORGE_SERVER_PORT");
        }
        LobbyRules rules = new LobbyRules(mode, variants(env),
                matchLength(env.getOrDefault("FORGE_SERVER_GAMES_PER_MATCH", "3")),
                number(env, "COMMANDER_BRACKET", 5, 1, 5), bool(env, "ENFORCE_DECK_LEGALITY", true));
        return new ServerConfig(port, adminPort, token, number(env, "MAX_PLAYERS", 4, 2, 8),
                number(env, "START_DELAY_SECONDS", 15, 1, 300),
                number(env, "RECONNECT_SECONDS", 300, 1, 3600),
                number(env, "POSTGAME_SECONDS", 120, 1, 3600), rules);
    }

    private static Mode mode(String value) {
        try { return Mode.valueOf(value.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("FORGE_SERVER_MODE must be one of " + java.util.Arrays.toString(Mode.values())); }
    }

    private static Set<Variant> variants(Map<String, String> env) {
        String value = env.getOrDefault("FORGE_SERVER_VARIANTS", "").trim();
        if (value.isEmpty()) { return Set.of(); }
        EnumSet<Variant> parsed = EnumSet.noneOf(Variant.class);
        for (String item : value.split(",")) {
            String normalized = item.trim().toUpperCase(Locale.ROOT);
            if (normalized.isEmpty()) { throw new IllegalArgumentException("FORGE_SERVER_VARIANTS contains an empty value"); }
            try {
                Variant variant = Variant.valueOf(normalized);
                if (!parsed.add(variant)) { throw new IllegalArgumentException("FORGE_SERVER_VARIANTS contains duplicate " + variant); }
            } catch (IllegalArgumentException e) {
                if (e.getMessage() != null && e.getMessage().startsWith("FORGE_SERVER_VARIANTS")) { throw e; }
                throw new IllegalArgumentException("FORGE_SERVER_VARIANTS has unsupported value " + item.trim());
            }
        }
        return Set.copyOf(parsed);
    }

    private static void validateCombination(Mode mode, Set<Variant> variants) {
        if (variants.contains(Variant.ARCHENEMY) && variants.contains(Variant.ARCHENEMY_RUMBLE)) {
            throw new IllegalArgumentException("FORGE_SERVER_VARIANTS cannot combine ARCHENEMY and ARCHENEMY_RUMBLE");
        }
        if ((mode == Mode.MOMIR_BASIC || mode == Mode.MOJHOSTO) && variants.contains(Variant.VANGUARD)) {
            throw new IllegalArgumentException("FORGE_SERVER_VARIANTS cannot combine VANGUARD with " + mode);
        }
    }

    private static int matchLength(String value) {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("FORGE_SERVER_GAMES_PER_MATCH must be one of [1, 3, 5]"); }
    }

    private static boolean bool(Map<String, String> env, String key, boolean fallback) {
        String value = env.getOrDefault("FORGE_SERVER_" + key, Boolean.toString(fallback));
        if ("true".equalsIgnoreCase(value)) { return true; }
        if ("false".equalsIgnoreCase(value)) { return false; }
        throw new IllegalArgumentException("FORGE_SERVER_" + key + " must be true or false");
    }

    private static int number(Map<String, String> env, String key, int fallback, int min, int max) {
        String fullKey = "FORGE_SERVER_" + key;
        final int value;
        try { value = Integer.parseInt(env.getOrDefault(fullKey, Integer.toString(fallback))); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(fullKey + " must be " + min + ".." + max); }
        if (value < min || value > max) { throw new IllegalArgumentException(fullKey + " must be " + min + ".." + max); }
        return value;
    }
}
