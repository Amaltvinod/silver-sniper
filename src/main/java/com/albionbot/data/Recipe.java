package com.albionbot.data;

import java.util.List;

/**
 * A craftable item, e.g. {@code T6_MAIN_SWORD@1}.
 *
 * @param baseId   item id without tier/enchant, e.g. {@code MAIN_SWORD}
 * @param baseName English name without the tier adjective, e.g. {@code Broadsword}
 * @param category the game's shop category (melee, armor, ...), used to find Black Market eligible gear
 */
public record Recipe(
        String itemId,
        String baseId,
        String baseName,
        int tier,
        int enchant,
        String category,
        String subcategory,
        int itemValue,
        int amountCrafted,
        List<Ingredient> ingredients) {

    public String displayName() {
        return "T" + tier + (enchant > 0 ? "." + enchant : "") + " " + baseName;
    }
}
