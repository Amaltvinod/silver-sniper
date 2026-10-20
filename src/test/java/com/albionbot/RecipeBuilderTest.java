package com.albionbot;

import com.albionbot.config.Game;

import com.albionbot.data.Ingredient;
import com.albionbot.data.Recipe;
import com.albionbot.data.RecipeBook;
import com.albionbot.data.RecipeBuilder;
import com.albionbot.data.RecipeFile;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Uses a miniature dump shaped like ao-bin-dumps' items.json (single objects vs arrays, string attributes). */
class RecipeBuilderTest {
    static final String ITEMS = """
            {"items": {
              "@xmlns:xsi": "http://www.w3.org/2001/XMLSchema-instance",
              "shopcategories": {"shopcategory": [{"@id": "weapons"}]},
              "simpleitem": {"@uniquename": "T4_METALBAR", "@tier": "4"},
              "weapon": [
                {"@uniquename": "T4_MAIN_SWORD", "@tier": "4", "@itemvalue": "336",
                 "@shopcategory": "weapons", "@shopsubcategory1": "sword",
                 "craftingrequirements": {"@silver": "0", "@time": "10",
                    "craftresource": [
                      {"@uniquename": "T4_METALBAR", "@count": "16"},
                      {"@uniquename": "T4_LEATHER", "@count": "8"},
                      {"@uniquename": "T4_ARTEFACT_MAIN_SWORD", "@count": "1", "@maxreturnamount": "0"}]},
                 "enchantments": {"enchantment": [
                    {"@enchantmentlevel": "1"}, {"@enchantmentlevel": "2"}]}},
                {"@uniquename": "T5_ARROWS", "@tier": "5", "@itemvalue": "10", "@shopcategory": "consumables",
                 "craftingrequirements": [
                    {"@amountcrafted": "10", "craftresource": {"@uniquename": "T5_PLANKS", "@count": "5"}}]},
                {"@uniquename": "T4_NOT_CRAFTABLE", "@tier": "4"}
              ]
            }}
            """;

    static final String NAMES = """
            [{"UniqueName": "T4_MAIN_SWORD", "LocalizedNames": {"EN-US": "Adept's Broadsword"}},
             {"UniqueName": "T4_METALBAR", "LocalizedNames": {"EN-US": "Adept's Metal Bar"}},
             {"UniqueName": "T4_METALBAR_LEVEL1@1", "LocalizedNames": {"EN-US": "Adept's Metal Bar"}},
             {"UniqueName": "T4_ARTEFACT_MAIN_SWORD", "LocalizedNames": {"EN-US": "Broadsword Blade"}},
             {"UniqueName": "T4_LEATHER", "LocalizedNames": {"EN-US": "Adept's Leather"}},
             {"UniqueName": "T4_NOTHING", "LocalizedNames": null}]
            """;

    private RecipeFile build() throws Exception {
        ObjectMapper om = new ObjectMapper();
        return RecipeBuilder.build(om.readTree(ITEMS), om.readTree(NAMES));
    }

