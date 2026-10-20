package com.albionbot.calc;

import com.albionbot.data.Recipe;

import java.util.List;
import java.util.Map;

/**
 * Profit for crafting one item and selling it to Black Market buy orders. All silver figures are per item.
 *
 * @param quotes         cheapest city per ingredient (buy each where it is cheapest)
 * @param singleCityCost total cost if everything is bought in one city, cheapest first; only cities that sell every
 *                       ingredient appear
 * @param bmFromHistory  {@code blackMarketPrice} is the 7-day average sale price, because the buy order was old or
 *                       missing (see {@link Calculator#blackMarketPrice})
 */
public record CraftResult(
        Recipe recipe,
        int quality,
        List<IngredientQuote> quotes,
        double materialCost,
        double stationFeeSilver,
        double totalCost,
        int blackMarketPrice,
        double netRevenue,
        double profit,
        double roi,
        Map<String, Double> singleCityCost,
        boolean bmFromHistory) {}
