package forge.web.images;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;

public class WebImageFetcherTest {
    @Test
    public void atomicWriteReplacesTargetWithCompleteBytes() throws Exception {
        final Path directory = Files.createTempDirectory("web-image-test");
        final Path target = directory.resolve("card.jpg");
        Files.write(target, new byte[] {1, 2, 3});

        WebImageFetcher.atomicWrite(target, new byte[] {4, 5, 6, 7});

        Assert.assertEquals(Files.readAllBytes(target), new byte[] {4, 5, 6, 7}, "target should contain the complete replacement");
        try (java.util.stream.Stream<Path> files = Files.list(directory)) {
            Assert.assertEquals(1, files.count());
        }
    }
}
