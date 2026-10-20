package com.albionbot.calc;

import com.albionbot.config.Game;
import com.albionbot.data.Ingredient;
import com.albionbot.data.Recipe;
import com.albionbot.market.Demand;
import com.albionbot.market.Freshness;
import com.albionbot.market.PriceBook;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Pure profit maths, no I/O.
 *
 * <ul>
 *   <li>Ingredients are bought instantly at the cheapest sell order (quality 1).</li>
 *   <li>Returnable ingredients cost {@code (1 - returnRate)} of face value; artifacts and similar cost full price.</li>
 *   <li>Station fee per item = {@code itemValue * 0.1125 * feePer100Nutrition / 100}.</li>
 *   <li>Black Market sale = highest buy order, minus sales tax. Selling into a buy order has no setup fee. An old or
 *       missing order is replaced by the 7-day average sale when that is lower (see {@link #blackMarketPrice}).</li>
 * </ul>
 */
public final class Calculator {
    private Calculator() {}

    /** The only ingredient quality the market uses. */
    private static final int INGREDIENT_QUALITY = 1;

    public static double stationFee(Recipe r, Settings s) {
        return r.itemValue() * 0.1125 * s.stationFee() / 100.0;
    }

    /** Material cost per crafted item once resource return is applied. */
    public static double materialCost(List<IngredientQuote> quotes, double returnRate, int amountCrafted) {
        double total = 0;
        for (IngredientQuote q : quotes) {
            double line = (double) q.ingredient().count() * q.unitPrice();
            total += q.ingredient().returnable() ? line * (1 - returnRate) : line;
        }
        return total / Math.max(1, amountCrafted);
    }

    /** Cheapest fresh sell order for {@code ing} across {@code cities}; empty when no city has one. */
    public static Optional<IngredientQuote> cheapest(PriceBook book, Ingredient ing, List<String> cities) {
        IngredientQuote best = null;
        for (String city : cities) {
            OptionalInt p = book.sell(ing.itemId(), city, INGREDIENT_QUALITY);
            if (p.isPresent() && (best == null || p.getAsInt() < best.unitPrice())) {
                best = new IngredientQuote(ing, p.getAsInt(), city);
            }
        }
        return Optional.ofNullable(best);
    }

    /** Fewest days of Black Market sales before their average is trusted over an old or missing buy order. */
    static final int MIN_HISTORY_DAYS = 2;

    /** @param fromHistory {@code price} is the item's 7-day average Black Market sale, not a buy order */
    public record BlackMarketPrice(int price, boolean fromHistory) {}

    /**
     * What selling {@code itemId} to the Black Market is likely to pay. A fresh buy order is used as-is. When the order
     * is old ({@link Freshness#BLACK_MARKET}) or missing, the 7-day average sale price stands in for it, unless the old
     * order is lower: the estimate only ever makes a sale look smaller, never bigger. Without enough sales history
     * ({@link #MIN_HISTORY_DAYS}) the old order is all there is.
     *
     * @param sales Black Market sales history for the item; null when unknown
     */
    public static Optional<BlackMarketPrice> blackMarketPrice(PriceBook book, String itemId, int quality, Demand sales) {
        OptionalInt order = book.buy(itemId, Game.BLACK_MARKET, quality);
        boolean fresh = order.isPresent()
                && !Freshness.stale(book.buyAge(itemId, Game.BLACK_MARKET, quality), Freshness.BLACK_MARKET);
        boolean history = sales != null && sales.avgPrice() > 0 && sales.days() >= MIN_HISTORY_DAYS;
        if (fresh || !history) {
            return order.isPresent() ? Optional.of(new BlackMarketPrice(order.getAsInt(), false)) : Optional.empty();
        }
        int avg = (int) Math.round(sales.avgPrice());
        if (order.isPresent() && order.getAsInt() <= avg) return Optional.of(new BlackMarketPrice(order.getAsInt(), false));
        return Optional.of(new BlackMarketPrice(avg, true));
    }

    /** Craft {@code recipe}, sell to the Black Market. Empty when either side lacks fresh prices. */
    public static Optional<CraftResult> evaluateCraft(
            PriceBook book, Recipe recipe, Settings s, List<String> buyCities, int quality) {
        return evaluateCraft(book, recipe, s, buyCities, quality, null);
    }

    /** As above, falling back on {@code sales} history when the Black Market order is old (see {@link #blackMarketPrice}). */
    public static Optional<CraftResult> evaluateCraft(
            PriceBook book, Recipe recipe, Settings s, List<String> buyCities, int quality, Demand sales) {
        Optional<BlackMarketPrice> bm = blackMarketPrice(book, recipe.itemId(), quality, sales);
        if (bm.isEmpty()) return Optional.empty();

        List<IngredientQuote> quotes = new ArrayList<>();
        for (Ingredient ing : recipe.ingredients()) {
            Optional<IngredientQuote> best = cheapest(book, ing, buyCities);
            if (best.isEmpty()) return Optional.empty();
            quotes.add(best.get());
        }

        double fee = stationFee(recipe, s);
        double material = materialCost(quotes, s.returnRate(), recipe.amountCrafted());
        double totalCost = material + fee;
        double net = bm.get().price() * (1 - s.salesTax());
        double profit = net - totalCost;
        double roi = totalCost > 0 ? profit / totalCost : 0;
        return Optional.of(new CraftResult(recipe, quality, quotes, material, fee, totalCost,
                bm.get().price(), net, profit, roi, singleCityCosts(book, recipe, s, buyCities), bm.get().fromHistory()));
    }

    private static Map<String, Double> singleCityCosts(
            PriceBook book, Recipe recipe, Settings s, List<String> cities) {
        Map<String, Double> costs = new LinkedHashMap<>();
        double fee = stationFee(recipe, s);
        for (String city : cities) {
            List<IngredientQuote> quotes = new ArrayList<>();
            for (Ingredient ing : recipe.ingredients()) {
                OptionalInt p = book.sell(ing.itemId(), city, INGREDIENT_QUALITY);
                if (p.isEmpty()) break;
                quotes.add(new IngredientQuote(ing, p.getAsInt(), city));
            }
            if (quotes.size() == recipe.ingredients().size()) {
                costs.put(city, materialCost(quotes, s.returnRate(), recipe.amountCrafted()) + fee);
            }
        }
        Map<String, Double> sorted = new LinkedHashMap<>();
        costs.entrySet().stream().sorted(Map.Entry.comparingByValue())
                .forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return sorted;
    }

    /** Buy {@code itemId} in the cheapest city and sell it into the Black Market. */
    public static Optional<FlipResult> evaluateFlip(
            PriceBook book, String itemId, List<String> buyCities, int quality, Settings s) {
        return evaluateFlip(book, itemId, buyCities, quality, s, null);
    }

    /** As above, falling back on {@code sales} history when the Black Market order is old (see {@link #blackMarketPrice}). */
    public static Optional<FlipResult> evaluateFlip(
            PriceBook book, String itemId, List<String> buyCities, int quality, Settings s, Demand sales) {
        Optional<BlackMarketPrice> bm = blackMarketPrice(book, itemId, quality, sales);
        if (bm.isEmpty()) return Optional.empty();
        String bestCity = null;
        int bestPrice = Integer.MAX_VALUE;
        for (String city : buyCities) {
            OptionalInt p = book.sell(itemId, city, quality);
            if (p.isPresent() && p.getAsInt() < bestPrice) {
                bestPrice = p.getAsInt();
                bestCity = city;
            }
        }
        if (bestCity == null) return Optional.empty();
        double net = bm.get().price() * (1 - s.salesTax());
        double profit = net - bestPrice;
        return Optional.of(new FlipResult(itemId, quality, bestCity, bestPrice, bm.get().price(), net, profit,
                profit / bestPrice, bm.get().fromHistory()));
    }

    public static final Comparator<CraftResult> CRAFT_BY_PROFIT =
            Comparator.comparingDouble(CraftResult::profit).reversed();
    public static final Comparator<CraftResult> CRAFT_BY_ROI =
            Comparator.comparingDouble(CraftResult::roi).reversed();
    public static final Comparator<FlipResult> FLIP_BY_PROFIT =
            Comparator.comparingDouble(FlipResult::profit).reversed();
}
