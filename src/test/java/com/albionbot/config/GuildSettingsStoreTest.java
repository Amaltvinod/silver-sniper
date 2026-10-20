package com.albionbot.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuildSettingsStoreTest {
    static final GuildSettings DEFAULTS = new GuildSettings("east", true, 500, 24, "city");
    static final long GUILD_A = 111111111111111111L;
    static final long GUILD_B = 222222222222222222L;

    @TempDir
    Path dir;

    @Test
    void unknownGuildAndDmsGetDefaults() throws Exception {
        GuildSettingsStore store = GuildSettingsStore.load(dir.resolve("guilds.json"), DEFAULTS);
        assertEquals(DEFAULTS, store.get(GUILD_A));
        assertEquals(DEFAULTS, store.get(null));
        assertFalse(Files.exists(dir.resolve("guilds.json")), "nothing written until a guild changes something");
    }

    @Test
    void guildsAreIndependentAndChangesMerge() throws Exception {
        GuildSettingsStore store = GuildSettingsStore.load(dir.resolve("guilds.json"), DEFAULTS);
        store.update(GUILD_A, new GuildSettingsStore.Overrides("europe", null, null, null, null));
        store.update(GUILD_A, new GuildSettingsStore.Overrides(null, false, 800.0, null, null));
        store.update(GUILD_B, new GuildSettingsStore.Overrides("west", null, null, 6.0, "focus"));

        assertEquals(new GuildSettings("europe", false, 800, 24, "city"), store.get(GUILD_A));
        assertEquals(new GuildSettings("west", true, 500, 6, "focus"), store.get(GUILD_B));
        assertEquals(0.08, store.get(GUILD_A).salesTax());
    }

    @Test
    void survivesRestartAndStoresOnlyOverrides() throws Exception {
        Path file = dir.resolve("guilds.json");
        GuildSettingsStore store = GuildSettingsStore.load(file, DEFAULTS);
        store.update(GUILD_A, new GuildSettingsStore.Overrides("europe", null, null, null, null));

        String json = Files.readString(file);
        assertTrue(json.contains("\"111111111111111111\""), json);
        assertFalse(json.contains("stationFee"), "unset fields are not written: " + json);

        // New default station fee reaches the guild, because it never set its own.
        GuildSettings newDefaults = new GuildSettings("east", true, 650, 24, "city");
        GuildSettingsStore reloaded = GuildSettingsStore.load(file, newDefaults);
        assertEquals(new GuildSettings("europe", true, 650, 24, "city"), reloaded.get(GUILD_A));
    }

    @Test
    void resetGoesBackToDefaults() throws Exception {
        Path file = dir.resolve("guilds.json");
        GuildSettingsStore store = GuildSettingsStore.load(file, DEFAULTS);
        store.update(GUILD_A, new GuildSettingsStore.Overrides("west", false, null, null, null));
        store.reset(GUILD_A);
        assertEquals(DEFAULTS, store.get(GUILD_A));
        assertEquals(DEFAULTS, GuildSettingsStore.load(file, DEFAULTS).get(GUILD_A));
    }
}
