package com.albionbot.market;

import java.time.Instant;

/**
 * One market row. {@code sellMin} is the cheapest sell order (what you pay to buy instantly);
 * {@code buyMax} is the highest buy order (what you receive when selling instantly, e.g. to the Black Market).
 * A value of 0 means no data; dates are null when unknown.
 */
public record Price(
        String itemId,
        String city,
        int quality,
        int sellMin,
        Instant sellMinDate,
        int buyMax,
        Instant buyMaxDate) {}
