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
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.configuration.PortalBridgeConfig;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class PortalNetherNetServer implements AutoCloseable {
    private final GeyserImpl geyser;
    private final PortalBridgeConfig config;
    private final String authHeaderFile;
    private static final long DISPOSAL_CHECK_SECONDS = 30;
    private static final int MAX_DISPOSAL_ATTEMPTS = 20; // ~10 minutes, then free regardless.
    /** Peers accepted on the current signaling generation. Swapped on every reload. */
    private volatile ChannelGroup activeChildren =
        new DefaultChannelGroup("nethernet-peers", GlobalEventExecutor.INSTANCE);
    /** Factories retired by a reload, awaiting a safe moment to free their native handle. */
    private final java.util.Set<PeerConnectionFactory> retiredFactories =
        java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final NetherNetEventLoops eventLoops;
    private final GeyserNetherNetServerInitializer initializer;
    private PeerConnectionFactory peerConnectionFactory;
    private NetherNetServerSignaling signaling;
    private final String configuredNetworkId;
    private Channel channel;

    public PortalNetherNetServer(GeyserImpl geyser, PortalBridgeConfig config, NetherNetEventLoops eventLoops,
                                 String authHeaderFile, String configuredNetworkId) {
        this.geyser = geyser;
        this.config = config;
        this.eventLoops = eventLoops;
        this.authHeaderFile = authHeaderFile == null ? "" : authHeaderFile;
        this.configuredNetworkId = configuredNetworkId == null ? "" : configuredNetworkId;
        this.initializer = new GeyserNetherNetServerInitializer(geyser, eventLoops.playerGroup(), () -> this.activeChildren);
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

    // authHeaderFile is the MCXboxBroadcast cache holding the Xbox token this ingress
    // authenticates with. It falls back to config.xboxAuthHeaderFile() when empty.
    private static String resolveAuthHeader(GeyserImpl geyser, PortalBridgeConfig config, String authHeaderFile) {
        if (!config.xboxAuthHeader().isBlank()) {
            return config.xboxAuthHeader();
        }

        String targetFile = !authHeaderFile.isBlank() ? authHeaderFile : config.xboxAuthHeaderFile();
        if (targetFile.isBlank()) {
            return "";
        }

        // The publisher (e.g. MCXboxBroadcast) may still be finishing its own auth flow
        // for this account when Geyser starts, so the cache file this ingress
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
            .group(this.eventLoops.bossGroup(), this.eventLoops.workerGroup())
            .channelFactory(NetherNetChannelFactory.server(this.peerConnectionFactory, this.signaling))
            .childOption(ChannelOption.TCP_NODELAY, false)
            .childHandler(this.initializer);

        // Name the account in both the success and failure paths: a bare "401 Unauthorized"
        // from the bind below gives no indication of which token Xbox rejected.
        String authLabel = this.authHeaderFile.isBlank() ? "<config header>" : this.authHeaderFile;
        // Log it BEFORE binding. Wrapping the failure afterwards is unreliable here:
        // Netty rethrows the original exception instance from the event-loop thread, so the
        // logged stack trace can hide which auth source was in flight. A plain "attempting"
        // line means the last one printed before an error is always the one that failed.
        // Only under debug-logging: the success line below already names the auth source, and
        // the failure path names it too, so on a retry loop this was an extra INFO line every
        // pass for no new information.
        if (config.debugLogging()) {
            this.geyser.getLogger().info("[proxy-bridge] Binding NetherNet ingress for auth source " + authLabel
                + " (network id " + this.signaling.getLocalNetworkId() + ", token " + shortAuthFingerprint() + ")");
        }
        try {
            this.channel = bootstrap.bind(new InetSocketAddress(0)).syncUninterruptibly().channel();
        } catch (Throwable throwable) {
            // syncUninterruptibly() rethrows the bind failure as-is, and the Xbox signaling
            // failure is a java.net.ConnectException - a checked exception, NOT a
            // RuntimeException - so this must catch Throwable or the auth context below
            // is silently lost and the log shows a bare, unattributable 401.
            throw new IllegalStateException("NetherNet ingress failed to bind for auth source " + authLabel
                + " (network id " + this.signaling.getLocalNetworkId() + ", token " + shortAuthFingerprint() + ")", throwable);
        }
        this.geyser.getLogger().info("[proxy-bridge] NetherNet ingress started with network ID " + this.signaling.getLocalNetworkId()
            + " for auth source " + authLabel + " (token " + shortAuthFingerprint() + ")");
        if (config.debugLogging()) {
            this.geyser.getLogger().info("[proxy-bridge] NetherNet control channel bound through Xbox signaling and local server bootstrap.");
        }
    }

    /**
     * Short, non-secret tag for the token in use, so an unexpected account shows up in the log.
     */
    private String shortAuthFingerprint() {
        String fingerprint = authHeaderFingerprint(this.geyser, this.config, this.authHeaderFile);
        if (fingerprint.isBlank()) {
            return "EMPTY - no auth header was resolved";
        }
        return fingerprint.substring(0, Math.min(8, fingerprint.length()));
    }

    /**
     * Recreates only the signaling channel used to accept new NetherNet
     * connections, using a fresh Xbox auth header. The shared event loop
     * groups and every already-established player channel are left untouched, so calling this does not disconnect anyone who
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
            .group(this.eventLoops.bossGroup(), this.eventLoops.workerGroup())
            .channelFactory(NetherNetChannelFactory.server(newPeerConnectionFactory, newSignaling))
            .childOption(ChannelOption.TCP_NODELAY, false)
            .childHandler(this.initializer);

        Channel newChannel;
        try {
            newChannel = bootstrap.bind(new InetSocketAddress(0)).syncUninterruptibly().channel();
        } catch (Throwable throwable) {
            // Keep serving on the old signaling channel/factory if the new one fails
            // to bind; do not tear down anything that was previously working.
            // Must be Throwable, not RuntimeException: syncUninterruptibly() rethrows
            // the checked java.net.ConnectException that a rejected Xbox token produces,
            // which would otherwise skip this cleanup and leak the new native factory.
            newSignaling.close();
            disposeQuietly(newPeerConnectionFactory);
            throw throwable;
        }

        // Peers accepted from here on belong to the new generation; the ones already running stay
        // in the old group, which is what the reaper watches before freeing the old factory.
        ChannelGroup retiredChildren = this.activeChildren;
        this.activeChildren = new DefaultChannelGroup("nethernet-peers", GlobalEventExecutor.INSTANCE);

        this.channel = newChannel;
        this.signaling = newSignaling;
        this.peerConnectionFactory = newPeerConnectionFactory;

        if (oldChannel != null) {
            oldChannel.close().syncUninterruptibly();
        }
        oldSignaling.close();
        // Do NOT dispose the old factory here. Closing the old *server* channel does not close
        // the peer channels already accepted through it - players mid-session keep running on
        // native PeerConnections owned by this factory. dispose() frees the native handle those
        // peers are still using, so a token refresh while anyone was connected could take the
        // JVM down with a native fault rather than a Java exception. Hand it to the reaper
        // instead, which disposes it once no session is left on it.
        scheduleDisposal(oldPeerConnectionFactory, retiredChildren);

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
        // The event loop groups are owned and shut down by the bootstrap.
        // On shutdown the peers are going away with the process, so disposing inline is fine;
        // drain anything the reaper is still holding so nothing is left allocated.
        disposeQuietly(this.peerConnectionFactory);
        for (PeerConnectionFactory pending : this.retiredFactories) {
            disposeQuietly(pending);
        }
        this.retiredFactories.clear();
    }

    /**
     * Disposes a retired {@link PeerConnectionFactory} only once nothing is running on it.
     * <p>
     * Freeing it while peers accepted through it are still alive is a native use-after-free, so
     * the factory is parked and re-checked on a timer. The grace period is bounded: after
     * {@link #MAX_DISPOSAL_ATTEMPTS} checks the factory is disposed anyway, otherwise a peer that
     * never fully closes would pin one factory per token refresh forever - the leak this is
     * meant to avoid.
     */
    private void scheduleDisposal(PeerConnectionFactory factory, ChannelGroup children) {
        if (factory == null) {
            return;
        }
        this.retiredFactories.add(factory);
        scheduleDisposalAttempt(factory, children, 1);
    }

    private void scheduleDisposalAttempt(PeerConnectionFactory factory, ChannelGroup children, int attempt) {
        this.eventLoops.workerGroup().schedule(() -> {
            if (!this.retiredFactories.contains(factory)) {
                return;
            }
            // Watch THIS factory's own peers, not the server's session count. An earlier version
            // checked whether Geyser had any sessions at all, which on a populated server is never
            // true - so it always fell through to the attempt cap and freed the factory while its
            // peers were still live, which is the very thing the delay exists to prevent.
            if (children.isEmpty()) {
                this.retiredFactories.remove(factory);
                disposeQuietly(factory);
                return;
            }
            if (attempt >= MAX_DISPOSAL_ATTEMPTS) {
                // Bounded on purpose: a peer that never closes would otherwise pin one native
                // factory per token refresh forever. Close them first so the handle we free is
                // no longer in use, rather than freeing it out from under them.
                this.geyser.getLogger().warning("[proxy-bridge] " + children.size()
                    + " NetherNet peer(s) still open on a retired signaling channel after "
                    + (MAX_DISPOSAL_ATTEMPTS * DISPOSAL_CHECK_SECONDS / 60) + " minutes; closing them to release it.");
                children.close().awaitUninterruptibly(5, TimeUnit.SECONDS);
                this.retiredFactories.remove(factory);
                disposeQuietly(factory);
                return;
            }
            scheduleDisposalAttempt(factory, children, attempt + 1);
        }, DISPOSAL_CHECK_SECONDS, TimeUnit.SECONDS);
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
