package com.albionbot.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Runtime configuration, read from environment variables. {@code server}, {@code maxAgeHours}, {@code premium} and
 * {@code stationFee} are only defaults: each Discord server can override them with /settings, and each player can
 * override premium and the station fee for themselves with /mysettings.
 *
 * @param stationFee  silver per 100 nutrition at the crafting station you use
 * @param devGuildIds guilds to register commands in directly (instant); empty = register globally
 */
public record Config(
        String token,
        String server,
        Path recipesPath,
        double maxAgeHours,
        boolean premium,
        double stationFee,
        Path guildsPath,
        Path accessPath,
        Path usersPath,
        List<Long> devGuildIds) {

    /** Premium accounts pay 4% market tax, others 8%. */
    public double salesTax() {
        return premium ? 0.04 : 0.08;
    }

    public String baseUrl() {
        return Game.SERVERS.get(server);
    }

    public static Config fromEnv() {
        String server = env("ALBION_SERVER", "east").toLowerCase();
        if (!Game.SERVERS.containsKey(server)) {
            throw new IllegalStateException(
                    "ALBION_SERVER must be one of " + Game.SERVERS.keySet() + ", got '" + server + "'");
        }
        List<Long> guilds = new ArrayList<>();
        for (String id : env("DEV_GUILD_ID", "").split(",")) {
            if (!id.isBlank()) guilds.add(Long.parseLong(id.trim()));
        }
        return new Config(
                env("DISCORD_TOKEN", ""),
                server,
                Path.of(env("RECIPES_PATH", "data/recipes.json")),
                Double.parseDouble(env("MAX_DATA_AGE_HOURS", "24")),
                Boolean.parseBoolean(env("PREMIUM", "true")),
                Double.parseDouble(env("STATION_FEE", "500")),
                Path.of(env("GUILDS_PATH", "data/guilds.json")),
                Path.of(env("ACCESS_PATH", "data/access.json")),
                Path.of(env("USERS_PATH", "data/users.json")),
                List.copyOf(guilds));
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }
}
