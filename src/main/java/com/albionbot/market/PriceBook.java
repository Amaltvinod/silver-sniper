package com.albionbot.market;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/** A snapshot of prices with freshness filtering: rows with no price, or older than {@code maxAge}, read as absent. */
public final class PriceBook {
    private record Key(String itemId, String city, int quality) {}

    private final Map<Key, Price> prices = new HashMap<>();
    private final Duration maxAge;
    private final Clock clock;

    public PriceBook(Collection<Price> rows, Duration maxAge, Clock clock) {
        this.maxAge = maxAge;
        this.clock = clock;
        for (Price p : rows) {
            prices.put(new Key(p.itemId(), p.city(), p.quality()), p);
        }
    }

    public static PriceBook merge(PriceBook a, PriceBook b) {
        PriceBook out = new PriceBook(a.prices.values(), a.maxAge, a.clock);
        b.prices.values().forEach(p -> out.prices.put(new Key(p.itemId(), p.city(), p.quality()), p));
        return out;
    }

    /** Cheapest sell order in {@code city}: the price to pay when buying instantly. */
    public OptionalInt sell(String itemId, String city, int quality) {
        Price p = prices.get(new Key(itemId, city, quality));
        if (p == null || p.sellMin() <= 0 || !fresh(p.sellMinDate())) return OptionalInt.empty();
        return OptionalInt.of(p.sellMin());
    }

    /** Highest buy order in {@code city}: the price received when selling instantly (Black Market). */
    public OptionalInt buy(String itemId, String city, int quality) {
        Price p = prices.get(new Key(itemId, city, quality));
        if (p == null || p.buyMax() <= 0 || !fresh(p.buyMaxDate())) return OptionalInt.empty();
        return OptionalInt.of(p.buyMax());
    }

    public Optional<Duration> sellAge(String itemId, String city, int quality) {
        Price p = prices.get(new Key(itemId, city, quality));
        return p == null || p.sellMinDate() == null ? Optional.empty() : Optional.of(age(p.sellMinDate()));
    }

    public Optional<Duration> buyAge(String itemId, String city, int quality) {
        Price p = prices.get(new Key(itemId, city, quality));
        return p == null || p.buyMaxDate() == null ? Optional.empty() : Optional.of(age(p.buyMaxDate()));
    }

    private boolean fresh(Instant date) {
        return date != null && age(date).compareTo(maxAge) <= 0;
    }

    private Duration age(Instant date) {
        return Duration.between(date, clock.instant());
    }
}
