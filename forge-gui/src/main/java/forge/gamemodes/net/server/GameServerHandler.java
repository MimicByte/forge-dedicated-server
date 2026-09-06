package forge.gamemodes.net.server;

import forge.gamemodes.net.GameProtocolHandler;
import forge.gamemodes.match.YieldUpdate;
import forge.util.IHasForgeLog;
import forge.gamemodes.net.IRemote;
import forge.gamemodes.net.ProtocolMethod;
import forge.gamemodes.net.ReplyPool;
import forge.gui.interfaces.IGuiGame;
import forge.interfaces.IGameController;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelId;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

final class GameServerHandler extends GameProtocolHandler<IGameController> implements IHasForgeLog {

    private static final int YIELD_SEED_RETRY_LIMIT = 100;
    private static final long YIELD_SEED_RETRY_DELAY_MS = 25L;

    private final FServerManager server = FServerManager.getInstance();
    private final Map<ChannelId, PendingYieldSeed> pendingYieldSeeds = new ConcurrentHashMap<>();

    private record PendingYieldSeed(YieldUpdate update, int attempts) { }

    GameServerHandler() {
        super(false);
    }

    private RemoteClient getClient(final ChannelHandlerContext ctx) {
        return server.getClient(ctx.channel());
    }

    @Override
    protected ReplyPool getReplyPool(final ChannelHandlerContext ctx) {
        return getClient(ctx).getReplyPool();
    }

    @Override
    protected IRemote getRemote(final ChannelHandlerContext ctx) {
        return getClient(ctx);
    }

    @Override
    protected IGameController getToInvoke(final ChannelHandlerContext ctx) {
        final RemoteClient client = getClient(ctx);
        return client != null ? server.getController(client.getIndex()) : null;
    }

    @Override
    protected boolean deferWhenTargetUnavailable(final ChannelHandlerContext ctx,
            final ProtocolMethod protocolMethod, final Object[] args) {
        if (protocolMethod != ProtocolMethod.sendYieldUpdate || args.length != 1
                || !(args[0] instanceof YieldUpdate.SeedFromClient seed)) {
            return false;
        }
        final ChannelId channelId = ctx.channel().id();
        pendingYieldSeeds.put(channelId, new PendingYieldSeed(seed, 0));
        retryYieldSeed(ctx, channelId);
        return true;
    }

    private void retryYieldSeed(final ChannelHandlerContext ctx, final ChannelId channelId) {
        ctx.executor().schedule(() -> {
            final PendingYieldSeed pending = pendingYieldSeeds.get(channelId);
            if (pending == null || !ctx.channel().isActive()) {
                pendingYieldSeeds.remove(channelId);
                return;
            }
            final IGameController controller = getToInvoke(ctx);
            if (controller != null) {
                pendingYieldSeeds.remove(channelId);
                forge.gui.FThreads.invokeInBackgroundThread(() -> controller.sendYieldUpdate(pending.update()));
                netLog.info("Applied deferred yield-state seed for client {}", channelId.asShortText());
            } else if (pending.attempts() >= YIELD_SEED_RETRY_LIMIT) {
                pendingYieldSeeds.remove(channelId);
                netLog.warn("Discarded yield-state seed for {} after controller was unavailable for {} ms",
                        channelId.asShortText(), YIELD_SEED_RETRY_LIMIT * YIELD_SEED_RETRY_DELAY_MS);
            } else {
                pendingYieldSeeds.replace(channelId, pending,
                        new PendingYieldSeed(pending.update(), pending.attempts() + 1));
                retryYieldSeed(ctx, channelId);
            }
        }, YIELD_SEED_RETRY_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    @Override
    protected void beforeCall(final ChannelHandlerContext ctx, final ProtocolMethod protocolMethod, final Object[] args) {
        if (protocolMethod == ProtocolMethod.requestResync) {
            RemoteClient client = getClient(ctx);
            if (client != null) {
                IGuiGame gui = server.getGui(client.getIndex());
                if (gui instanceof RemoteClientGuiGame netGui) {
                    netLog.debug("[DeltaSync] Resync requested by client {}, deferring to game thread", client.getIndex());
                    netGui.setResyncPending();
                } else {
                    netLog.warn("[DeltaSync] GUI is not RemoteClientGuiGame, cannot resync client {}", client.getIndex());
                }
            }
        }
    }

    @Override
    public void channelInactive(final ChannelHandlerContext ctx) throws Exception {
        pendingYieldSeeds.remove(ctx.channel().id());
        super.channelInactive(ctx);
    }

}
