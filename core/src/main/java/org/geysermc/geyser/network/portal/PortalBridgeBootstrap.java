/*
 * Copyright (c) 2019-2025 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * @author GeyserMC
 * @link https://github.com/GeyserMC/Geyser
 */

package org.geysermc.geyser.network.portal;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.configuration.PortalBridgeConfig;
import org.geysermc.geyser.network.CIDRMatcher;
import org.geysermc.geyser.network.portal.nethernet.NetherNetEventLoops;
import org.geysermc.geyser.network.portal.nethernet.PortalNetherNetServer;
import org.geysermc.geyser.ping.GeyserPingInfo;
import org.geysermc.geyser.ping.IGeyserPingPassthrough;
import org.geysermc.geyser.translator.text.MessageTranslator;

import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.Path;
import java.util.Collection;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Startup and trust bootstrap for portal-style Bedrock ingress.
 */
public final class PortalBridgeBootstrap implements AutoCloseable {
    private static final String SESSION_STATUS_FILENAME = "portal-session-status.json";
    /** Geyser's own record of the ids to reuse on restart, so the Xbox identity stays stable. */
    private static final String IDENTITIES_FILENAME = "portal-nethernet-identities.json";
    private final GeyserImpl geyser;
    private final PortalBridgeConfig config;
    private final List<CIDRMatcher> trustedProxyMatchers;
    private final List<String> configuredRules;
    private @Nullable ScheduledExecutorService statusWriterExecutor;
    private @Nullable ScheduledExecutorService startupRetryExecutor;
    private static final long STARTUP_RETRY_DELAY_SECONDS = 10;
    private @Nullable PortalNetherNetServer netherNetServer;
    private final NetherNetEventLoops eventLoops = new NetherNetEventLoops();
    private volatile String authHeaderFingerprint = "";

    public PortalBridgeBootstrap(GeyserImpl geyser) {
        this.geyser = geyser;
        this.config = geyser.config().advanced().bedrock().portalBridge();
        this.configuredRules = copyRules(this.config.trustedProxyIps());
        this.trustedProxyMatchers = parseTrustedProxyMatchers(this.configuredRules);
    }

    public void start() {
        geyser.getLogger().info("[proxy-bridge] Portal bridge enabled.");
        
        if (trustedProxyMatchers.isEmpty() && config.debugLogging()) {
            geyser.getLogger().info("[proxy-bridge] No trusted proxy rules are configured. "
                + "This only matters if you relay a plain Bedrock proxy into this Geyser's normal UDP listener; "
                + "NetherNet ingress trust does not depend on this list.");
        }

        // Only worth stating when there is something to state; the empty case is already
        // covered by the message above, which printed "…rules are configured" immediately
        // followed by "Trusted proxy rules: 0".
        if (config.debugLogging() && !configuredRules.isEmpty()) {
            geyser.getLogger().info("[proxy-bridge] Trusted proxy rules: " + configuredRules.size());
        }

        if (config.xboxAuthHeader().isBlank() && config.xboxAuthHeaderFile().isBlank()) {
            geyser.getLogger().warning("[proxy-bridge] Xbox auth header source is not configured; NetherNet ingress will stay disabled.");
            return;
        }

        silenceSignalingLibraryLogger();
        attemptStart(1);
    }

    /**
     * Mutes dev.kastle's own signaling logger.
     * <p>
     * That library logs the full websocket handshake stacktrace itself on every failed bind, so a
     * rejected token produced two stacktraces per shard per retry. The standalone bootstrap silences
     * it through log4j2.xml, but this fork runs on Velocity, which uses its own logging config, so
     * the level has to be set programmatically here. Done reflectively and best-effort: if Log4j core
     * is not reachable the bridge still works, the log is just noisier.
     */
    private void silenceSignalingLibraryLogger() {
        try {
            Class<?> configurator = Class.forName("org.apache.logging.log4j.core.config.Configurator");
            Class<?> level = Class.forName("org.apache.logging.log4j.Level");
            Object off = level.getField("OFF").get(null);
            configurator.getMethod("setLevel", String.class, level)
                .invoke(null, "dev.kastle.netty.channel.nethernet.signaling.NetherNetXboxRpcSignaling", off);
        } catch (Throwable ignored) {
            // Best effort only.
        }
    }

