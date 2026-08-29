package org.geysermc.geyser.network.portal.nethernet;

import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.cloudburstmc.protocol.bedrock.BedrockPeer;
import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.cloudburstmc.protocol.bedrock.netty.codec.packet.BedrockPacketCodec;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.network.InvalidPacketHandler;
import org.geysermc.geyser.network.UpstreamPacketHandler;
import org.geysermc.geyser.session.GeyserSession;

public final class GeyserNetherNetServerInitializer extends NetherNetBedrockChannelInitializer<BedrockServerSession> {
    private static final boolean PROXY_BRIDGE_DEBUG = Boolean.parseBoolean(System.getProperty("Geyser.ProxyBridgeDebug", "false"));

    private final GeyserImpl geyser;
    /** Shared across all shards; owned and shut down by PortalBridgeBootstrap. */
    private final EventLoopGroup playerGroup;

    public GeyserNetherNetServerInitializer(GeyserImpl geyser, EventLoopGroup playerGroup) {
        this.geyser = geyser;
        this.playerGroup = playerGroup;
    }

    @Override
    protected BedrockServerSession createSession0(BedrockPeer peer, int subClientId) {
        return new BedrockServerSession(peer, subClientId);
    }

    @Override
    protected void initSession(@NonNull BedrockServerSession bedrockServerSession) {
        try {
            if (PROXY_BRIDGE_DEBUG) {
                this.geyser.getLogger().info("[proxy-bridge] NetherNet peer connected");
                this.geyser.getLogger().info("[proxy-bridge] nethernet initSession remote=" + bedrockServerSession.getSocketAddress());
            }

            bedrockServerSession.setLogging(this.geyser.config().debugMode());
            GeyserSession session = new GeyserSession(this.geyser, bedrockServerSession, this.playerGroup.next());
            session.setProxyBridgeIngress(true);
            if (this.geyser.config().advanced().bedrock().portalBridge().debugLogging()) {
                this.geyser.getLogger().info("[proxy-bridge] NetherNet Bedrock session initialized remote="
                    + bedrockServerSession.getSocketAddress());
            }

            if (!bedrockServerSession.isSubClient()) {
                Channel channel = bedrockServerSession.getPeer().getChannel();
                channel.pipeline().addAfter(BedrockPacketCodec.NAME, InvalidPacketHandler.NAME, new InvalidPacketHandler(session));
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
