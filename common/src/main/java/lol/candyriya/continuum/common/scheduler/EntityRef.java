package lol.candyriya.continuum.common.scheduler;

import java.util.UUID;

/**
 * Platform-neutral handle for an entity a task targets. Carries only the
 * entity's stable id; platform implementations resolve it to whatever the
 * underlying scheduler needs (Folia region, Paper entity) at schedule time.
 */
public record EntityRef(UUID entityId) {

    public EntityRef {
        if (entityId == null) {
            throw new NullPointerException("entityId must not be null");
        }
    }
}