    private Recipe recipe(RecipeFile f, String id) {
        return f.recipes().stream().filter(r -> r.itemId().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void buildsBaseAndEnchantedRecipesAndSkipsNonCraftables() throws Exception {
        RecipeFile f = build();
        assertEquals(4, f.recipes().size()); // sword .0 .1 .2 + arrows
        assertFalse(f.recipes().stream().anyMatch(r -> r.itemId().equals("T4_NOT_CRAFTABLE")));
        assertFalse(f.recipes().stream().anyMatch(r -> r.itemId().equals("T4_METALBAR")));
    }

    @Test
    void readsBaseRecipeFields() throws Exception {
        Recipe sword = recipe(build(), "T4_MAIN_SWORD");
        assertEquals("MAIN_SWORD", sword.baseId());
        assertEquals("Broadsword", sword.baseName()); // "Adept's" stripped
        assertEquals(4, sword.tier());
        assertEquals("weapons", sword.category());
        assertEquals(336, sword.itemValue());
        assertEquals(1, sword.amountCrafted());
        assertEquals(3, sword.ingredients().size());
        assertEquals(new Ingredient("T4_METALBAR", 16, true), sword.ingredients().get(0));
        assertEquals(new Ingredient("T4_ARTEFACT_MAIN_SWORD", 1, false), sword.ingredients().get(2));
    }

    @Test
    void enchantedRecipesSwapRefinedResourcesButNotArtifacts() throws Exception {
        Recipe s1 = recipe(build(), "T4_MAIN_SWORD@1");
        assertEquals(1, s1.enchant());
        assertEquals("T4_METALBAR_LEVEL1@1", s1.ingredients().get(0).itemId());
        assertEquals("T4_LEATHER_LEVEL1@1", s1.ingredients().get(1).itemId());
        assertEquals("T4_ARTEFACT_MAIN_SWORD", s1.ingredients().get(2).itemId());
        assertFalse(s1.ingredients().get(2).returnable());
        assertEquals("T4_METALBAR_LEVEL2@2", recipe(build(), "T4_MAIN_SWORD@2").ingredients().get(0).itemId());
    }

    @Test
    void handlesArrayCraftingRequirementsAndAmountCrafted() throws Exception {
        Recipe arrows = recipe(build(), "T5_ARROWS");
        assertEquals(10, arrows.amountCrafted());
        assertEquals(new Ingredient("T5_PLANKS", 5, true), arrows.ingredients().get(0));
    }

    @Test
    void recipeBookFindsByBaseIdOrNameAndDisplaysIngredients() throws Exception {
        RecipeBook book = new RecipeBook(build());
        assertEquals("T4_MAIN_SWORD@2", book.find("MAIN_SWORD", 4, 2).orElseThrow().itemId());
        assertEquals("T4_MAIN_SWORD", book.find("broadsword", 4, 0).orElseThrow().itemId());
        assertEquals("T4_MAIN_SWORD", book.find("broad", 4, 0).orElseThrow().itemId());
        assertFalse(book.find("broadsword", 8, 0).isPresent()); // no T8 in this dump

        assertEquals("T4.1 Metal Bar", book.display("T4_METALBAR_LEVEL1@1"));
        assertEquals("T4 Leather", book.display("T4_LEATHER"));
        assertEquals("T4 Broadsword Blade", book.display("T4_ARTEFACT_MAIN_SWORD"));
        assertEquals("T6 Some Thing", book.display("T6_SOME_THING")); // falls back to the id

        assertEquals("Broadsword", book.suggest("sword", 5).get(0).getValue());
        Optional<Recipe> none = book.find("zzz", 4, 0);
        assertFalse(none.isPresent());
    }

    @Test
    void filterByTierEnchantAndCategory() throws Exception {
        RecipeBook book = new RecipeBook(build());
        assertEquals(1, book.filter(4, 0, Set.of("melee")).size());
        assertEquals(0, book.filter(4, 0, Set.of("armor")).size());
        assertTrue(book.filter(5, 0, Set.of("ranged", "melee")).isEmpty(), "arrows are not Black Market gear");
    }

    @Test
    void gearGroupsMatchRealDumpCategories() {
        assertEquals("melee", Game.gearGroup("weapons", "sword"));
        assertEquals("melee", Game.gearGroup("weapons", "quarterstaff"));
        assertEquals("ranged", Game.gearGroup("weapons", "crossbow"));
        assertEquals("magic", Game.gearGroup("weapons", "firestaff"));
        assertEquals("offhand", Game.gearGroup("offhands", "shieldtype"));
        assertEquals("armor", Game.gearGroup("head", "plate_helmet"));
        assertEquals("armor", Game.gearGroup("shoes", "cloth_shoes"));
        assertEquals("accessories", Game.gearGroup("capes", "accessoires_capes_martlock"));
        assertEquals(null, Game.gearGroup("consumables", "potion"));
        assertEquals(null, Game.gearGroup("artefacts", "weapons"));
    }

    @Test
    void recipeFileRoundTripsThroughJson() throws Exception {
        ObjectMapper om = new ObjectMapper();
        String json = om.writeValueAsString(build());
        RecipeFile back = om.readValue(json, RecipeFile.class);
        assertEquals(4, back.recipes().size());
        assertEquals(recipe(build(), "T4_MAIN_SWORD@1"), recipe(back, "T4_MAIN_SWORD@1"));
    }
}
