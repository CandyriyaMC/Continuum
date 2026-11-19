package lol.candyriya.continuum.common.config;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;

/**
 * Identity of this node inside the Continuum network.
 *
 * @param id        network identifier, e.g. {@code main-network}. Lowercase dns-ish.
 * @param serverId  this server's unique id inside the network, e.g. {@code survival-1}
 * @param role      what this node does in the network
 */
@ConfigSerializable
public record NetworkConfig(String id, String serverId, ServerRole role) {
}
