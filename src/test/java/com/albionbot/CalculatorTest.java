package com.albionbot;

import com.albionbot.calc.Calculator;
import com.albionbot.calc.CraftResult;
import com.albionbot.calc.FlipResult;
import com.albionbot.calc.Settings;
import com.albionbot.config.Game;
import com.albionbot.data.Ingredient;
import com.albionbot.data.Recipe;
import com.albionbot.market.Demand;
import com.albionbot.market.Price;
import com.albionbot.market.PriceBook;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CalculatorTest {
    static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static final Instant ONE_HOUR_AGO = NOW.minus(Duration.ofHours(1));

    /** 16 metal bars + 8 leather (both returnable) + 1 artifact (never returned). */
    static final Recipe SWORD = new Recipe("T4_MAIN_SWORD", "MAIN_SWORD", "Broadsword", 4, 0, "melee", "sword",
            1000, 1, List.of(
            new Ingredient("T4_METALBAR", 16, true),
            new Ingredient("T4_LEATHER", 8, true),
            new Ingredient("T4_ARTEFACT_MAIN_SWORD", 1, false)));

    static final Settings NO_FEE = new Settings(0.25, 0.04, 0);

    private final List<Price> rows = new ArrayList<>();

    private void sell(String item, String city, int price) {
        rows.add(new Price(item, city, 1, price, ONE_HOUR_AGO, 0, null));
    }

    private void bmBuy(String item, int price, Instant date) {
        rows.add(new Price(item, Game.BLACK_MARKET, 1, 0, null, price, date));
    }

    private PriceBook book() {
        return new PriceBook(rows, Duration.ofHours(24), CLOCK);
    }

    private void standardMarket() {
        sell("T4_METALBAR", "Martlock", 100);
        sell("T4_METALBAR", "Lymhurst", 120);
        sell("T4_LEATHER", "Lymhurst", 50);
        sell("T4_LEATHER", "Martlock", 70);
        sell("T4_ARTEFACT_MAIN_SWORD", "Bridgewatch", 1000);
        sell("T4_ARTEFACT_MAIN_SWORD", "Martlock", 1200);
        bmBuy("T4_MAIN_SWORD", 5000, ONE_HOUR_AGO);
    }

    @Test
    void picksCheapestCityPerIngredientAndAppliesReturnOnlyToReturnables() {
        standardMarket();
        CraftResult r = Calculator.evaluateCraft(book(), SWORD, NO_FEE, Game.CITIES, 1).orElseThrow();

        assertEquals("Martlock", r.quotes().get(0).city());   // metal bars
        assertEquals("Lymhurst", r.quotes().get(1).city());   // leather
        assertEquals("Bridgewatch", r.quotes().get(2).city()); // artifact

        // (16*100 + 8*50) * (1 - 0.25) + 1000 artifact at full price
        assertEquals(2500.0, r.materialCost(), 1e-9);
        assertEquals(2500.0, r.totalCost(), 1e-9);
        assertEquals(4800.0, r.netRevenue(), 1e-9); // 5000 * 0.96
        assertEquals(2300.0, r.profit(), 1e-9);
        assertEquals(0.92, r.roi(), 1e-9);
    }

    @Test
    void stationFeeUsesItemValueAndNutrition() {
        standardMarket();
        Settings withFee = new Settings(0.25, 0.04, 500);
        CraftResult r = Calculator.evaluateCraft(book(), SWORD, withFee, Game.CITIES, 1).orElseThrow();
        assertEquals(1000 * 0.1125 * 500 / 100.0, r.stationFeeSilver(), 1e-9); // 562.5
        assertEquals(2500 + 562.5, r.totalCost(), 1e-9);
    }

    @Test
    void singleCityOnlyListsCitiesThatSellEverything() {
        standardMarket();
        CraftResult r = Calculator.evaluateCraft(book(), SWORD, NO_FEE, Game.CITIES, 1).orElseThrow();
        // Only Martlock sells metal bars, leather and the artifact: (1600 + 560) * 0.75 + 1200
        assertEquals(List.of("Martlock"), new ArrayList<>(r.singleCityCost().keySet()));
        assertEquals(2820.0, r.singleCityCost().get("Martlock"), 1e-9);
    }

    @Test
    void noResultWithoutFreshBlackMarketOrder() {
        standardMarket();
        rows.removeIf(p -> p.city().equals(Game.BLACK_MARKET));
        bmBuy("T4_MAIN_SWORD", 5000, NOW.minus(Duration.ofHours(30))); // older than the 24h limit
        assertFalse(Calculator.evaluateCraft(book(), SWORD, NO_FEE, Game.CITIES, 1).isPresent());
    }

    @Test
    void noResultWhenAnIngredientHasNoPriceAnywhere() {
        standardMarket();
        rows.removeIf(p -> p.itemId().equals("T4_ARTEFACT_MAIN_SWORD"));
        assertFalse(Calculator.evaluateCraft(book(), SWORD, NO_FEE, Game.CITIES, 1).isPresent());
    }

    @Test
    void zeroPricesAreTreatedAsMissing() {
        standardMarket();
        sell("T4_METALBAR", "Bridgewatch", 0); // API reports 0 for "no listing"
        CraftResult r = Calculator.evaluateCraft(book(), SWORD, NO_FEE, Game.CITIES, 1).orElseThrow();
        assertEquals("Martlock", r.quotes().get(0).city());
    }

    @Test
    void amountCraftedDividesCost() {
        Recipe arrows = new Recipe("T4_ARROW", "ARROW", "Arrow", 4, 0, "consumables", "x", 0, 10,
                List.of(new Ingredient("T4_PLANKS", 10, true)));
        sell("T4_PLANKS", "Martlock", 100);
        bmBuy("T4_ARROW", 500, ONE_HOUR_AGO);
        CraftResult r = Calculator.evaluateCraft(book(), arrows, new Settings(0, 0, 0), Game.CITIES, 1).orElseThrow();
        assertEquals(100.0, r.materialCost(), 1e-9); // 10 planks * 100 / 10 items
    }

    @Test
    void flipPicksCheapestCityAndTaxesTheSale() {
        sell("T5_BAG", "Lymhurst", 3000);
        sell("T5_BAG", "Thetford", 2500);
        bmBuy("T5_BAG", 4000, ONE_HOUR_AGO);
        FlipResult f = Calculator.evaluateFlip(book(), "T5_BAG", Game.CITIES, 1, new Settings(0, 0.04, 0)).orElseThrow();
        assertEquals("Thetford", f.buyCity());
        assertEquals(2500, f.buyPrice());
        assertEquals(1340.0, f.profit(), 1e-9); // 4000*0.96 - 2500
        assertEquals(0.536, f.roi(), 1e-9);
    }

    @Test
    void flipNeedsBothSides() {
        sell("T5_BAG", "Lymhurst", 3000);
        assertFalse(Calculator.evaluateFlip(book(), "T5_BAG", Game.CITIES, 1, NO_FEE).isPresent());
        assertTrue(true);
        Optional<FlipResult> none = Calculator.evaluateFlip(book(), "T9_MISSING", Game.CITIES, 1, NO_FEE);
        assertFalse(none.isPresent());
    }

    // ---------------------------------------------------------------- Black Market price from sales history

    static final Demand SELLS_AT_4000 = new Demand("T4_MAIN_SWORD", 20, 4000, 7);

    @Test
    void freshOrderIsUsedAsIsEvenWithHistory() {
        bmBuy("T4_MAIN_SWORD", 5000, ONE_HOUR_AGO);
        Calculator.BlackMarketPrice p = Calculator.blackMarketPrice(book(), "T4_MAIN_SWORD", 1, SELLS_AT_4000).orElseThrow();
        assertEquals(new Calculator.BlackMarketPrice(5000, false), p);
    }

    @Test
    void oldOrderAboveTheAverageIsReplacedByTheAverage() {
        bmBuy("T4_MAIN_SWORD", 5000, NOW.minus(Duration.ofHours(10)));
        assertEquals(new Calculator.BlackMarketPrice(4000, true),
                Calculator.blackMarketPrice(book(), "T4_MAIN_SWORD", 1, SELLS_AT_4000).orElseThrow());
    }

    @Test
    void oldOrderBelowTheAverageIsKeptSoTheEstimateNeverInflates() {
        bmBuy("T4_MAIN_SWORD", 3500, NOW.minus(Duration.ofHours(10)));
        assertEquals(new Calculator.BlackMarketPrice(3500, false),
                Calculator.blackMarketPrice(book(), "T4_MAIN_SWORD", 1, SELLS_AT_4000).orElseThrow());
    }

    @Test
    void missingOrderUsesHistoryOnlyWithEnoughDays() {
        assertEquals(new Calculator.BlackMarketPrice(4000, true),
                Calculator.blackMarketPrice(book(), "T4_MAIN_SWORD", 1, SELLS_AT_4000).orElseThrow());
        Demand oneDay = new Demand("T4_MAIN_SWORD", 20, 4000, 1);
        assertTrue(Calculator.blackMarketPrice(book(), "T4_MAIN_SWORD", 1, oneDay).isEmpty(), "1 day of sales is too thin");
        assertTrue(Calculator.blackMarketPrice(book(), "T4_MAIN_SWORD", 1, null).isEmpty());
    }

    @Test
    void oldOrderWithoutHistoryIsStillUsed() {
        bmBuy("T4_MAIN_SWORD", 5000, NOW.minus(Duration.ofHours(10)));
        assertEquals(new Calculator.BlackMarketPrice(5000, false),
                Calculator.blackMarketPrice(book(), "T4_MAIN_SWORD", 1, null).orElseThrow());
    }

    @Test
    void craftAndFlipUseTheHistoryPrice() {
        standardMarket();
        rows.removeIf(p -> p.city().equals(Game.BLACK_MARKET));
        bmBuy("T4_MAIN_SWORD", 9000, NOW.minus(Duration.ofHours(8)));
        sell("T4_MAIN_SWORD", "Martlock", 3000);

        CraftResult c = Calculator.evaluateCraft(book(), SWORD, NO_FEE, Game.CITIES, 1, SELLS_AT_4000).orElseThrow();
        assertTrue(c.bmFromHistory());
        assertEquals(4000, c.blackMarketPrice());
        assertEquals(4000 * 0.96, c.netRevenue(), 1e-9);

        FlipResult f = Calculator.evaluateFlip(book(), "T4_MAIN_SWORD", Game.CITIES, 1, NO_FEE, SELLS_AT_4000).orElseThrow();
        assertTrue(f.bmFromHistory());
        assertEquals(4000 * 0.96 - 3000, f.profit(), 1e-9);

        assertFalse(Calculator.evaluateCraft(book(), SWORD, NO_FEE, Game.CITIES, 1).orElseThrow().bmFromHistory(),
                "without history the old order is used, as before");
    }
}