    // A failed attempt (e.g. Xbox rejects a stale/not-yet-ready auth token with 401 while
    // MCXboxBroadcast is still starting up) used to permanently disable NetherNet ingress
    // for the rest of this Geyser process, since reloadSignalingIfAuthChanged() only ever
    // reloads a server that is already running - there was nothing left to reload after a
    // failed startup. Retry on a timer instead of giving up after one try.
    private void attemptStart(int attempt) {
        try {
            startNetherNetServer();
            startStatusWriter();
            if (this.startupRetryExecutor != null) {
                this.startupRetryExecutor.shutdownNow();
                this.startupRetryExecutor = null;
            }
            if (attempt > 1) {
                geyser.getLogger().info("[proxy-bridge] NetherNet ingress started successfully after " + attempt + " attempts.");
            }
        } catch (Throwable throwable) {
            closeNetherNetServersOnly();
            long delay = retryDelaySeconds(attempt);
            // Log the reason once, then stay quiet. Passing the throwable here printed a full
            // stacktrace per shard per attempt - with 3 shards retrying every 10s that is 6
            // stacktraces every 10 seconds forever, which buries every other line in the log.
            if (attempt == 1) {
                geyser.getLogger().error("[proxy-bridge] NetherNet ingress could not start: " + rootMessage(throwable)
                    + " Retrying in the background; no further attempts will be logged until one succeeds.");
            } else if (attempt % 30 == 0) {
                geyser.getLogger().warning("[proxy-bridge] NetherNet ingress still down after " + attempt
                    + " attempts: " + rootMessage(throwable));
            }
            scheduleStartupRetry(attempt + 1, delay);
        }
    }


    /** Back off 10s -> 30s -> 60s so a permanently rejected token stops hammering Xbox and the log. */
    private static long retryDelaySeconds(int attempt) {
        if (attempt < 3) {
            return STARTUP_RETRY_DELAY_SECONDS;
        }
        return attempt < 10 ? 30 : 60;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null || message.isBlank() ? root.getClass().getSimpleName() : message;
    }

