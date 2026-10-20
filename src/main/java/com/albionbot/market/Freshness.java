package com.albionbot.market;

import java.time.Duration;
import java.util.Optional;

/**
 * How old a price can get before it has probably changed in game. Black Market buy orders fill fastest, cheap city
 * gear listings get bought out by other flippers, and bulk material prices barely move.
 */
public final class Freshness {
    private Freshness() {}

    public static final Duration BLACK_MARKET = Duration.ofHours(3);
    public static final Duration CITY_GEAR = Duration.ofHours(6);
    public static final Duration MATERIALS = Duration.ofHours(24);

    /** Shown under tables that flag stale rows. */
    public static final String LIMITS = "Black Market >3h, city gear >6h, materials >24h";

    /** True when the price was never seen ({@code age} empty) or is older than {@code limit}. */
    public static boolean stale(Optional<Duration> age, Duration limit) {
        return age.isEmpty() || age.get().compareTo(limit) > 0;
    }
}
