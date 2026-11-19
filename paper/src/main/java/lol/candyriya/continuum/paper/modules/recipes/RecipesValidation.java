package lol.candyriya.continuum.paper.modules.recipes;

import lol.candyriya.continuum.api.module.ValidationResult;

import java.util.List;

/**
 * Defensive checks for {@link RecipesPayload} before restore applies it.
 *
 * <p>Format gate first, then caps, then per-entry checks — all cheap enough
 * to run on every restore. Nothing here touches live state; an invalid
 * result means Continuum marks the module {@code VALIDATION_FAILED} and
 * restore never runs, so a corrupt (or hand-edited) snapshot cannot mutate
 * a player's recipe book at all.</p>
 *
 * <p>Unknown namespaces are fine — {@code modded:copper_gear} is a normal
 * entry, the target server decides at restore time whether it knows the
 * key. What is <em>not</em> fine: malformed strings, duplicates and a list
 * larger than {@link RecipesPayload#MAX_RECIPES}. Those are hard failures,
 * because carrying on would mean either a corrupt restore or a silent loss
 * of recipe knowledge.</p>
 */
final class RecipesValidation {

    private RecipesValidation() {
        // static access only
    }

    static ValidationResult validate(RecipesPayload data) {
        if (data == null) {
            return ValidationResult.invalid("payload is null");
        }
        if (data.format() != RecipesPayload.FORMAT) {
            return ValidationResult.invalid(
                    "unsupported payload format " + data.format() + " (expected " + RecipesPayload.FORMAT + ")");
        }
        List<String> recipes = data.recipes();
        if (recipes == null) {
            return ValidationResult.invalid("recipes list is null");
        }
        if (recipes.size() > RecipesPayload.MAX_RECIPES) {
            return ValidationResult.invalid(
                    "recipes list exceeds the limit of " + RecipesPayload.MAX_RECIPES + " (got " + recipes.size() + ")");
        }
        int badIndex = RecipeKeys.firstInvalidIndex(recipes);
        if (badIndex >= 0) {
            return ValidationResult.invalid(
                    "invalid recipe key '" + recipes.get(badIndex) + "' at index " + badIndex);
        }
        String duplicate = RecipeKeys.firstDuplicate(recipes);
        if (duplicate != null) {
            return ValidationResult.invalid("duplicate recipe key '" + duplicate + "'");
        }
        return ValidationResult.ok();
    }
}
