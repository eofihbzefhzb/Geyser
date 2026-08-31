package org.geysermc.geyser.network.portal.nethernet;

import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.group.ChannelGroup;

import java.util.function.Supplier;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.cloudburstmc.protocol.bedrock.BedrockPeer;
import org.cloudburstmc.protocol.bedrock.BedrockServerSession;

import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.network.InvalidPacketHandler;
import org.geysermc.geyser.network.UpstreamPacketHandler;
import org.geysermc.geyser.session.GeyserSession;

public final class GeyserNetherNetServerInitializer extends NetherNetBedrockChannelInitializer<BedrockServerSession> {
    private final GeyserImpl geyser;
    /** Owned and shut down by PortalBridgeBootstrap. */
    private final EventLoopGroup playerGroup;
    /**
     * Supplies the group tracking peers of the CURRENT signaling generation.
     * <p>
     * A reload swaps in a new group, so peers accepted before it stay in the old one. That is what
     * lets the server know when a retired PeerConnectionFactory has no traffic left on it and can
     * be freed without pulling native memory out from under a live player.
     */
    private final Supplier<ChannelGroup> activeChildren;

    public GeyserNetherNetServerInitializer(GeyserImpl geyser, EventLoopGroup playerGroup,
                                            Supplier<ChannelGroup> activeChildren) {
        this.geyser = geyser;
        this.playerGroup = playerGroup;
        this.activeChildren = activeChildren;
    }

    @Override
    protected void postInitChannel(Channel channel) {
        // ChannelGroup removes a channel automatically once it closes, so this needs no teardown.
        this.activeChildren.get().add(channel);
    }

    @Override
    protected BedrockServerSession createSession0(BedrockPeer peer, int subClientId) {
        return new BedrockServerSession(peer, subClientId);
    }

    @Override
    protected void initSession(@NonNull BedrockServerSession bedrockServerSession) {
        try {
            bedrockServerSession.setLogging(this.geyser.config().debugMode());
            GeyserSession session = new GeyserSession(this.geyser, bedrockServerSession, this.playerGroup.next());
            session.setProxyBridgeIngress(true);
            if (this.geyser.config().advanced().bedrock().portalBridge().debugLogging()) {
                this.geyser.getLogger().info("[proxy-bridge] NetherNet Bedrock session initialized remote="
                    + bedrockServerSession.getSocketAddress());
            }

            if (!bedrockServerSession.isSubClient()) {
                Channel channel = bedrockServerSession.getPeer().getChannel();
                // After BedrockPeer, not BedrockPacketCodec: the peer is the last handler in the
                // pipeline, so exceptions thrown while it dispatches to the packet handler only
                // reach InvalidPacketHandler if it sits behind the peer.
                channel.pipeline().addAfter(BedrockPeer.NAME, InvalidPacketHandler.NAME, new InvalidPacketHandler(session));
                if (this.geyser.config().advanced().bedrock().portalBridge().debugLogging()) {
                    long openedAt = System.currentTimeMillis();
                    channel.closeFuture().addListener(future -> this.geyser.getLogger().info(
                        "[proxy-bridge] NetherNet Bedrock transport closed after "
                            + (System.currentTimeMillis() - openedAt) + "ms, spawned=" + session.isSpawned()
                            + (future.cause() != null ? ", cause: " + future.cause() : ", no error (peer went away)")));
                }
            }

            bedrockServerSession.setPacketHandler(new UpstreamPacketHandler(this.geyser, session));
        } catch (Throwable throwable) {
            this.geyser.getLogger().error("[proxy-bridge] NetherNet/Geyser session initialization failed: "
                + throwable.getClass().getSimpleName(), throwable);
            bedrockServerSession.disconnect(throwable.getMessage());
        }
    }
}
