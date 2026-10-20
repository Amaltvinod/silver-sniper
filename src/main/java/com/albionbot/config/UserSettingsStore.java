package com.albionbot.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player settings set with /mysettings, persisted as JSON ({@code Discord user id -> overrides}). They follow the
 * player to every server: premium belongs to their Albion account and the station fee to where they craft. Anything
 * a player has not set falls back to the server's settings.
 */
public final class UserSettingsStore {

    /** What a player has explicitly set; null means "use this server's setting". */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Overrides(Boolean premium, Double stationFee) {
        public static final Overrides NONE = new Overrides(null, null);

        /** Fields set in {@code o} win over ours. */
        Overrides merge(Overrides o) {
            return new Overrides(
                    o.premium != null ? o.premium : premium,
                    o.stationFee != null ? o.stationFee : stationFee);
        }

        public GuildSettings applyTo(GuildSettings g) {
            return new GuildSettings(
                    g.server(),
                    premium != null ? premium : g.premium(),
                    stationFee != null ? stationFee : g.stationFee(),
                    g.maxAgeHours(),
                    g.returnRate());
        }
    }

    private final Path path;
    private final Map<Long, Overrides> overrides = new ConcurrentHashMap<>();

    private UserSettingsStore(Path path) {
        this.path = path;
    }

    /** Loads {@code path} if it exists; a missing file means no player has set anything yet. */
    public static UserSettingsStore load(Path path) throws IOException {
        UserSettingsStore store = new UserSettingsStore(path);
        if (Files.exists(path)) {
            Map<String, Overrides> raw = JsonFiles.MAPPER.readValue(path.toFile(), new TypeReference<Map<String, Overrides>>() {});
            raw.forEach((id, o) -> store.overrides.put(Long.parseLong(id), o));
        }
        return store;
    }

    public Overrides get(long userId) {
        return overrides.getOrDefault(userId, Overrides.NONE);
    }

    /** {@code serverSettings} with this player's own settings on top. */
    public GuildSettings apply(long userId, GuildSettings serverSettings) {
        return get(userId).applyTo(serverSettings);
    }

    public void update(long userId, Overrides change) {
        overrides.merge(userId, change, Overrides::merge);
        save();
    }

    public void reset(long userId) {
        if (overrides.remove(userId) != null) save();
    }

    private synchronized void save() {
        Map<String, Overrides> out = new TreeMap<>();
        overrides.forEach((id, o) -> out.put(Long.toString(id), o));
        JsonFiles.writeAtomic(path, out);
    }
}