    private void scheduleStartupRetry(int nextAttempt, long delaySeconds) {
        if (this.startupRetryExecutor == null) {
            this.startupRetryExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "GeyserPortalBridgeStartupRetry");
                thread.setDaemon(true);
                return thread;
            });
        }
        this.startupRetryExecutor.schedule(() -> attemptStart(nextAttempt), delaySeconds, TimeUnit.SECONDS);
    }

    public boolean isTrustedProxy(@Nullable InetSocketAddress address) {
        if (address == null) {
            return false;
        }

        InetAddress inetAddress = address.getAddress();
        if (inetAddress == null) {
            return false;
        }

        for (CIDRMatcher matcher : trustedProxyMatchers) {
            if (matcher.matches(inetAddress)) {
                return true;
            }
        }
        return false;
    }

    public int trustedProxyRuleCount() {
        return trustedProxyMatchers.size();
    }

    @Override
    public void close() {
        if (this.startupRetryExecutor != null) {
            this.startupRetryExecutor.shutdownNow();
            this.startupRetryExecutor = null;
        }
        if (this.statusWriterExecutor != null) {
            this.statusWriterExecutor.shutdownNow();
            this.statusWriterExecutor = null;
        }
        deleteStatusFile();
        deleteShardFiles();
        closeNetherNetServersOnly();
        this.eventLoops.close();
    }

    private void closeNetherNetServersOnly() {
        if (this.netherNetServer != null) {
            this.netherNetServer.close();
            this.netherNetServer = null;
        }
    }

    private void startStatusWriter() {
        this.statusWriterExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "GeyserPortalStatusWriter");
            thread.setDaemon(true);
            return thread;
        });
        writeStatusFile();
        this.statusWriterExecutor.scheduleWithFixedDelay(this::writeStatusFile, 5, 5, TimeUnit.SECONDS);
        this.statusWriterExecutor.scheduleWithFixedDelay(this::reloadSignalingIfAuthChanged, 2, 2, TimeUnit.SECONDS);
    }

    private String computeCombinedFingerprint() {
        return PortalNetherNetServer.authHeaderFingerprint(this.geyser, this.config, config.xboxAuthHeaderFile());
    }

    private void reloadSignalingIfAuthChanged() {
        try {
            String currentFingerprint = computeCombinedFingerprint();
            if (currentFingerprint.isBlank() || Objects.equals(currentFingerprint, this.authHeaderFingerprint)) {
                return;
            }

            synchronized (this) {
                String confirmedFingerprint = computeCombinedFingerprint();
                if (confirmedFingerprint.isBlank() || Objects.equals(confirmedFingerprint, this.authHeaderFingerprint)) {
                    return;
                }

                // Reload ONLY the shards whose own token changed. The combined fingerprint
                // changes whenever ANY account refreshes, so reloading every shard here meant
                // one account's routine token refresh tore down and rebuilt the signaling
                // channel - and allocated a fresh native PeerConnectionFactory - for every
                // other shard too, repeatedly, for no reason.
                if (this.netherNetServer != null) {
                    geyser.getLogger().info("[proxy-bridge] Xbox auth source changed; reloading NetherNet signaling.");
                    this.netherNetServer.reloadSignaling();
                    writeIdentityFile();
                    writeStatusFile();
                }
                this.authHeaderFingerprint = confirmedFingerprint;
            }
        } catch (Throwable throwable) {
            ConnectException authFailure = findAuthConnectException(throwable);
            if (authFailure != null) {
                geyser.getLogger().error("[proxy-bridge] Failed to reload NetherNet signaling: "
                    + authFailure.getMessage() + " (will keep retrying every 2s until the Xbox auth source is valid)");
            } else {
                geyser.getLogger().error("[proxy-bridge] Failed to reload NetherNet signaling after Xbox auth refresh.", throwable);
            }
        }
    }

    private static @Nullable ConnectException findAuthConnectException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ConnectException connectException) {
                return connectException;
            }
            current = current.getCause();
        }
        return null;
    }

    private void writeStatusFile() {
        try {
            JsonObject root = new JsonObject();
            GeyserPingInfo pingInfo = resolvePingInfo();
            String bukkitMotd = resolveBukkitMotd();
            Integer bukkitPlayers = resolveBukkitOnlinePlayers();
            Integer bukkitMaxPlayers = resolveBukkitMaxPlayers();

            String primaryMotd = bukkitMotd != null ? bukkitMotd : geyser.config().motd().primaryMotd();
            String secondaryMotd = geyser.config().motd().secondaryMotd();
            int players = bukkitPlayers != null ? bukkitPlayers : geyser.onlineConnections().size();
            int maxPlayers = bukkitMaxPlayers != null ? bukkitMaxPlayers : geyser.config().motd().maxPlayers();

            if (geyser.config().motd().passthroughMotd() && pingInfo != null && pingInfo.getDescription() != null) {
                String[] motd = MessageTranslator.convertMessageLenient(pingInfo.getDescription()).split("\n");
                primaryMotd = (motd.length > 0 && !motd[0].isBlank()) ? motd[0].trim() : primaryMotd;
                secondaryMotd = (motd.length > 1 && !motd[1].isBlank()) ? motd[1].trim() : secondaryMotd;
            }

            if (secondaryMotd == null || secondaryMotd.isBlank() || "Another Geyser server.".equals(secondaryMotd)) {
                secondaryMotd = primaryMotd;
            }

            if (geyser.config().motd().passthroughPlayerCounts() && pingInfo != null && pingInfo.getPlayers() != null) {
                players = pingInfo.getPlayers().getOnline();
                maxPlayers = pingInfo.getPlayers().getMax();
            }

            root.addProperty("hostName", secondaryMotd);
            root.addProperty("worldName", primaryMotd);
            root.addProperty("players", players);
            root.addProperty("maxPlayers", maxPlayers);
            root.addProperty("ready", this.netherNetServer != null);
            root.addProperty("generatedAt", java.time.Instant.now().toString());
            if (this.netherNetServer != null) {
                root.addProperty("netherNetId", this.netherNetServer.networkId());
            }

            Path path = statusFile();
            Files.createDirectories(path.getParent());
            Path temporaryPath = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temporaryPath, root.toString() + System.lineSeparator());
            try {
                Files.move(temporaryPath, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(temporaryPath, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception exception) {
            if (config.debugLogging()) {
                geyser.getLogger().warning("[proxy-bridge] Failed to write session status file: " + exception.getMessage());
            }
        }
    }

    private void deleteStatusFile() {
        try {
            Files.deleteIfExists(statusFile());
        } catch (Exception exception) {
            if (config.debugLogging()) {
                geyser.getLogger().warning("[proxy-bridge] Failed to remove session status file: " + exception.getMessage());
            }
        }
    }

    private Path statusFile() {
        return this.geyser.configDirectory().resolve(SESSION_STATUS_FILENAME);
    }

    /**
     * Starts the single NetherNet ingress.
     * <p>
     * This used to loop over {@code shard-count} / {@code xbox-auth-header-files} and bind one
     * ingress per Xbox account. That existed so each sub-account could advertise its own joinable
     * session; sub-accounts now join the primary session as members instead, so there is exactly
     * one session and therefore exactly one ingress to bind.
     */
    private synchronized void startNetherNetServer() {
        String configuredNetworkId = this.config.netherNetNetworkId();
        if (configuredNetworkId.isBlank()) {
            configuredNetworkId = readPersistedNetworkId();
        }

        PortalNetherNetServer server = new PortalNetherNetServer(
            this.geyser, this.config, this.eventLoops, this.config.xboxAuthHeaderFile(), configuredNetworkId);
        // Let the failure propagate: with one ingress there is nothing left to serve if it fails,
        // so attemptStart() logs it once and schedules the retry.
        server.start();
        this.netherNetServer = server;
        this.authHeaderFingerprint = computeCombinedFingerprint();
        writeIdentityFile();
    }

    /**
     * Persists the active network id so a restart rebinds the same Xbox identity instead of
     * publishing a new one.
     */
    private void writeIdentityFile() {
        try {
            if (this.netherNetServer == null) {
                return;
            }
            JsonObject root = new JsonObject();
            root.addProperty("networkId", this.netherNetServer.networkId());

            Path identitiesPath = this.geyser.configDirectory().resolve(IDENTITIES_FILENAME);
            Files.createDirectories(identitiesPath.getParent());
            Files.writeString(identitiesPath, root.toString() + System.lineSeparator());
        } catch (Exception exception) {
            this.geyser.getLogger().warning("[proxy-bridge] Failed to persist the NetherNet identity: " + exception.getMessage());
        }
    }

    /**
     * Removes stale files from older builds that wrote a plain-text id and a separate shard
     * list. The identities file is deliberately kept: it is what lets a restart reuse the same
     * Xbox identity instead of publishing a new one.
     */
    private void deleteShardFiles() {
        for (String stale : new String[] {"portal-nethernet-id.txt", "portal-nethernet-shards.json"}) {
            try {
                Files.deleteIfExists(this.geyser.configDirectory().resolve(stale));
            } catch (Exception exception) {
                if (config.debugLogging()) {
                    geyser.getLogger().warning("[proxy-bridge] Failed to remove stale file " + stale + ": " + exception.getMessage());
                }
            }
        }
    }

    /**
     * Reads the previously bound network id. Also accepts the old {@code shards} array so an
     * existing identities file from a sharded build still yields a stable identity on first start.
     */
    private String readPersistedNetworkId() {
        Path identitiesPath = this.geyser.configDirectory().resolve(IDENTITIES_FILENAME);
        if (!Files.isRegularFile(identitiesPath)) {
            return "";
        }

        try {
            JsonObject root = JsonParser.parseString(Files.readString(identitiesPath)).getAsJsonObject();
            if (root.has("networkId") && !root.get("networkId").isJsonNull()) {
                return root.get("networkId").getAsString().replaceAll("[^0-9]", "");
            }
            if (root.has("shards") && root.get("shards").isJsonArray()) {
                var shards = root.getAsJsonArray("shards");
                if (!shards.isEmpty() && shards.get(0).isJsonObject()) {
                    JsonObject first = shards.get(0).getAsJsonObject();
                    if (first.has("networkId") && !first.get("networkId").isJsonNull()) {
                        return first.get("networkId").getAsString().replaceAll("[^0-9]", "");
                    }
                }
            }
        } catch (Exception exception) {
            if (config.debugLogging()) {
                geyser.getLogger().warning("[proxy-bridge] Failed to read the persisted NetherNet identity: " + exception.getMessage());
            }
        }

        return "";
    }

    private @Nullable GeyserPingInfo resolvePingInfo() {
        IGeyserPingPassthrough pingPassthrough = geyser.getBootstrap().getGeyserPingPassthrough();
        if (pingPassthrough == null) {
            return null;
        }

        try {
            return pingPassthrough.getPingInformation(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        } catch (RuntimeException exception) {
            if (config.debugLogging()) {
                geyser.getLogger().warning("[proxy-bridge] Failed to resolve live ping info for session status file: " + exception.getMessage());
            }
            return null;
        }
    }

    private @Nullable String resolveBukkitMotd() {
        try {
            Class<?> bukkitClass = Class.forName("org.bukkit.Bukkit");
            Object motd = bukkitClass.getMethod("getMotd").invoke(null);
            if (motd instanceof String string && !string.isBlank()) {
                return string.trim();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private @Nullable Integer resolveBukkitOnlinePlayers() {
        try {
            Class<?> bukkitClass = Class.forName("org.bukkit.Bukkit");
            Object onlinePlayers = bukkitClass.getMethod("getOnlinePlayers").invoke(null);
            if (onlinePlayers instanceof Collection<?> collection) {
                return collection.size();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private @Nullable Integer resolveBukkitMaxPlayers() {
        try {
            Class<?> bukkitClass = Class.forName("org.bukkit.Bukkit");
            Object maxPlayers = bukkitClass.getMethod("getMaxPlayers").invoke(null);
            if (maxPlayers instanceof Integer integer) {
                return integer;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static List<String> copyRules(@Nullable List<String> configuredRules) {
        if (configuredRules == null || configuredRules.isEmpty()) {
            return List.of();
        }
        return List.copyOf(configuredRules);
    }

    private List<CIDRMatcher> parseTrustedProxyMatchers(List<String> rules) {
        List<CIDRMatcher> matchers = new ArrayList<>(rules.size());
        for (String entry : rules) {
            if (entry == null) {
                continue;
            }

            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            try {
                matchers.add(new CIDRMatcher(trimmed));
            } catch (RuntimeException exception) {
                geyser.getLogger().warning("[proxy-bridge] Ignoring invalid trusted proxy rule: " + trimmed);
                if (geyser.config().debugMode() || config.debugLogging()) {
                    geyser.getLogger().debug("[proxy-bridge] Invalid trusted proxy rule parse failure", exception);
                }
            }
        }
        return List.copyOf(matchers);
    }
}