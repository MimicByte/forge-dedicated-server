package forge.gamemodes.net.server;

import forge.game.GameType;
import forge.gamemodes.match.LobbySlot;
import forge.gamemodes.match.LobbySlotType;
import forge.gamemodes.net.event.LoginEvent;
import forge.gamemodes.net.event.MessageEvent;
import forge.gamemodes.net.event.UpdateLobbyPlayerEvent;
import forge.util.BuildInfo;
import forge.util.IHasForgeLog;
import forge.util.LogSafe;
import io.netty.channel.ChannelHandlerContext;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import javax.swing.SwingUtilities;

/** Dedicated-only connection and lobby rules, separated from shared Netty transport ownership. */
final class DedicatedServerSession implements IHasForgeLog {
    private final FServerManager server;
    private final DedicatedServerPolicy policy;
    private final LoginFailureTracker failedLogins;

    DedicatedServerSession(FServerManager server, DedicatedServerPolicy policy) {
        this.server = server;
        this.policy = policy;
        failedLogins = new LoginFailureTracker(policy.loginFailureLimit(),
                policy.loginFailureWindowSeconds(), policy.loginBlockSeconds());
    }

    void handleLogin(ChannelHandlerContext context, RemoteClient client, LoginEvent event) {
        onDispatcher(() -> login(context, client, event));
    }

    void handleLobbyUpdate(RemoteClient client, UpdateLobbyPlayerEvent event) {
        onDispatcher(() -> updateLobby(client, event));
    }

    void handleDisconnect(RemoteClient client) {
        SwingUtilities.invokeLater(() -> {
            if (!client.hasValidSlot() || server.isDedicatedShuttingDown()) { return; }
            if (policy.wasKicked(client)) {
                server.getLocalLobby().disconnectPlayer(client.getIndex());
                policy.connectionsChanged();
                return;
            }
            if (!policy.acceptsLobbyChanges()) {
                server.pauseRemoteClientGuiGame(client);
                server.parkDisconnectedClient(client);
            } else {
                server.getLocalLobby().disconnectPlayer(client.getIndex());
            }
            policy.disconnected(client);
            policy.connectionsChanged();
        });
    }

    private void login(ChannelHandlerContext context, RemoteClient client, LoginEvent event) {
        String name = LogSafe.forDisplay(event.getUsername(), server.maxDedicatedNameLength());
        if (client == null || client.hasValidSlot() || name == null || name.isBlank() || event.isLibgdx()
                || server.connectedPlayers().stream().anyMatch(c -> name.equalsIgnoreCase(c.getUsername()))) {
            if (client != null) {
                client.send(MessageEvent.warning("Login rejected: use a desktop Forge client and a unique name."));
            }
            context.close();
            return;
        }
        String source = sourceOf(context.channel().remoteAddress());
        boolean allowed = policy.allowsPlayer(name);
        long now = System.currentTimeMillis();
        boolean blocked = failedLogins.isBlocked(source, now);
        if (blocked || !allowed) {
            if (!blocked && !allowed) { failedLogins.recordFailure(source, now); }
            rejectPrivateLogin(context, client);
            return;
        }
        warnOnVersionMismatch(client, name, event.getVersion());

        RemoteClient parked = server.takeParkedDedicatedClient(name);
        if (parked != null) {
            server.replaceClientChannel(context, parked);
            server.updateLobbyState();
            policy.reconnected(parked);
            server.resumeAndResync(parked);
            server.broadcast(new MessageEvent(name + " reconnected."));
        } else {
            if (!policy.acceptsNewPlayers()) {
                client.send(MessageEvent.warning("A match is in progress. Join again when it ends."));
                context.close();
                return;
            }
            client.setUsername(name);
            int index = server.getLocalLobby().connectPlayer(name, event.getAvatarIndex(), event.getSleeveIndex());
            if (index < 0) {
                context.close();
                return;
            }
            client.setIndex(index);
            client.setLibgdx(false);
            server.broadcast(new MessageEvent(name + " joined the lobby."));
            server.updateLobbyState();
        }
        failedLogins.clear(source);
        policy.connectionsChanged();
    }

    private void rejectPrivateLogin(ChannelHandlerContext context, RemoteClient client) {
        client.send(MessageEvent.warning("Login rejected: this is a private server."));
        context.close();
    }

    private static String sourceOf(SocketAddress address) {
        if (address instanceof InetSocketAddress inet) {
            InetAddress host = inet.getAddress();
            return host == null ? inet.getHostString() : host.getHostAddress();
        }
        return String.valueOf(address);
    }

    private void warnOnVersionMismatch(RemoteClient client, String name, String clientVersion) {
        String hostVersion = BuildInfo.getVersionString();
        if (java.util.Objects.equals(hostVersion, clientVersion)) { return; }
        String reportedVersion = clientVersion == null ? "unknown" : clientVersion;
        netLog.warn("[Dedicated] {} joined with Forge version {} (server: {})", name, reportedVersion, hostVersion);
        client.send(MessageEvent.warning(String.format(
                "Warning: You are using Forge version %s (server: %s). Network compatibility is not guaranteed.",
                reportedVersion, hostVersion)));
    }

    private void updateLobby(RemoteClient client, UpdateLobbyPlayerEvent event) {
        if (client == null || !client.hasValidSlot() || !policy.acceptsLobbyChanges()) { return; }
        event.clearServerOwnedFields();
        event.setName(null); // Names and teams stay fixed for the connection lifetime.
        ServerGameLobby lobby = server.getLocalLobby();
        LobbySlot slot = lobby.getSlot(client.getIndex());
        validateArchenemyChoice(client, lobby, event, slot);
        boolean archenemyChanged = event.getArchenemy() != null && slot.isArchenemy() != event.getArchenemy();
        boolean deckChanged = event.getDeck() != null || event.getSection() != null
                || event.getCards() != null || archenemyChanged;
        lobby.applyToSlot(client.getIndex(), event);
        if (Boolean.FALSE.equals(event.getArchenemy())) {
            for (int i = 0; i < lobby.getNumberOfSlots(); i++) { lobby.getSlot(i).setIsArchenemy(false); }
        }
        slot.setTeam(client.getIndex());
        slot.setIsDevMode(false);
        if (deckChanged) { slot.setIsReady(false); }
        lobby.applyToSlot(client.getIndex(), UpdateLobbyPlayerEvent.isReadyUpdate(slot.isReady()));
        server.updateLobbyState();
        policy.connectionsChanged();
    }

    private void validateArchenemyChoice(RemoteClient client, ServerGameLobby lobby,
            UpdateLobbyPlayerEvent event, LobbySlot slot) {
        if (event.getArchenemy() != null && !lobby.hasVariant(GameType.Archenemy)) {
            event.setArchenemy(null);
            return;
        }
        if (!Boolean.TRUE.equals(event.getArchenemy()) || slot.isArchenemy()) { return; }
        for (int i = 0; i < lobby.getNumberOfSlots(); i++) {
            LobbySlot other = lobby.getSlot(i);
            if (i != client.getIndex() && other.getType() != LobbySlotType.OPEN && other.isArchenemy()) {
                client.send(MessageEvent.warning("Another player has already nominated themselves as the Archenemy."));
                event.setArchenemy(null);
                return;
            }
        }
    }

    private static void onDispatcher(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(action);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new IllegalStateException("Dedicated lobby dispatch failed", e.getCause());
        }
    }
}
