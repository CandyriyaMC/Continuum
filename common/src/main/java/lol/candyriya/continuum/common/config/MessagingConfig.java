package lol.candyriya.continuum.common.config;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;

/**
 * The two transports Continuum can use, toggled independently. At least one
 * must be enabled — validated at load time.
 */
@ConfigSerializable
public record MessagingConfig(RabbitMqConfig rabbitmq, PluginMessageConfig pluginMessage) {

    public MessagingConfig {
        if (rabbitmq == null) {
            rabbitmq = new RabbitMqConfig(true, "", "continuum.events", new RabbitMqRetry(5, java.time.Duration.ofMillis(200), java.time.Duration.ofSeconds(10)));
        }
        if (pluginMessage == null) {
            pluginMessage = new PluginMessageConfig(true, "continuum:messaging", 32_768);
        }
    }
}
