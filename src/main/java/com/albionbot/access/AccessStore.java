package com.albionbot.access;

import com.albionbot.config.JsonFiles;
import com.fasterxml.jackson.core.type.TypeReference;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which Albion guild each Discord server is locked to, and which Discord users registered as which character.
 * A server with no link is open to everyone. Persisted as JSON keyed by Discord server id.
 */
public final class AccessStore {

    /**
     * @param server   Albion server key the guild lives on (west, east, europe)
     * @param linkedAt epoch seconds
     */
    public record Link(String server, String guildId, String guildName, long linkedAt) {}

    /** @param verifiedAt epoch seconds of the last time the game-info API confirmed this character was in the guild */
    public record Member(String playerId, String playerName, long verifiedAt) {}

    /** Everything stored for one Discord server. */
    public record Entry(Link link, Map<String, Member> members) {
        public Entry {
            members = members == null ? new TreeMap<>() : new TreeMap<>(members);
        }
    }

    private final Path path;
    private final Map<Long, Entry> entries = new ConcurrentHashMap<>();

    private AccessStore(Path path) {
        this.path = path;
    }

    public static AccessStore load(Path path) throws IOException {
        AccessStore store = new AccessStore(path);
        if (Files.exists(path)) {
            Map<String, Entry> raw = JsonFiles.MAPPER.readValue(path.toFile(), new TypeReference<Map<String, Entry>>() {});
            raw.forEach((id, e) -> store.entries.put(Long.parseLong(id), e));
        }
        return store;
    }

    public Optional<Link> link(long guildId) {
        Entry e = entries.get(guildId);
        return e == null ? Optional.empty() : Optional.ofNullable(e.link());
    }

    /** Linking to a different Albion guild drops the old registrations: they were checked against the old guild. */
    public synchronized void setLink(long guildId, Link link) {
        Entry old = entries.get(guildId);
        boolean sameGuild = old != null && old.link() != null && old.link().guildId().equals(link.guildId());
        entries.put(guildId, new Entry(link, sameGuild ? old.members() : Map.of()));
        save();
    }

    public synchronized void unlink(long guildId) {
        if (entries.remove(guildId) != null) save();
    }

    public Optional<Member> member(long guildId, long userId) {
        Entry e = entries.get(guildId);
        return e == null ? Optional.empty() : Optional.ofNullable(e.members().get(Long.toString(userId)));
    }

    /** The Discord user who registered {@code playerId} in this server, if any. */
    public Optional<Long> ownerOf(long guildId, String playerId) {
        Entry e = entries.get(guildId);
        if (e == null) return Optional.empty();
        return e.members().entrySet().stream()
                .filter(m -> m.getValue().playerId().equals(playerId))
                .map(m -> Long.parseLong(m.getKey()))
                .findFirst();
    }

    /** Registered members, longest-standing first. */
    public List<Map.Entry<Long, Member>> members(long guildId) {
        Entry e = entries.get(guildId);
        if (e == null) return List.of();
        List<Map.Entry<Long, Member>> out = new ArrayList<>();
        e.members().forEach((uid, m) -> out.add(Map.entry(Long.parseLong(uid), m)));
        out.sort(Comparator.comparingLong(m -> m.getValue().verifiedAt()));
        return out;
    }

    public synchronized void putMember(long guildId, long userId, Member member) {
        Entry e = entries.get(guildId);
        if (e == null || e.link() == null) throw new IllegalStateException("Server " + guildId + " has no linked guild");
        e.members().put(Long.toString(userId), member);
        save();
    }

    public synchronized boolean removeMember(long guildId, long userId) {
        Entry e = entries.get(guildId);
        if (e == null || e.members().remove(Long.toString(userId)) == null) return false;
        save();
        return true;
    }

    private void save() {
        Map<String, Entry> out = new TreeMap<>();
        entries.forEach((id, e) -> out.put(Long.toString(id), e));
        JsonFiles.writeAtomic(path, out);
    }
}
