package forge.server;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Writes server failures to the persistent configuration volume. */
public final class DedicatedCrashReporter {
    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
            .withZone(ZoneOffset.UTC);
    private static final AtomicBoolean REPORTING_UNCAUGHT_EXCEPTION = new AtomicBoolean();
    private static Path reportDirectory;
    private static int maxReports;
    private static Supplier<String> context = () -> "";

    private DedicatedCrashReporter() { }

    static void install(Path configDirectory, int maximumReports, Supplier<String> reportContext) {
        reportDirectory = configDirectory.resolve("crash-reports");
        maxReports = maximumReports;
        context = reportContext;
        try { Files.createDirectories(reportDirectory); }
        catch (Exception error) { System.err.println("[server] Could not create crash report directory: " + error); }

        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            if (REPORTING_UNCAUGHT_EXCEPTION.compareAndSet(false, true)) {
                try { report(error, "Uncaught exception on thread " + thread.getName()); }
                finally { REPORTING_UNCAUGHT_EXCEPTION.set(false); }
            }
            if (previous != null) { previous.uncaughtException(thread, error); }
            else { error.printStackTrace(); }
        });
    }

    static void report(Throwable error, String reason) {
        if (reportDirectory == null) { return; }
        try {
            Path report = writeReport(reportDirectory, maxReports, safeContext(), error, reason);
            System.err.println("[server] Crash report written to " + report);
        } catch (Throwable writeFailure) {
            System.err.println("[server] Could not write crash report: " + writeFailure);
        }
    }

    static Path writeReport(Path directory, int maximumReports, String reportContext, Throwable error, String reason)
            throws java.io.IOException {
        Files.createDirectories(directory);
        Path report = nextReportPath(directory);
        StringWriter trace = new StringWriter();
        error.printStackTrace(new PrintWriter(trace));
        String content = "Forge Dedicated Server crash report\n"
                + "timestamp_utc=" + Instant.now() + "\n"
                + "reason=" + reason + "\n"
                + "thread=" + Thread.currentThread().getName() + "\n"
                + reportContext + "\n\n"
                + trace;
        Files.writeString(report, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        pruneReports(directory, maximumReports);
        return report;
    }

    private static Path nextReportPath(Path directory) {
        String stem = "dedicated-crash-" + FILE_TIMESTAMP.format(Instant.now());
        for (int suffix = 0; ; suffix++) {
            Path candidate = directory.resolve(stem + (suffix == 0 ? "" : "-" + suffix) + ".log");
            if (!Files.exists(candidate)) { return candidate; }
        }
    }

    private static String safeContext() {
        try { return context.get(); }
        catch (Throwable ignored) { return "context=unavailable"; }
    }

    private static void pruneReports(Path directory, int maximumReports) {
        try (var reports = Files.list(directory)) {
            Path[] oldReports = reports.filter(path -> path.getFileName().toString().startsWith("dedicated-crash-")
                            && path.getFileName().toString().endsWith(".log"))
                    .sorted(Comparator.comparingLong(path -> path.toFile().lastModified()))
                    .toArray(Path[]::new);
            for (int index = 0; index < oldReports.length - maximumReports; index++) { Files.deleteIfExists(oldReports[index]); }
        } catch (Exception ignored) {
            // Reporting must not fail because cleanup did.
        }
    }
}
