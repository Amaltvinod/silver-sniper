package com.albionbot.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Static game constants. */
public final class Game {
    private Game() {}

    /** Albion Online Data Project endpoints. west = Americas, east = Asia, europe = Europe. */
    public static final Map<String, String> SERVERS = Map.of(
            "west", "https://west.albion-online-data.com",
            "east", "https://east.albion-online-data.com",
            "europe", "https://europe.albion-online-data.com");

    /** Markets where ingredients / finished goods can be bought. Names must match the API exactly. */
    public static final List<String> CITIES = List.of(
            "Bridgewatch", "Caerleon", "Fort Sterling", "Lymhurst", "Martlock", "Thetford", "Brecilien");

    /** Lives in Caerleon, but the API reports it as its own location. */
    public static final String BLACK_MARKET = "Black Market";

    /**
     * Resource return rate: fraction of returnable materials refunded when crafting, {@code bonus / (1 + bonus)} of the
     * local production bonus. Royal cities give 18% base, a city's crafting specialisation +15%, a refining
     * specialisation +40%, focus +59%. Every command lets the user pick one.
     */
    public static final Map<String, Double> RETURN_PRESETS = new LinkedHashMap<>();

    static {
        RETURN_PRESETS.put("none", 0.152);               // royal city, no specialisation bonus, no focus (18%)
        RETURN_PRESETS.put("city", 0.248);               // city that specialises in crafting this item (33%)
        RETURN_PRESETS.put("focus", 0.435);              // focus, no city bonus (77%)
        RETURN_PRESETS.put("city_focus", 0.479);         // crafting city bonus + focus (92%)
        RETURN_PRESETS.put("refine_city", 0.367);        // refining a resource in its bonus city (58%)
        RETURN_PRESETS.put("refine_city_focus", 0.539);  // refining bonus city + focus (117%)
    }

    /** The Black Market only buys gear: these are the gear groups (see {@link #gearGroup}) treated as BM-eligible. */
    public static final Set<String> BM_CATEGORIES =
            Set.of("melee", "ranged", "magic", "offhand", "armor", "accessories");

    /**
     * Maps the game's shop category/subcategory (as in ao-bin-dumps, e.g. weapons/firestaff, armors/plate_armor)
     * to the gear group users pick in commands. Returns null for anything that is not Black Market gear.
     */
    public static String gearGroup(String category, String subcategory) {
        if (category == null) return null;
        String sub = subcategory == null ? "" : subcategory;
        return switch (category) {
            case "weapons" -> sub.equals("bow") || sub.equals("crossbow") ? "ranged"
                    : sub.endsWith("staff") && !sub.equals("quarterstaff") ? "magic"
                    : "melee";
            case "offhands" -> "offhand";
            case "head", "armors", "shoes" -> "armor";
            case "capes", "bags" -> "accessories";
            default -> null;
        };
    }
}
