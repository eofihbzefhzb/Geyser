package org.geysermc.geyser.network.portal.nethernet;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kastle.netty.channel.nethernet.NetherNetChannelFactory;
import dev.kastle.netty.channel.nethernet.signaling.NetherNetServerSignaling;
import dev.kastle.netty.channel.nethernet.signaling.NetherNetSignaling;
import dev.kastle.netty.channel.nethernet.signaling.NetherNetXboxRpcSignaling;
import dev.kastle.webrtc.PeerConnectionFactory;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.configuration.PortalBridgeConfig;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class PortalNetherNetServer implements AutoCloseable {
    private final GeyserImpl geyser;
    private final PortalBridgeConfig config;
    private final String authHeaderFile;
    private final EventLoopGroup bossGroup = new NioEventLoopGroup(1, new DefaultThreadFactory("GeyserNetherNetBoss", true));
    private final EventLoopGroup workerGroup = new NioEventLoopGroup(0, new DefaultThreadFactory("GeyserNetherNetChild", true));
    private final GeyserNetherNetServerInitializer initializer;
    private PeerConnectionFactory peerConnectionFactory;
    private NetherNetServerSignaling signaling;
    private final String configuredNetworkId;
    private Channel channel;

    public PortalNetherNetServer(GeyserImpl geyser, PortalBridgeConfig config, String authHeaderFile, String configuredNetworkId) {
        this.geyser = geyser;
        this.config = config;
        this.authHeaderFile = authHeaderFile == null ? "" : authHeaderFile;
        this.configuredNetworkId = configuredNetworkId == null ? "" : configuredNetworkId;
        this.initializer = new GeyserNetherNetServerInitializer(geyser);
        this.peerConnectionFactory = new PeerConnectionFactory();
        NetherNetXboxRpcSignaling rawSignaling = createSignaling(geyser, config, this.authHeaderFile, this.configuredNetworkId);
        this.signaling = config.debugLogging()
            ? new TracingServerSignaling(geyser, rawSignaling)
            : rawSignaling;
    }

    private static NetherNetXboxRpcSignaling createSignaling(GeyserImpl geyser, PortalBridgeConfig config, String authHeaderFile, String configuredNetworkId) {
        String authHeader = resolveAuthHeader(geyser, config, authHeaderFile);
        if (!configuredNetworkId.isBlank()) {
            return new NetherNetXboxRpcSignaling(configuredNetworkId, authHeader);
        }
        return new NetherNetXboxRpcSignaling(authHeader);
    }

    // authHeaderFile is the per-shard cache file (e.g. cache_Jimmy.json for a sub-session).
    // Each shard MUST resolve its own file here - reading config.xboxAuthHeaderFile() (the
    // single primary-account fallback) for every shard means every sub-session tries to
    // authenticate as the primary account, or with no header at all if only the
    // xbox-auth-header-files list is configured, both of which Xbox rejects with 401.
    private static String resolveAuthHeader(GeyserImpl geyser, PortalBridgeConfig config, String authHeaderFile) {
        if (!config.xboxAuthHeader().isBlank()) {
            return config.xboxAuthHeader();
        }

        String targetFile = !authHeaderFile.isBlank() ? authHeaderFile : config.xboxAuthHeaderFile();
        if (targetFile.isBlank()) {
            return "";
        }

        // The publisher (e.g. MCXboxBroadcast) may still be finishing its own auth flow
        // for this account/sub-session when Geyser starts, so the cache file this shard
        // needs might not exist, be fully written, or have reached the minecraftSession
        // step of that auth flow yet. Retry for up to a minute instead of giving up on
        // the very first read, and report exactly which of those is the case so a
        // permanent failure (as opposed to a startup race) is diagnosable from the log
        // instead of a bare "no valid header" message.
        int maxRetries = 30;
        String lastFailureReason = "unknown";
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                Path path = Path.of(targetFile);
                if (!Files.isRegularFile(path)) {
                    lastFailureReason = "file does not exist yet";
                } else {
                    String json = Files.readString(path);
                    if (json.isBlank()) {
                        lastFailureReason = "file exists but is empty";
                    } else {
                        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
                        JsonObject minecraftSession = root.getAsJsonObject("minecraftSession");
                        if (minecraftSession == null) {
                            // This is the common "stuck forever" case, not a startup race: the
                            // publisher's own auth chain (xboxLiveXstsToken -> playFabToken ->
                            // profile -> minecraftSession) saved a partial cache after an earlier
                            // step but hasn't successfully completed the minecraftSession step for
                            // this account. Retrying here will never fix that; the publisher needs
                            // to successfully refresh that account's Xbox auth first.
                            lastFailureReason = "cache file has no \"minecraftSession\" entry yet - "
                                + "the publisher's auth flow for this account hasn't reached that step";
                        } else if (!minecraftSession.has("authorizationHeader")
                                || minecraftSession.get("authorizationHeader").getAsString().isBlank()) {
                            lastFailureReason = "cache file's \"minecraftSession\" entry has no authorizationHeader value";
                        } else {
                            String header = minecraftSession.get("authorizationHeader").getAsString();
                            if (attempt > 1) {
                                geyser.getLogger().info("[proxy-bridge] Successfully loaded auth header from " + targetFile + " after " + attempt + " attempts.");
                            }
                            return header;
                        }
                    }
                }
            } catch (Exception exception) {
                lastFailureReason = "error reading/parsing file: " + exception;
            }

            if (attempt < maxRetries) {
                if (attempt == 1) {
                    geyser.getLogger().info("[proxy-bridge] Waiting for auth cache file to be ready: " + targetFile + " ...");
                }
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        geyser.getLogger().warning("[proxy-bridge] Failed to read a valid Xbox auth header from " + targetFile
            + " after 60 seconds. Last reason: " + lastFailureReason);
        return "";
    }

    /**
     * Returns a non-secret fingerprint of the effective Xbox auth header. This is
     * intentionally the only value exposed to the reload watcher.
     */
    public static String authHeaderFingerprint(GeyserImpl geyser, PortalBridgeConfig config, String authHeaderFile) {
        String authHeader = resolveAuthHeader(geyser, config, authHeaderFile);
        if (authHeader.isBlank()) {
            return "";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(authHeader.getBytes(StandardCharsets.UTF_8));
            StringBuilder fingerprint = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                fingerprint.append(String.format("%02x", value));
            }
            return fingerprint.toString();
        } catch (NoSuchAlgorithmException exception) {
            // SHA-256 is required by every Java runtime, but do not expose the
            // header if a non-conforming runtime is used.
            return Integer.toHexString(authHeader.hashCode());
        }
    }

    public void start() {
        ServerBootstrap bootstrap = new ServerBootstrap()
            .group(this.bossGroup, this.workerGroup)
            .channelFactory(NetherNetChannelFactory.server(this.peerConnectionFactory, this.signaling))
            .childOption(ChannelOption.TCP_NODELAY, false)
            .childHandler(this.initializer);

        this.channel = bootstrap.bind(new InetSocketAddress(0)).syncUninterruptibly().channel();
        this.geyser.getLogger().info("[proxy-bridge] NetherNet ingress started with network ID " + this.signaling.getLocalNetworkId());
        if (config.debugLogging()) {
            this.geyser.getLogger().info("[proxy-bridge] NetherNet control channel bound through Xbox signaling and local server bootstrap.");
        }
    }

    /**
     * Recreates only the signaling channel used to accept new NetherNet
     * connections, using a fresh Xbox auth header. {@code bossGroup},
     * {@code workerGroup}, and every already-established player channel
     * are left untouched, so calling this does not disconnect anyone who
     * is already connected. Use this instead of {@link #close()} +
     * re-construction whenever only the auth header has changed.
     */
    public synchronized void reloadSignaling() {
        Channel oldChannel = this.channel;
        NetherNetServerSignaling oldSignaling = this.signaling;
        PeerConnectionFactory oldPeerConnectionFactory = this.peerConnectionFactory;

        // Keep the currently active network ID across an auth-refresh reload, even
        // when no explicit id was configured. Regenerating a random id here would
        // silently change the identity that MCXboxBroadcast already published to
        // Xbox and that in-flight joins are using, on every token refresh.
        String reloadNetworkId = !this.configuredNetworkId.isBlank()
            ? this.configuredNetworkId
            : this.signaling.getLocalNetworkId();
        NetherNetXboxRpcSignaling rawSignaling = createSignaling(this.geyser, this.config, this.authHeaderFile, reloadNetworkId);
        NetherNetServerSignaling newSignaling = config.debugLogging()
            ? new TracingServerSignaling(this.geyser, rawSignaling)
            : rawSignaling;

        // Use a dedicated PeerConnectionFactory for the new channel instead of the
        // currently-live instance. If the bind below fails, Netty/the native layer
        // tears down the failed channel and, with it, whatever PeerConnectionFactory
        // it was constructed with. Handing it the still-in-use factory would take
        // down every already-connected/-connecting peer along with the failed
        // reload attempt.
        PeerConnectionFactory newPeerConnectionFactory = new PeerConnectionFactory();

        ServerBootstrap bootstrap = new ServerBootstrap()
            .group(this.bossGroup, this.workerGroup)
            .channelFactory(NetherNetChannelFactory.server(newPeerConnectionFactory, newSignaling))
            .childOption(ChannelOption.TCP_NODELAY, false)
            .childHandler(this.initializer);

        Channel newChannel;
        try {
            newChannel = bootstrap.bind(new InetSocketAddress(0)).syncUninterruptibly().channel();
        } catch (RuntimeException exception) {
            // Keep serving on the old signaling channel/factory if the new one fails
            // to bind; do not tear down anything that was previously working.
            newSignaling.close();
            disposeQuietly(newPeerConnectionFactory);
            throw exception;
        }

        this.channel = newChannel;
        this.signaling = newSignaling;
        this.peerConnectionFactory = newPeerConnectionFactory;

        if (oldChannel != null) {
            oldChannel.close().syncUninterruptibly();
        }
        oldSignaling.close();
        disposeQuietly(oldPeerConnectionFactory);

        this.geyser.getLogger().info("[proxy-bridge] NetherNet signaling reloaded for " + this.authHeaderFile + ", new network ID " + this.signaling.getLocalNetworkId());
    }

    private static void disposeQuietly(PeerConnectionFactory factory) {
        try {
            factory.dispose();
        } catch (NullPointerException ignored) {
            // The native handle was never fully initialized.
        }
    }

    @Override
    public void close() {
        if (this.channel != null) {
            this.channel.close().syncUninterruptibly();
            this.channel = null;
        }
        this.signaling.close();
        this.initializer.close();
        this.workerGroup.shutdownGracefully();
        this.bossGroup.shutdownGracefully();
        disposeQuietly(this.peerConnectionFactory);
    }

    public String networkId() {
        return this.signaling.getLocalNetworkId();
    }

    /**
     * Adds stage-only diagnostics around the library signaling callbacks. Signal
     * bodies are deliberately never logged because they contain SDP/candidates.
     */
    private static final class TracingServerSignaling implements NetherNetServerSignaling {
        private final GeyserImpl geyser;
        private final NetherNetServerSignaling delegate;

        private TracingServerSignaling(GeyserImpl geyser, NetherNetServerSignaling delegate) {
            this.geyser = geyser;
            this.delegate = delegate;
        }

        @Override
        public void bind(java.net.SocketAddress address) throws java.net.ConnectException {
            delegate.bind(address);
            geyser.getLogger().info("[proxy-bridge] NetherNet signaling websocket connected.");
        }

        @Override
        public void setNewConnectionHandler(NewConnectionHandler handler) {
            delegate.setNewConnectionHandler((connectionId, peerId, offer) -> {
                geyser.getLogger().info("[proxy-bridge] NetherNet offer received (peer=" + peerId
                    + ", offerBytes=" + (offer == null ? 0 : offer.length()) + ").");
                handler.onConnect(connectionId, peerId, offer);
            });
        }

        @Override
        public void setAdvertisementData(PongData data) {
            delegate.setAdvertisementData(data);
        }

        @Override
        public java.util.List<NetherNetSignaling.IceServerInfo> getIceServers() {
            return delegate.getIceServers();
        }

        @Override
        public void sendSignal(String peerId, String signal) {
            geyser.getLogger().info("[proxy-bridge] NetherNet signal sent (peer=" + peerId
                + ", signalBytes=" + (signal == null ? 0 : signal.length()) + ").");
            delegate.sendSignal(peerId, signal);
        }

        @Override
        public void setSignalHandler(long connectionId, NetherNetSignaling.SignalHandler handler) {
            delegate.setSignalHandler(connectionId, signal -> {
                geyser.getLogger().info("[proxy-bridge] NetherNet signal received (connection="
                    + connectionId + ", signalBytes=" + (signal == null ? 0 : signal.length()) + ").");
                handler.onSignal(signal);
            });
        }

        @Override
        public void removeSignalHandler(long connectionId) {
            delegate.removeSignalHandler(connectionId);
        }

        @Override
        public String getLocalNetworkId() {
            return delegate.getLocalNetworkId();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
