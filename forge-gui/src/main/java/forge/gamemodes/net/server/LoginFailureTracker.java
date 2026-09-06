package forge.gamemodes.net.server;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Bounded, single-dispatcher tracking of rejected dedicated-server logins. */
final class LoginFailureTracker {
    private static final int MAX_SOURCES = 1024;

    private final int limit;
    private final long windowMillis;
    private final long blockMillis;
    private final Map<String, Failure> failures = new HashMap<>();

    private static final class Failure {
        private long windowStartedAt;
        private int attempts;
        private long blockedUntil;

        Failure(long now) {
            windowStartedAt = now;
        }
    }

    LoginFailureTracker(int limit, int windowSeconds, int blockSeconds) {
        this.limit = limit;
        windowMillis = windowSeconds * 1000L;
        blockMillis = blockSeconds * 1000L;
    }

    boolean isBlocked(String source, long now) {
        Failure failure = failures.get(source);
        if (failure == null) { return false; }
        if (failure.blockedUntil > 0) {
            if (failure.blockedUntil > now) { return true; }
            failures.remove(source);
            return false;
        }
        if (now - failure.windowStartedAt >= windowMillis) { failures.remove(source); }
        return false;
    }

    void recordFailure(String source, long now) {
        removeExpired(now);
        Failure failure = failures.get(source);
        if (failure == null) {
            if (failures.size() >= MAX_SOURCES) { failures.remove(failures.keySet().iterator().next()); }
            failure = new Failure(now);
            failures.put(source, failure);
        } else if (now - failure.windowStartedAt >= windowMillis) {
            failure.windowStartedAt = now;
            failure.attempts = 0;
        }
        failure.attempts++;
        if (failure.attempts >= limit) { failure.blockedUntil = now + blockMillis; }
    }

    void clear(String source) {
        failures.remove(source);
    }

    private void removeExpired(long now) {
        Iterator<Map.Entry<String, Failure>> entries = failures.entrySet().iterator();
        while (entries.hasNext()) {
            Failure failure = entries.next().getValue();
            if (failure.blockedUntil <= now && now - failure.windowStartedAt >= windowMillis) {
                entries.remove();
            }
        }
    }
}
