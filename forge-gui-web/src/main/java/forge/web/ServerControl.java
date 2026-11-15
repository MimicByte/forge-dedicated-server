package forge.web;

import forge.gamemodes.net.NetworkLogConfig;
import forge.gamemodes.net.server.PortForward;
import forge.localinstance.properties.ForgeNetPreferences.FNetPref;
import forge.model.FModel;
import forge.util.IHasForgeLog;
import forge.web.console.CloudflareTunnel;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.function.Consumer;

/** The web server, in a form the console can stop and start again. The link token is kept across a restart, so old links still work. */
public final class ServerControl implements IHasForgeLog {
    /** Set at launch to keep the server running after its browsers leave, for example when there is no console to change the option in. */
    public static final boolean KEEP_OPEN = Boolean.getBoolean("forge.web.keepOpen");
    private final WebGuiBase ui;
    private final long idleMillis;
    private final Runnable onQuit;
    /** True when a console is on screen. The console itself shows that Forge is running and waiting. */
    private final boolean consoleShown;
    private final String token;
    private WebServer server;
    private volatile WebHub sessions;
    private boolean quitWhenEmpty = !KEEP_OPEN;
    private volatile PortForward forward;
    private volatile Forwarding forwarding = Forwarding.OFF;
    private volatile Consumer<Forwarding> onForwarding = f -> { };
    private volatile CloudflareTunnel tunnel;
    private volatile CloudflareTunnel.State tunnelling = CloudflareTunnel.State.OFF;
    private volatile Consumer<CloudflareTunnel.State> onTunnel = t -> { };

    /** The state of the request to the router to forward the port. */
    public enum Forwarding { OFF, ASKING, FORWARDED, REFUSED }

    ServerControl(final WebGuiBase ui, final long idleMillis, final Runnable onQuit, final boolean consoleShown,
            final String token) {
        this.ui = ui;
        this.idleMillis = idleMillis;
        this.onQuit = onQuit;
        this.consoleShown = consoleShown;
        this.token = token;
    }

    public synchronized boolean running() {
        return server != null;
    }

    /** Binds the port. Does nothing if it is already bound. */
    public synchronized void start() throws InterruptedException {
        if (server != null) {
            return;
        }
        // One log for the whole time the port is bound, starting before the first table
        NetworkLogConfig.activateNetworkLogging();
        final WebHub fresh = new WebHub(ui, idleMillis, onQuit);
        final WebServer bound;
        try {
            bound = new WebServer(fresh, token);
        } catch (final Exception e) {
            // Otherwise the sessions' idle timer would quit Forge a few seconds after the port failed to bind
            fresh.shutdown();
            throw e;
        }
        fresh.setServer(bound);
        if (consoleShown) {
            fresh.visibleElsewhere();
        }
        fresh.quitWhenEmpty(quitWhenEmpty);
        sessions = fresh;
        server = bound;
        // The address carries the link token, and netLog also writes this line to the network log file
        netLog.info("Forge web UI: {}", bound.url());
        netLog.info("The web server has started on port {}.", bound.port());
        if (throughCloudflare()) {
            openTunnel();
        } else if (forwardPort()) {
            openForward();
        }
    }

    /** Closes the port and drops every seat. Does nothing if it is already stopped. */
    public synchronized void stop() {
        if (server == null) {
            return;
        }
        closeForward();
        closeTunnel();
        sessions.shutdown();
        server.close();
        sessions = null;
        server = null;
        netLog.info("The web server has stopped.");
        NetworkLogConfig.deactivateNetworkLogging();
    }

    public synchronized void quitWhenEmpty(final boolean value) {
        quitWhenEmpty = value;
        if (sessions != null) {
            sessions.quitWhenEmpty(value);
        }
    }

    /** Whether the router is asked to forward the port whenever the server starts. This is desktop's UPnP preference, and only ALWAYS forwards because the console cannot ask the host each time. */
    public boolean forwardPort() {
        return "ALWAYS".equalsIgnoreCase(FModel.getNetPreferences().getPref(FNetPref.UPnP));
    }

    public synchronized void forwardPort(final boolean value) {
        FModel.getNetPreferences().setPref(FNetPref.UPnP, value ? "ALWAYS" : "NEVER");
        FModel.getNetPreferences().save();
        if (!value) {
            closeForward();
        } else if (server != null) {
            openForward();
        } else {
            onForwarding.accept(forwarding);
        }
    }

    /** The listener is called at once with the current state and then on every change, on the caller's thread except for the router's answer, which arrives on a UPnP thread. */
    public synchronized void onForwarding(final Consumer<Forwarding> listener) {
        onForwarding = listener;
        listener.accept(forwarding);
    }

    public Forwarding forwarding() {
        return forwarding;
    }

    private void openForward() {
        if (forward != null) {
            forward.close();
        }
        final int port = server.port();
        final PortForward asking = new PortForward(port);
        forward = asking;
        setForwarding(Forwarding.ASKING);
        // The answer takes no lock. It arrives on a UPnP thread, and stop() may wait for that thread while holding this object's lock
        asking.open(accepted -> {
            if (forward == asking) {
                if (accepted) {
                    netLog.info("The router is forwarding port {}.", port);
                } else {
                    netLog.warn("The router did not forward port {}. Players on the internet cannot join until it is forwarded.", port);
                }
                setForwarding(accepted ? Forwarding.FORWARDED : Forwarding.REFUSED);
            }
        });
    }

