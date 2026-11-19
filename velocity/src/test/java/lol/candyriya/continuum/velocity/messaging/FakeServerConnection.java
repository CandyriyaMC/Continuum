package lol.candyriya.continuum.velocity.messaging;

import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.messages.PluginMessageEncoder;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;

import java.net.InetSocketAddress;
import java.util.Optional;

/** Minimal fake for the parts of the proxy surface the messaging path touches. */
final class FakeServerConnection implements ServerConnection {

    private final ServerInfo serverInfo;
    private final boolean sendResult;

    volatile ChannelIdentifier lastChannel;
    volatile byte[] lastPayload;
    volatile boolean sendPluginMessageCalled;

    FakeServerConnection(String name) {
        this(name, true);
    }

    FakeServerConnection(String name, boolean sendResult) {
        this.serverInfo = new ServerInfo(name, new InetSocketAddress("127.0.0.1", 25565));
        this.sendResult = sendResult;
    }

    @Override
    public RegisteredServer getServer() {
        throw new UnsupportedOperationException("not needed for messaging tests");
    }

    @Override
    public Optional<RegisteredServer> getPreviousServer() {
        return Optional.empty();
    }

    @Override
    public ServerInfo getServerInfo() {
        return serverInfo;
    }

    @Override
    public boolean isClientLoaded() {
        return true;
    }

    @Override
    public com.velocitypowered.api.proxy.Player getPlayer() {
        throw new UnsupportedOperationException("not needed for messaging tests");
    }

    @Override
    public boolean sendPluginMessage(ChannelIdentifier identifier, byte[] data) {
        sendPluginMessageCalled = true;
        lastChannel = identifier;
        lastPayload = data;
        return sendResult;
    }

    @Override
    public boolean sendPluginMessage(ChannelIdentifier identifier, PluginMessageEncoder encoder) {
        return sendPluginMessage(identifier, new byte[0]);
    }
}
