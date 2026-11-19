package lol.candyriya.continuum.common.config;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;

/**
 * Plugin Messaging (Velocity <-> backend) transport tuning.
 *
 * @param enabled          allow the channel to carry Continuum frames; when false
 *                         only RabbitMQ (if enabled) carries events
 * @param channel          namespaced channel, lowercase {@code namespace:path}
 * @param maxPayloadBytes  wire size cap for a single frame; larger frames are rejected
 */
@ConfigSerializable
public record PluginMessageConfig(boolean enabled, String channel, int maxPayloadBytes) {
}
