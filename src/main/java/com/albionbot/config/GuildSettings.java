package com.albionbot.config;

import java.time.Duration;

/**
 * The settings one Discord server (guild) runs with: its own overrides on top of the env-var defaults.
 *
 * @param server      Albion Data Project server key: west, east or europe
 * @param stationFee  silver per 100 nutrition at the crafting station the guild uses
 * @param returnRate  key into {@link Game#RETURN_PRESETS}, used when a command has no rrr option
 */
public record GuildSettings(
        String server,
        boolean premium,
        double stationFee,
        double maxAgeHours,
        String returnRate) {

    public static GuildSettings defaults(Config config) {
        return new GuildSettings(config.server(), config.premium(), config.stationFee(), config.maxAgeHours(), "city");
    }

    /** Premium accounts pay 4% market tax, others 8%. */
    public double salesTax() {
        return premium ? 0.04 : 0.08;
    }

    public Duration maxAge() {
        return Duration.ofMinutes((long) (maxAgeHours * 60));
    }

    public GuildSettings withServer(String server) {
        return new GuildSettings(server, premium, stationFee, maxAgeHours, returnRate);
    }
}
