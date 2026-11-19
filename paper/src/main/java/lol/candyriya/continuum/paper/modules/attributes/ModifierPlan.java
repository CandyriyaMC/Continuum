package lol.candyriya.continuum.paper.modules.attributes;

import java.util.List;

/**
 * What a restore will do about modifiers on one attribute instance.
 *
 * @param keysToRemove  modifier keys to remove from the instance first (only keys the
 *                      snapshot also carries); order is the captured order
 * @param modifiersToAdd  every captured modifier, re-added after the removals —
 *                        replace, never accumulate
 */
record ModifierPlan(List<String> keysToRemove, List<CapturedModifier> modifiersToAdd) {

    ModifierPlan {
        keysToRemove = List.copyOf(keysToRemove);
        modifiersToAdd = List.copyOf(modifiersToAdd);
    }
}
