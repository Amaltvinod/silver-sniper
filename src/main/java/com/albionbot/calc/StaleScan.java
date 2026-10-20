package com.albionbot.calc;

import com.albionbot.config.Game;
import com.albionbot.data.Ingredient;
import com.albionbot.data.Recipe;
import com.albionbot.market.Demand;
import com.albionbot.market.Freshness;
import com.albionbot.market.PriceBook;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Which prices the guild should refresh in game (with the Albion Data Client running) for /scan. Pure logic, no I/O.
 * Each list puts what matters most first: items the Black Market actually buys, and materials many recipes need.
 */
public final class StaleScan {
    private StaleScan() {}

    /**
     * One price to refresh.
     *
     * @param age    age of the freshest price we have; empty when it was never seen
     * @param weight what to sort by: Black Market sales per day for gear, number of recipes needing it for materials
     */
    public record Entry(String itemId, Optional<Duration> age, double weight) {}

    /** Gear whose Black Market buy order is older than {@link Freshness#BLACK_MARKET}, best sellers first. */
    public static List<Entry> blackMarket(PriceBook book, List<Recipe> items, Map<String, Demand> demand, int quality) {
        List<Entry> out = new ArrayList<>();
        for (Recipe r : items) {
            Optional<Duration> age = book.buyAge(r.itemId(), Game.BLACK_MARKET, quality);
            if (Freshness.stale(age, Freshness.BLACK_MARKET)) out.add(new Entry(r.itemId(), age, perDay(demand, r.itemId())));
        }
        out.sort(PRIORITY);
        return out;
    }

    /** Gear with no city listing newer than {@link Freshness#CITY_GEAR} in any city, best sellers first. */
    public static List<Entry> cityGear(PriceBook book, List<Recipe> items, Map<String, Demand> demand, int quality) {
        List<Entry> out = new ArrayList<>();
        for (Recipe r : items) {
            Optional<Duration> age = freshestSell(book, r.itemId(), quality);
            if (Freshness.stale(age, Freshness.CITY_GEAR)) out.add(new Entry(r.itemId(), age, perDay(demand, r.itemId())));
        }
        out.sort(PRIORITY);
        return out;
    }

    /**
     * Materials of {@code items} with no city price newer than {@link Freshness#MATERIALS}, most-used first. Ones never
     * seen in any city (thinly listed artifacts, sigils, hearts) go after old ones, since an old bar or cloth price is
     * the quicker fix in game.
     */
    public static List<Entry> materials(PriceBook book, List<Recipe> items) {
        Map<String, Integer> usedBy = new LinkedHashMap<>();
        for (Recipe r : items) {
            for (Ingredient ing : r.ingredients()) usedBy.merge(ing.itemId(), 1, Integer::sum);
        }
        List<Entry> out = new ArrayList<>();
        usedBy.forEach((id, n) -> {
            Optional<Duration> age = freshestSell(book, id, 1);
            if (Freshness.stale(age, Freshness.MATERIALS)) out.add(new Entry(id, age, n));
        });
        out.sort(Comparator.comparing((Entry e) -> e.age().isEmpty()).thenComparing(PRIORITY));
        return out;
    }

    /** Highest weight first; ties go to the oldest price, with never-seen prices oldest of all. */
    static final Comparator<Entry> PRIORITY = Comparator.comparingDouble((Entry e) -> -e.weight())
            .thenComparing(e -> e.age().orElse(Duration.ofDays(36_500)), Comparator.reverseOrder())
            .thenComparing(Entry::itemId);

    private static Optional<Duration> freshestSell(PriceBook book, String itemId, int quality) {
        Optional<Duration> best = Optional.empty();
        for (String city : Game.CITIES) {
            Optional<Duration> age = book.sellAge(itemId, city, quality);
            if (age.isPresent() && (best.isEmpty() || age.get().compareTo(best.get()) < 0)) best = age;
        }
        return best;
    }

    private static double perDay(Map<String, Demand> demand, String itemId) {
        Demand d = demand.get(itemId);
        return d == null ? 0 : d.perDay();
    }
}
