package forge.web;

import forge.gamemodes.net.server.ServerGameLobby;

import java.security.SecureRandom;
import java.util.HexFormat;

/** Starts the browser service inside a process that already owns Forge's lobby and GUI dispatcher. */
public final class EmbeddedWebServer implements AutoCloseable {
    private final WebHub hub;
    private final WebServer server;

    private EmbeddedWebServer(final ServerGameLobby lobby, final int port) throws InterruptedException {
        final WebGuiBase ui = new WebGuiBase(true);
        hub = new WebHub(ui, 0, () -> { }, lobby);
        try {
            server = new WebServer(hub, newToken(), port);
        } catch (RuntimeException | Error failure) {
            hub.shutdown();
            throw failure;
        }
        hub.setServer(server);
    }

    public static EmbeddedWebServer start(final ServerGameLobby lobby, final int port) throws InterruptedException {
        return new EmbeddedWebServer(lobby, port);
    }

    public int port() { return server.port(); }

    public String inviteUrl(final String host) { return server.inviteUrl(host); }

    @Override public void close() {
        hub.shutdown();
        server.close();
    }

    private static String newToken() {
        final byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
