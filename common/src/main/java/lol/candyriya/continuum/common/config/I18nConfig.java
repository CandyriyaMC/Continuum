package lol.candyriya.continuum.common.config;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;

/**
 * i18n behavior.
 *
 * @param defaultLocale    locale used when no player/client preference is known (admin choice)
 * @param fallbackLocale   locale used when a key is missing from the requested one
 * @param useClientLocale  prefer the client's locale over the admin default when resolving messages
 */
@ConfigSerializable
public record I18nConfig(String defaultLocale, String fallbackLocale, boolean useClientLocale) {
}
