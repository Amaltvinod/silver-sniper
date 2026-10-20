package com.albionbot.market;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Recent sales of one item at one market, from the Data Project's daily history.
 * Days nobody captured are missing rather than zero, so the per-day figure averages only the days that have data.
 *
 * @param perDay   average items sold per day, over {@code days}
 * @param avgPrice average sale price, weighted by count
 * @param days     how many of the last {@link #WINDOW} days had data
 */
public record Demand(String itemId, double perDay, double avgPrice, int days) {
    public static final Duration WINDOW = Duration.ofDays(7);
    /** A Black Market order this far above the recent average is likely a one-off. */
    public static final double SPIKE_FACTOR = 1.5;

    /** One daily history point. */
    public record Day(Instant date, long count, double avgPrice) {}

    /** Empty when no day in the window has sales. */
    public static Optional<Demand> of(String itemId, List<Day> history, Instant now) {
        long count = 0;
        double value = 0;
        int days = 0;
        for (Day d : history) {
            if (d.date() == null || d.date().isBefore(now.minus(WINDOW)) || d.count() <= 0) continue;
            count += d.count();
            value += d.count() * d.avgPrice();
            days++;
        }
        if (days == 0) return Optional.empty();
        return Optional.of(new Demand(itemId, (double) count / days, value / count, days));
    }

    /** True when {@code price} is well above what the item has actually been selling for. */
    public boolean isSpike(int price) {
        return avgPrice > 0 && price > avgPrice * SPIKE_FACTOR;
    }
}
