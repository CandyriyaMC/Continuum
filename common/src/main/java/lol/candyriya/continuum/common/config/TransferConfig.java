package lol.candyriya.continuum.common.config;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;

/**
 * Transfer behavior toggles.
 *
 * @param requireSnapshot    refuse to start a transfer without a durable pre-transfer snapshot
 * @param restoreBeforePlay  hold the player in a blocked state until the restore completes
 */
@ConfigSerializable
public record TransferConfig(boolean requireSnapshot, boolean restoreBeforePlay) {
}
