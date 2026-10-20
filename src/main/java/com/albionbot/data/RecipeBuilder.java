package com.albionbot.data;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the community game-data dumps (github.com/ao-data/ao-bin-dumps) into the compact recipes.json the bot uses.
 *
 * <p>Enchanted gear is derived rather than read: the same recipe, but each refined resource (planks, metal bars,
 * leather, cloth, stone blocks) is swapped for its enchanted variant, e.g. {@code T4_METALBAR} becomes
 * {@code T4_METALBAR_LEVEL1@1} for a .1 item. Artifacts, runes and other non-refined inputs are kept as-is.
 */
public final class RecipeBuilder {
    private static final Pattern ID = Pattern.compile("^T(\\d+)_(.+)$");
    private static final Pattern REFINED = Pattern.compile("^T\\d_(PLANKS|METALBAR|LEATHER|CLOTH|STONEBLOCK)$");
    private static final Pattern LEVEL_SUFFIX = Pattern.compile("_LEVEL(\\d)$");
    private static final Pattern TIER_ADJECTIVE = Pattern.compile(
            "^(Beginner's|Novice's|Journeyman's|Adept's|Expert's|Master's|Grandmaster's|Elder's)\\s+");

    private RecipeBuilder() {}

    /**
     * @param itemsDump  parsed {@code items.json} from ao-bin-dumps
     * @param namesDump  parsed {@code formatted/items.json} from ao-bin-dumps (array of UniqueName + LocalizedNames)
     */
    public static RecipeFile build(JsonNode itemsDump, JsonNode namesDump) {
        Map<String, String> rawNames = new HashMap<>();
        for (JsonNode n : namesDump) {
            JsonNode loc = n.get("LocalizedNames");
            if (loc != null && loc.hasNonNull("EN-US") && n.hasNonNull("UniqueName")) {
                rawNames.put(n.get("UniqueName").asText(), loc.get("EN-US").asText());
            }
        }

        List<Recipe> recipes = new ArrayList<>();
        Set<String> neededNames = new HashSet<>();

        JsonNode items = itemsDump.get("items");
        for (var entry : items.properties()) {
            if (entry.getKey().startsWith("@")) continue;
            for (JsonNode item : asList(entry.getValue())) {
                if (item.isObject() && item.has("@uniquename")) {
                    addItem(item, rawNames, recipes, neededNames);
                }
            }
        }

        Map<String, String> names = new TreeMap<>();
        for (String id : neededNames) {
            String n = lookupName(id, rawNames);
            if (n != null) names.put(id, stripAdjective(n));
        }
        return new RecipeFile(names, recipes);
    }

    private static void addItem(JsonNode item, Map<String, String> rawNames, List<Recipe> out, Set<String> needed) {
        String uid = item.get("@uniquename").asText();
        Matcher m = ID.matcher(uid);
        if (!m.matches() || uid.contains("@")) return;

        List<JsonNode> reqs = asList(item.get("craftingrequirements"));
        if (reqs.isEmpty()) return;
        JsonNode req = reqs.get(0); // first recipe variant only
        List<Ingredient> base = new ArrayList<>();
        for (JsonNode res : asList(req.get("craftresource"))) {
            String rid = normalise(res.path("@uniquename").asText());
            int count = res.path("@count").asInt(1);
            boolean returnable = !"0".equals(res.path("@maxreturnamount").asText());
            base.add(new Ingredient(rid, count, returnable));
        }
        if (base.isEmpty()) return;

        int tier = Integer.parseInt(m.group(1));
        String baseId = m.group(2);
        String name = lookupName(uid, rawNames);
        String baseName = name == null ? baseId : stripAdjective(name);
        String category = item.path("@shopcategory").asText("");
        String sub = item.path("@shopsubcategory1").asText("");
        int value = (int) Math.round(item.path("@itemvalue").asDouble(0));
        int amount = req.path("@amountcrafted").asInt(1);

        List<Integer> levels = new ArrayList<>();
        levels.add(0);
        JsonNode ench = item.path("enchantments").path("enchantment");
        for (JsonNode e : asList(ench)) {
            int lvl = e.path("@enchantmentlevel").asInt(0);
            if (lvl > 0) levels.add(lvl);
        }

        for (int lvl : levels) {
            List<Ingredient> ings = lvl == 0 ? base : enchant(base, lvl);
            String id = lvl == 0 ? uid : uid + "@" + lvl;
            out.add(new Recipe(id, baseId, baseName, tier, lvl, category, sub, value, amount, ings));
            needed.add(id);
            for (Ingredient i : ings) needed.add(i.itemId());
        }
    }

    private static List<Ingredient> enchant(List<Ingredient> base, int lvl) {
        List<Ingredient> out = new ArrayList<>();
        for (Ingredient i : base) {
            String id = REFINED.matcher(i.itemId()).matches() ? i.itemId() + "_LEVEL" + lvl + "@" + lvl : i.itemId();
            out.add(new Ingredient(id, i.count(), i.returnable()));
        }
        return out;
    }

    /** Defensive: market ids for enchanted resources always carry an @n suffix. */
    private static String normalise(String id) {
        Matcher m = LEVEL_SUFFIX.matcher(id);
        return m.find() && !id.contains("@") ? id + "@" + m.group(1) : id;
    }

    private static String lookupName(String id, Map<String, String> rawNames) {
        String n = rawNames.get(id);
        if (n == null) n = rawNames.get(id.replaceAll("@\\d$", ""));
        if (n == null) n = rawNames.get(id.replaceAll("_LEVEL\\d@\\d$", ""));
        return n;
    }

    private static String stripAdjective(String name) {
        return TIER_ADJECTIVE.matcher(name).replaceFirst("");
    }

    /** Dump fields are either a single object or an array of them (or absent). */
    static List<JsonNode> asList(JsonNode n) {
        List<JsonNode> out = new ArrayList<>();
        if (n == null || n.isMissingNode() || n.isNull()) return out;
        if (n.isArray()) n.forEach(out::add);
        else out.add(n);
        return out;
    }
}
