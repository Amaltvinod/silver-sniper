package com.albionbot.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class UserSettingsStoreTest {
    static final GuildSettings SERVER = new GuildSettings("east", true, 500, 24, "city");
    static final long USER_A = 333333333333333333L;
    static final long USER_B = 444444444444444444L;

    @TempDir
    Path dir;

    @Test
    void playersWithoutSettingsGetTheServersSettings() throws Exception {
        UserSettingsStore store = UserSettingsStore.load(dir.resolve("users.json"));
        assertEquals(SERVER, store.apply(USER_A, SERVER));
        assertFalse(Files.exists(dir.resolve("users.json")), "nothing written until a player changes something");
    }

    @Test
    void ownSettingsWinAndOnlyTouchPremiumAndStationFee() throws Exception {
        UserSettingsStore store = UserSettingsStore.load(dir.resolve("users.json"));
        store.update(USER_A, new UserSettingsStore.Overrides(false, null));
        store.update(USER_A, new UserSettingsStore.Overrides(null, 1200.0));
        store.update(USER_B, new UserSettingsStore.Overrides(null, 300.0));

        assertEquals(new GuildSettings("east", false, 1200, 24, "city"), store.apply(USER_A, SERVER));
        assertEquals(0.08, store.apply(USER_A, SERVER).salesTax());
        assertEquals(new GuildSettings("east", true, 300, 24, "city"), store.apply(USER_B, SERVER));
    }

    @Test
    void survivesRestartAndResets() throws Exception {
        Path file = dir.resolve("users.json");
        UserSettingsStore store = UserSettingsStore.load(file);
        store.update(USER_A, new UserSettingsStore.Overrides(false, null));
        assertFalse(Files.readString(file).contains("stationFee"), "unset fields are not written");

        UserSettingsStore reloaded = UserSettingsStore.load(file);
        assertEquals(new UserSettingsStore.Overrides(false, null), reloaded.get(USER_A));

        reloaded.reset(USER_A);
        assertEquals(SERVER, UserSettingsStore.load(file).apply(USER_A, SERVER));
    }
}
