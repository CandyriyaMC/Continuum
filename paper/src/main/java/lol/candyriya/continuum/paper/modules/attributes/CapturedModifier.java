package lol.candyriya.continuum.paper.modules.attributes;

/**
 * One captured attribute modifier, stored exactly as the wire format carries it.
 *
 * <p>{@code operation} is the {@link org.bukkit.attribute.AttributeModifier.Operation}
 * constant name ({@code ADD_NUMBER}, {@code ADD_SCALAR}, {@code MULTIPLY_SCALAR_1}).
 * {@code slotGroup} is the {@link org.bukkit.inventory.EquipmentSlotGroup} name
 * ({@code any}, {@code mainhand}, ...); {@code null} means "not recorded" and is
 * applied as {@code any}.</p>
 *
 * <p>Raw names are kept on purpose: the record is a storage DTO, not a typed
 * domain object. Decoding tolerates unknown operation names (the modifier is
 * skipped with a warning, never thrown) and unknown slot groups (they degrade to
 * {@code any} at apply time), so a payload written by a newer backend does not
 * blow up an older one.</p>
 */
record CapturedModifier(String key, double amount, String operation, String slotGroup) {

    CapturedModifier {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("modifier key must not be blank");
        }
        if (!Double.isFinite(amount)) {
            throw new IllegalArgumentException("modifier amount must be finite, got " + amount);
        }
        if (operation == null || operation.isBlank()) {
            throw new IllegalArgumentException("modifier operation must not be blank");
        }
        if (slotGroup != null && slotGroup.isBlank()) {
            throw new IllegalArgumentException("modifier slotGroup must not be blank when present");
        }
    }
}
