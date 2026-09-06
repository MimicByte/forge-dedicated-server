package forge.server;

import forge.GuiDesktop;
import forge.gamemodes.match.HostedMatch;
import forge.gui.interfaces.IGuiGame;
import forge.localinstance.skin.FSkinProp;
import forge.localinstance.skin.ISkinImage;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/** Headless desktop services retain the real EDT, but never construct a local match UI. */
public final class DedicatedGui extends GuiDesktop {
    private final Path assets;
    private Consumer<HostedMatch> onMatch = match -> { };
    public DedicatedGui(Path assets) { this.assets = assets.toAbsolutePath(); }
    public void setOnMatch(Consumer<HostedMatch> onMatch) { this.onMatch = onMatch; }
    @Override public String getAssetsDir() { return assets + java.io.File.separator; }
    @Override public HostedMatch hostMatch() {
        HostedMatch match = new HostedMatch();
        onMatch.accept(match);
        return match;
    }
    @Override public IGuiGame getNewGuiGame() { throw new IllegalStateException("Dedicated server requested a local GUI"); }
    @Override public int showOptionDialog(String message, String title, FSkinProp icon, List<String> options, int defaultOption) {
        System.err.println("[server] Unsupported dialog: " + title + ": " + message);
        return -1;
    }
    @Override public void showImageDialog(ISkinImage image, String message, String title) {
        System.err.println("[server] " + title + ": " + message);
    }
    @Override public String showInputDialog(String message, String title, FSkinProp icon, String initial,
                                            List<String> options, boolean numeric) {
        throw new IllegalStateException("Unsupported dedicated input: " + title + ": " + message);
    }
    @Override public String showFileDialog(String title, String directory) { throw new IllegalStateException(title); }
    @Override public void showBugReportDialog(String title, String text, boolean exit) {
        System.err.println("[server] " + title + ": " + text);
    }
    @Override public forge.sound.IAudioClip createAudioClip(String filename) { return null; }
    @Override public forge.sound.IAudioMusic createAudioMusic(String filename) { return null; }
    @Override public void startAltSoundSystem(String filename, boolean synchronizedSound) { }
}