    private void closeForward() {
        if (forward != null) {
            forward.close();
            forward = null;
        }
        setForwarding(Forwarding.OFF);
    }

    /** Whether a browser on another machine must give a password to take the host's seat. */
    public static boolean hostPasswordSet() {
        return !hostPassword().isEmpty();
    }

    /** The comparison takes constant time, so the time a guess takes reveals nothing about the password. */
    static boolean hostPasswordMatches(final String given) {
        return given != null && MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), hostPassword().getBytes(StandardCharsets.UTF_8));
    }

    public static String hostPassword() {
        return FModel.getNetPreferences().getPref(FNetPref.WEB_HOST_PASSWORD);
    }

    /** Kept between runs. An empty password lets anyone with the link take the host's seat. */
    public static void hostPassword(final String value) {
        FModel.getNetPreferences().setPref(FNetPref.WEB_HOST_PASSWORD, value);
        FModel.getNetPreferences().save();
    }

    /** Whether players on the internet connect through Cloudflare, with no change to the router. Kept between runs. */
    public boolean throughCloudflare() {
        return FModel.getNetPreferences().getPrefBoolean(FNetPref.WEB_CLOUDFLARE);
    }

    /** Only one way in is open at a time, so choosing one closes the other. The port forwarding choice is kept for when Cloudflare is turned off. */
    public synchronized void throughCloudflare(final boolean value) {
        FModel.getNetPreferences().setPref(FNetPref.WEB_CLOUDFLARE, String.valueOf(value));
        FModel.getNetPreferences().save();
        if (value) {
            closeForward();
            if (server != null) {
                openTunnel();
            }
        } else {
            closeTunnel();
            if (server != null && forwardPort()) {
                openForward();
            }
        }
    }

    /** The listener is called on every change in the Cloudflare connection's state, on the thread that runs the connection. */
    public synchronized void onTunnel(final Consumer<CloudflareTunnel.State> listener) {
        onTunnel = listener;
        listener.accept(tunnelling);
    }

    /** The host has chosen where Cloudflare's program is. A tunnel still searching for the program looks there. Kept between runs. */
    public void cloudflaredAt(final File program) {
        FModel.getNetPreferences().setPref(FNetPref.WEB_CLOUDFLARED_PATH, program.getAbsolutePath());
        FModel.getNetPreferences().save();
        final CloudflareTunnel waiting = tunnel;
        if (waiting != null) {
            waiting.lookIn(program);
        }
    }

    /** A link for another player through Cloudflare, or null until it is connected. */
    public synchronized String tunnelUrl() {
        final CloudflareTunnel open = tunnel;
        final String address = open == null ? null : open.address();
        return server == null || address == null ? null : server.inviteUrlThrough(address);
    }

    private void openTunnel() {
        if (tunnel != null) {
            tunnel.close();
        }
        final String path = FModel.getNetPreferences().getPref(FNetPref.WEB_CLOUDFLARED_PATH);
        final CloudflareTunnel[] opening = new CloudflareTunnel[1];
        // The answer takes no lock, for the same reason as the router's answer
        opening[0] = new CloudflareTunnel(server.port(), path.isEmpty() ? null : new File(path), state -> {
            if (tunnel == opening[0]) {
                setTunnelling(state);
            }
        });
        tunnel = opening[0];
        opening[0].open();
    }

    private void closeTunnel() {
        if (tunnel != null) {
            tunnel.close();
            tunnel = null;
        }
        setTunnelling(CloudflareTunnel.State.OFF);
    }

    private void setTunnelling(final CloudflareTunnel.State value) {
        tunnelling = value;
        final WebHub now = sessions;
        final CloudflareTunnel open = tunnel;
        if (now != null) {
            now.throughCloudflare(value == CloudflareTunnel.State.CONNECTED && open != null ? open.address() : null);
        }
        onTunnel.accept(value);
    }

    private void setForwarding(final Forwarding value) {
        forwarding = value;
        if (sessions != null) {
            sessions.portForwarded(value == Forwarding.FORWARDED);
        }
        onForwarding.accept(value);
    }

    /** The host's own link, or null while stopped. */
    public synchronized String url() {
        return server == null ? null : server.url();
    }

    /** A link for another player at one of this machine's addresses, or null while stopped. */
    public synchronized String inviteUrl(final String address) {
        return server == null ? null : server.inviteUrl(address);
    }

    /** The bytes through the port since it was last started, or null while stopped. */
    public synchronized ServerTraffic traffic() {
        return server == null ? null : server.traffic();
    }

    /** Takes no lock, so the console can call it on the event thread while a stop is in progress. */
    public int playersHere() {
        final WebHub now = sessions;
        return now == null ? 0 : now.playersHere();
    }

    /** The port the browser connects on. While stopped, the port that the next start will use. */
    public synchronized int port() {
        return server == null ? WebServer.configuredPort() : server.port();
    }
}
