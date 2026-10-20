package com.albionbot.data;

import com.albionbot.config.Game;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** In-memory index over recipes.json. */
public final class RecipeBook {
    static final Pattern ID = Pattern.compile("^T(\\d+)_(.+?)(?:@(\\d))?$");

    private final Map<String, Recipe> byId = new HashMap<>();
    private final Map<String, String> names;
    private final Map<String, String> baseNames = new TreeMap<>(); // baseId -> base name

    public RecipeBook(RecipeFile file) {
        this.names = file.names() == null ? Map.of() : file.names();
        for (Recipe r : file.recipes()) {
            byId.put(r.itemId(), r);
            baseNames.putIfAbsent(r.baseId(), r.baseName());
        }
    }

    public static RecipeBook load(Path path) throws IOException {
        if (!Files.exists(path)) {
            throw new IOException("Recipe file " + path + " not found. Build it first with: "
                    + "java -cp target/albion-bot.jar com.albionbot.data.BuildRecipes");
        }
        ObjectMapper om = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return new RecipeBook(om.readValue(path.toFile(), RecipeFile.class));
    }

    public int size() {
        return byId.size();
    }

    public Optional<Recipe> get(String itemId) {
        return Optional.ofNullable(byId.get(itemId));
    }

    public static String idFor(String baseId, int tier, int enchant) {
        return "T" + tier + "_" + baseId + (enchant > 0 ? "@" + enchant : "");
    }

    /** Resolve what the user typed (a base id from autocomplete, or a name fragment) at a tier/enchant. */
    public Optional<Recipe> find(String query, int tier, int enchant) {
        String q = query.trim();
        Recipe direct = byId.get(idFor(q.toUpperCase(Locale.ROOT).replace(' ', '_'), tier, enchant));
        if (direct != null) {
            return Optional.of(direct);
        }
        String lower = q.toLowerCase(Locale.ROOT);
        List<Map.Entry<String, String>> matches = new ArrayList<>();
        for (Map.Entry<String, String> e : baseNames.entrySet()) {
            if (e.getValue().equalsIgnoreCase(q)) {
                matches.add(0, e); // exact name wins
            } else if (e.getValue().toLowerCase(Locale.ROOT).contains(lower)) {
                matches.add(e);
            }
        }
        matches.sort(Comparator.comparing((Map.Entry<String, String> e) -> !e.getValue().equalsIgnoreCase(q))
                .thenComparingInt(e -> e.getValue().length()));
        for (Map.Entry<String, String> m : matches) {
            Recipe r = byId.get(idFor(m.getKey(), tier, enchant));
            if (r != null) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }

    /** Autocomplete: (baseId, baseName) pairs whose name or id contains the typed text. */
    public List<Map.Entry<String, String>> suggest(String typed, int limit) {
        String t = typed.toLowerCase(Locale.ROOT).trim();
        List<Map.Entry<String, String>> out = new ArrayList<>();
        for (Map.Entry<String, String> e : baseNames.entrySet()) {
            if (t.isEmpty()
                    || e.getValue().toLowerCase(Locale.ROOT).contains(t)
                    || e.getKey().toLowerCase(Locale.ROOT).contains(t)) {
                out.add(e);
            }
        }
        out.sort(Comparator.comparing((Map.Entry<String, String> e) ->
                        !e.getValue().toLowerCase(Locale.ROOT).startsWith(t))
                .thenComparing(Map.Entry::getValue));
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    /** All recipes at a tier/enchant whose gear group ({@link Game#gearGroup}) is in {@code groups}. */
    public List<Recipe> filter(int tier, int enchant, Set<String> groups) {
        List<Recipe> out = new ArrayList<>();
        for (Recipe r : byId.values()) {
            String group = Game.gearGroup(r.category(), r.subcategory());
            if (r.tier() == tier && r.enchant() == enchant && group != null && groups.contains(group)) {
                out.add(r);
            }
        }
        out.sort(Comparator.comparing(Recipe::itemId));
        return out;
    }

    /** Human label for any item id, e.g. {@code T4_METALBAR_LEVEL1@1} becomes "T4.1 Metal Bar". */
    public String display(String itemId) {
        Matcher m = ID.matcher(itemId);
        if (!m.matches()) {
            return itemId;
        }
        String tier = m.group(1);
        String rest = m.group(2);
        String ench = m.group(3);
        String name = names.get(itemId);
        if (name == null) {
            name = names.get("T" + tier + "_" + rest);
        }
        if (name == null) {
            name = titleCase(rest.replaceAll("_LEVEL\\d$", ""));
        }
        return "T" + tier + (ench != null && !ench.equals("0") ? "." + ench : "") + " " + name;
    }

    private static String titleCase(String id) {
        StringBuilder sb = new StringBuilder();
        for (String part : id.toLowerCase(Locale.ROOT).split("_")) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }
}
