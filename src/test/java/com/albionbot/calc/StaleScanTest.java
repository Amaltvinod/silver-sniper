package com.albionbot.calc;

import com.albionbot.config.Game;
import com.albionbot.data.Ingredient;
import com.albionbot.data.Recipe;
import com.albionbot.market.Demand;
import com.albionbot.market.Freshness;
import com.albionbot.market.Price;
import com.albionbot.market.PriceBook;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaleScanTest {
    static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    static Recipe gear(String id, String... materials) {
        List<Ingredient> ings = new ArrayList<>();
        for (String m : materials) ings.add(new Ingredient(m, 8, true));
        return new Recipe(id, id, id, 6, 0, "weapons", "sword", 100, 1, ings);
    }

    static Instant hoursAgo(double h) {
        return NOW.minus(Duration.ofMinutes((long) (h * 60)));
    }

    static Price bm(String id, double hoursOld) {
        return new Price(id, Game.BLACK_MARKET, 1, 0, null, 50_000, hoursAgo(hoursOld));
    }

    static Price city(String id, String city, double hoursOld) {
        return new Price(id, city, 1, 40_000, hoursAgo(hoursOld), 0, null);
    }

    static PriceBook book(Price... rows) {
        // 7-day max age: /scan must see old rows' ages even when price commands would ignore them.
        return new PriceBook(List.of(rows), Duration.ofDays(7), CLOCK);
    }

    @Test
    void limitsMatchEachMarket() {
        assertFalse(Freshness.stale(Optional.of(Duration.ofHours(3)), Freshness.BLACK_MARKET), "exactly 3h is still fresh");
        assertTrue(Freshness.stale(Optional.of(Duration.ofMinutes(181)), Freshness.BLACK_MARKET));
        assertFalse(Freshness.stale(Optional.of(Duration.ofHours(5)), Freshness.CITY_GEAR));
        assertTrue(Freshness.stale(Optional.of(Duration.ofHours(7)), Freshness.CITY_GEAR));
        assertFalse(Freshness.stale(Optional.of(Duration.ofHours(20)), Freshness.MATERIALS));
        assertTrue(Freshness.stale(Optional.of(Duration.ofHours(25)), Freshness.MATERIALS));
        assertTrue(Freshness.stale(Optional.empty(), Freshness.MATERIALS), "never seen counts as stale");
    }

    @Test
    void blackMarketListsOldAndMissingOrdersBestSellersFirst() {
        Recipe fresh = gear("FRESH"), oldPopular = gear("OLD_POPULAR"), oldNiche = gear("OLD_NICHE"), never = gear("NEVER");
        PriceBook b = book(bm("FRESH", 1), bm("OLD_POPULAR", 5), bm("OLD_NICHE", 20));
        Map<String, Demand> demand = Map.of(
                "OLD_POPULAR", new Demand("OLD_POPULAR", 30, 50_000, 7),
                "OLD_NICHE", new Demand("OLD_NICHE", 2, 50_000, 7));

        List<StaleScan.Entry> out = StaleScan.blackMarket(b, List.of(fresh, oldPopular, oldNiche, never), demand, 1);

        assertEquals(List.of("OLD_POPULAR", "OLD_NICHE", "NEVER"), out.stream().map(StaleScan.Entry::itemId).toList());
        assertEquals(Optional.of(Duration.ofHours(5)), out.get(0).age());
        assertTrue(out.get(2).age().isEmpty());
    }

    @Test
    void cityGearIsFreshIfAnyCityHasARecentListing() {
        Recipe a = gear("A"), b = gear("B");
        PriceBook book = book(
                city("A", "Martlock", 30), city("A", "Lymhurst", 2),     // Lymhurst is fresh -> A is fine
                city("B", "Martlock", 10), city("B", "Thetford", 8));    // freshest is 8h -> B is stale

        List<StaleScan.Entry> out = StaleScan.cityGear(book, List.of(a, b), Map.of(), 1);

        assertEquals(1, out.size());
        assertEquals("B", out.get(0).itemId());
        assertEquals(Optional.of(Duration.ofHours(8)), out.get(0).age(), "reports the freshest listing's age");
    }

    @Test
    void materialsAreDedupedAndMostUsedComeFirst() {
        Recipe r1 = gear("R1", "BAR", "CLOTH"), r2 = gear("R2", "BAR"), r3 = gear("R3", "BAR", "LEATHER");
        PriceBook book = book(city("BAR", "Thetford", 30), city("CLOTH", "Lymhurst", 40), city("LEATHER", "Martlock", 2));

        List<StaleScan.Entry> out = StaleScan.materials(book, List.of(r1, r2, r3));

        assertEquals(List.of("BAR", "CLOTH"), out.stream().map(StaleScan.Entry::itemId).toList());
        assertEquals(3.0, out.get(0).weight(), "BAR is used by all three recipes");
    }

    @Test
    void neverSeenMaterialsGoAfterOldOnesEvenIfUsedMore() {
        Recipe r1 = gear("R1", "SIGIL", "BAR"), r2 = gear("R2", "SIGIL"), r3 = gear("R3", "SIGIL");
        PriceBook book = book(city("BAR", "Thetford", 30));

        List<StaleScan.Entry> out = StaleScan.materials(book, List.of(r1, r2, r3));

        assertEquals(List.of("BAR", "SIGIL"), out.stream().map(StaleScan.Entry::itemId).toList());
    }

    @Test
    void tiesGoToTheOldestThenNeverSeen() {
        Recipe a = gear("A"), b = gear("B"), c = gear("C");
        PriceBook book = book(bm("A", 4), bm("B", 12));

        List<StaleScan.Entry> out = StaleScan.blackMarket(book, List.of(a, b, c), Map.of(), 1);

        assertEquals(List.of("C", "B", "A"), out.stream().map(StaleScan.Entry::itemId).toList());
    }
}
