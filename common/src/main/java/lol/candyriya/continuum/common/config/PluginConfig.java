package lol.candyriya.continuum.common.config;

import org.jetbrains.annotations.NotNull;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;
import org.spongepowered.configurate.objectmapping.meta.Setting;

import java.util.Objects;

/**
 * Root of config.yml. A record, not a class: the config is data, immutability
 * protects against "someone flipped a value bypassing reload".
 *
 * <p>New fields come in pairs: the record component plus a key in resources/config.yml.
 * On load ConfigManager writes missing keys back into the file, so old user configs
 * heal themselves.</p>
 */
@ConfigSerializable
public record PluginConfig(
        @Setting("prefix")
        @Comment("Prefix of all plugin messages, MiniMessage format")
        String prefix,

        @Setting("debug")
        @Comment("Verbose logging: true enables debug output from the core")
        boolean debug,

        @Setting("welcome")
        @Comment("Welcome message on join")
        Welcome welcome
) {
    public PluginConfig {
        Objects.requireNonNull(prefix, "prefix");
        Objects.requireNonNull(welcome, "welcome");
    }

    /** The welcome section of config.yml. */
    @ConfigSerializable
    public record Welcome(
            @Setting("enabled")
            @Comment("Send the welcome message on join")
            boolean enabled,

            @Setting("message")
            @Comment("Welcome text, <player> gets substituted automatically")
            String message
    ) {
        public Welcome {
            Objects.requireNonNull(message, "message");
        }
    }

    /** Defaults, one to one with resources/config.yml. Two sources of truth are evil,
     * so when the two drift apart the file wins — this method is only a safety net for broken configs. */
    public static @NotNull PluginConfig defaults() {
        return new PluginConfig(
                "<gray>[<gradient:#7ed6ff:#4a9eff>Continuum</gradient>]</gray> ",
                false,
                new Welcome(true, "<green>Hey, <player>! Welcome to the network :)")
        );
    }
}
