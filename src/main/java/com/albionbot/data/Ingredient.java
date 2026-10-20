package com.albionbot.data;

/** One line of a crafting recipe. {@code returnable} is false for artifacts etc. that are never refunded. */
public record Ingredient(String itemId, int count, boolean returnable) {}
