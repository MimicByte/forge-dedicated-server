package forge.server;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.testng.Assert;
import org.testng.annotations.Test;

public class DedicatedCrashReporterTest {
    @Test public void writesSanitizedReportAndPrunesOlderReports() throws Exception {
        Path directory = Files.createTempDirectory("forge-crash-reporter-test");
        try {
            Path first = DedicatedCrashReporter.writeReport(directory, 1, "mode=COMMANDER",
                    new IllegalStateException("first failure"), "First failure");
            Path second = DedicatedCrashReporter.writeReport(directory, 1, "mode=COMMANDER",
                    new IllegalArgumentException("second failure"), "Second failure");

            Assert.assertFalse(Files.exists(first));
            Assert.assertTrue(Files.exists(second));
            String report = Files.readString(second);
            Assert.assertTrue(report.contains("reason=Second failure"));
            Assert.assertTrue(report.contains("mode=COMMANDER"));
            Assert.assertTrue(report.contains("IllegalArgumentException: second failure"));
        } finally {
            try (var paths = Files.walk(directory)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }
}
