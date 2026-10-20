package com.albionbot.calc;

import com.albionbot.data.Ingredient;

/** The cheapest place to buy one ingredient, and what it costs per unit. */
public record IngredientQuote(Ingredient ingredient, int unitPrice, String city) {}
