package com.albionbot.data;

import java.util.List;
import java.util.Map;

/** On-disk shape of data/recipes.json. {@code names} maps item id to English name (tier adjective removed). */
public record RecipeFile(Map<String, String> names, List<Recipe> recipes) {}
