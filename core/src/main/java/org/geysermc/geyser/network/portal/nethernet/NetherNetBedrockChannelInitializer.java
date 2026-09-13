/*
 * Copyright (c) 2026 GeyserMC. http://geysermc.org
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

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import org.cloudburstmc.protocol.bedrock.BedrockPeer;
import org.cloudburstmc.protocol.bedrock.BedrockSession;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.CompressionStrategy;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.SimpleCompressionStrategy;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.ZlibCompression;
import org.cloudburstmc.protocol.bedrock.netty.codec.packet.BedrockPacketCodec;
import org.cloudburstmc.protocol.bedrock.netty.codec.packet.BedrockPacketCodec_v3;
import org.cloudburstmc.protocol.common.util.Zlib;
import org.geysermc.geyser.network.portal.nethernet.codec.NetherNetPacketDecoder;
import org.geysermc.geyser.network.portal.nethernet.codec.NetherNetPacketEncoder;

public abstract class NetherNetBedrockChannelInitializer<T extends BedrockSession> extends ChannelInitializer<Channel> {
    private static final CompressionStrategy ZLIB_RAW_STRATEGY = new SimpleCompressionStrategy(new ZlibCompression(Zlib.RAW));

    @Override
    protected final void initChannel(Channel channel) throws Exception {
        channel.pipeline()
            .addLast(NetherNetPacketDecoder.NAME, new NetherNetPacketDecoder())
            .addLast(NetherNetPacketEncoder.NAME, new NetherNetPacketEncoder());
        this.initPacketCodec(channel);

        channel.pipeline().addLast(BedrockPeer.NAME, this.createPeer(channel));

        this.postInitChannel(channel);
    }

    public static CompressionStrategy getCompression() {
        return ZLIB_RAW_STRATEGY;
    }

    protected void postInitChannel(Channel channel) throws Exception {
    }

    protected void initPacketCodec(Channel channel) throws Exception {
        channel.pipeline().addLast(BedrockPacketCodec.NAME, new BedrockPacketCodec_v3());
    }

    protected BedrockPeer createPeer(Channel channel) {
        return new GeyserNetherNetBedrockPeer(channel, this::createSession);
    }

    protected final T createSession(BedrockPeer peer, int subClientId) {
        T session = this.createSession0(peer, subClientId);
        this.initSession(session);
        return session;
    }

    protected abstract T createSession0(BedrockPeer peer, int subClientId);

    protected abstract void initSession(T session);
}
