package forge.web;

import com.google.gson.JsonObject;
import forge.StaticData;
import forge.game.player.Player;
import forge.gamemodes.match.HostedMatch;
import forge.gamemodes.net.server.RemoteClientGuiGame;
import forge.gui.GuiBase;
import forge.localinstance.properties.ForgeNetPreferences;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.PlayerControllerHuman;
import org.testng.Assert;
import org.testng.SkipException;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class WebTestSupport {
    private WebTestSupport() {}

    /** GuiBase must be set before ForgeConstants loads, because its static init reads the assets dir. */
    public static synchronized void initModel() {
        if (GuiBase.getInterface() == null) {
            GuiBase.setInterface(new WebGuiBase());
        }
        if (StaticData.instance() == null) {
            FModel.initialize(null, prefs -> {
                prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
                prefs.setPref(FPref.UI_LANGUAGE, "en-US");
                prefs.setPref(FPref.ENFORCE_DECK_LEGALITY, false);
                return null;
            }, false);
        }
        FModel.getNetPreferences().setPref(ForgeNetPreferences.FNetPref.UPnP, "NEVER");
    }

    /** The host's view of the web seat. Call {@code updateGameView()} on it after a test seeds cards, because seeding fires no event. */
    static RemoteClientGuiGame remoteGui(final HostedMatch match) {
        for (final Player p : match.getGame().getPlayers()) {
            if (p.getController() instanceof PlayerControllerHuman pch && pch.getGui() instanceof RemoteClientGuiGame r) {
                return r;
            }
        }
        throw new AssertionError("no remote human seat");
    }

    /** Checks that a browser model built from updates holds the same objects as a fresh snapshot. */
    static void assertSameIgnoringZone(final Map<Integer, JsonObject> incremental, final Map<Integer, JsonObject> fresh) {
        // The client rewrites CardView.Zone from zone collections, and that rewrite is not in the host's packets
        incremental.values().forEach(o -> o.remove("Zone"));
        fresh.values().forEach(o -> o.remove("Zone"));
        final Set<Integer> onlyIncremental = new HashSet<>(incremental.keySet());
        onlyIncremental.removeAll(fresh.keySet());
        final Set<Integer> onlyFresh = new HashSet<>(fresh.keySet());
        onlyFresh.removeAll(incremental.keySet());
        Assert.assertTrue(onlyIncremental.isEmpty() && onlyFresh.isEmpty(),
                "keys only in incremental " + onlyIncremental + ", only in fresh " + onlyFresh);
        for (final Map.Entry<Integer, JsonObject> e : fresh.entrySet()) {
            Assert.assertEquals(incremental.get(e.getKey()), e.getValue(), "object " + e.getKey());
        }
    }

    /** Skips a slow test unless -Drun.stress.tests=true is set. Forge's other network tests use the same switch. */
    static void skipUnlessStress() {
        if (!"true".equalsIgnoreCase(System.getProperty("run.stress.tests"))) {
            throw new SkipException("Slow test skipped. Use -Drun.stress.tests=true to run.");
        }
    }
}
