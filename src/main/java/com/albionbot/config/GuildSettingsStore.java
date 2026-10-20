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
 * Per-guild settings, persisted as JSON ({@code guild id -> overrides}). Only fields a guild has set are stored,
 * so changing an env-var default still reaches every guild that never touched that field.
 */
public final class GuildSettingsStore {

    /** What a guild has explicitly set; null means "use the default". */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Overrides(String server, Boolean premium, Double stationFee, Double maxAgeHours, String returnRate) {
        static final Overrides NONE = new Overrides(null, null, null, null, null);

        /** Fields set in {@code o} win over ours. */
        public Overrides merge(Overrides o) {
            return new Overrides(
                    o.server != null ? o.server : server,
                    o.premium != null ? o.premium : premium,
                    o.stationFee != null ? o.stationFee : stationFee,
                    o.maxAgeHours != null ? o.maxAgeHours : maxAgeHours,
                    o.returnRate != null ? o.returnRate : returnRate);
        }

        GuildSettings applyTo(GuildSettings d) {
            return new GuildSettings(
                    server != null ? server : d.server(),
                    premium != null ? premium : d.premium(),
                    stationFee != null ? stationFee : d.stationFee(),
                    maxAgeHours != null ? maxAgeHours : d.maxAgeHours(),
                    returnRate != null ? returnRate : d.returnRate());
        }
    }

    private final Path path;
    private final GuildSettings defaults;
    private final Map<Long, Overrides> overrides = new ConcurrentHashMap<>();

    private GuildSettingsStore(Path path, GuildSettings defaults) {
        this.path = path;
        this.defaults = defaults;
    }

    /** Loads {@code path} if it exists; a missing file means no guild has changed anything yet. */
    public static GuildSettingsStore load(Path path, GuildSettings defaults) throws IOException {
        GuildSettingsStore store = new GuildSettingsStore(path, defaults);
        if (Files.exists(path)) {
            Map<String, Overrides> raw = JsonFiles.MAPPER.readValue(path.toFile(), new TypeReference<Map<String, Overrides>>() {});
            raw.forEach((id, o) -> store.overrides.put(Long.parseLong(id), o));
        }
        return store;
    }

    public GuildSettings defaults() {
        return defaults;
    }

    /** Effective settings for a guild; {@code guildId == null} (a DM) gets the defaults. */
    public GuildSettings get(Long guildId) {
        if (guildId == null) return defaults;
        return overrides.getOrDefault(guildId, Overrides.NONE).applyTo(defaults);
    }

    public Overrides overrides(long guildId) {
        return overrides.getOrDefault(guildId, Overrides.NONE);
    }

    public GuildSettings update(long guildId, Overrides change) {
        overrides.merge(guildId, change, Overrides::merge);
        save();
        return get(guildId);
    }

    public void reset(long guildId) {
        if (overrides.remove(guildId) != null) save();
    }

    private synchronized void save() {
        Map<String, Overrides> out = new TreeMap<>();
        overrides.forEach((id, o) -> out.put(Long.toString(id), o));
        JsonFiles.writeAtomic(path, out);
    }
}
