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

package org.geysermc.geyser.network.portal.nethernet;

import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.DefaultThreadFactory;

/**
 * The Netty thread pools backing NetherNet ingress.
 * <p>
 * Owned by {@link org.geysermc.geyser.network.portal.PortalBridgeBootstrap} rather than by the
 * server itself, so a signaling reload swaps the channel without churning threads: the two
 * unbounded groups default to {@code availableProcessors() * 2} each, which is a lot to tear
 * down and rebuild every time an Xbox token refreshes.
 */
public final class NetherNetEventLoops implements AutoCloseable {
    private final EventLoopGroup bossGroup =
        new NioEventLoopGroup(1, new DefaultThreadFactory("GeyserNetherNetBoss", true));
    private final EventLoopGroup workerGroup =
        new NioEventLoopGroup(0, new DefaultThreadFactory("GeyserNetherNetChild", true));
    private final EventLoopGroup playerGroup =
        new DefaultEventLoopGroup(0, new DefaultThreadFactory("GeyserNetherNetPlayer", true));

    /**
     * @return the acceptor group for the signaling channel.
     */
    public EventLoopGroup bossGroup() {
        return bossGroup;
    }

    /**
     * @return the group driving I/O for accepted NetherNet peers.
     */
    public EventLoopGroup workerGroup() {
        return workerGroup;
    }

    /**
     * @return the group that backs each {@code GeyserSession}'s event loop.
     */
    public EventLoopGroup playerGroup() {
        return playerGroup;
    }

    @Override
    public void close() {
        playerGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
        bossGroup.shutdownGracefully();
    }
}
