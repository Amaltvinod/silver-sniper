package com.albionbot.access;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessStoreTest {
    static final long SERVER = 1561789852691730523L;
    static final long ALICE = 100L, BOB = 200L;
    static final AccessStore.Link DGC = new AccessStore.Link("east", "AqfO1SY5Rg6kSTjIZcVO3g", "Dont Get Caught", 1_000);

    @TempDir
    Path dir;

    @Test
    void unlinkedServerHasNoLinkAndRejectsMembers() throws Exception {
        AccessStore store = AccessStore.load(dir.resolve("access.json"));
        assertTrue(store.link(SERVER).isEmpty());
        assertThrows(IllegalStateException.class,
                () -> store.putMember(SERVER, ALICE, new AccessStore.Member("p1", "Alice", 1)));
    }

    @Test
    void registrationsSurviveRestart() throws Exception {
        Path file = dir.resolve("access.json");
        AccessStore store = AccessStore.load(file);
        store.setLink(SERVER, DGC);
        store.putMember(SERVER, ALICE, new AccessStore.Member("p1", "Alice", 2_000));

        AccessStore reloaded = AccessStore.load(file);
        assertEquals(Optional.of(DGC), reloaded.link(SERVER));
        assertEquals("Alice", reloaded.member(SERVER, ALICE).orElseThrow().playerName());
        assertEquals(Optional.of(ALICE), reloaded.ownerOf(SERVER, "p1"));
        assertTrue(reloaded.member(SERVER, BOB).isEmpty());
    }

    @Test
    void relinkingSameGuildKeepsMembersButDifferentGuildClearsThem() throws Exception {
        AccessStore store = AccessStore.load(dir.resolve("access.json"));
        store.setLink(SERVER, DGC);
        store.putMember(SERVER, ALICE, new AccessStore.Member("p1", "Alice", 1));

        store.setLink(SERVER, new AccessStore.Link("east", DGC.guildId(), DGC.guildName(), 5_000));
        assertTrue(store.member(SERVER, ALICE).isPresent(), "same guild: registrations still valid");

        store.setLink(SERVER, new AccessStore.Link("east", "OTHER", "Other Guild", 6_000));
        assertTrue(store.member(SERVER, ALICE).isEmpty(), "new guild: old checks no longer apply");
    }

    @Test
    void removeAndUnlink() throws Exception {
        Path file = dir.resolve("access.json");
        AccessStore store = AccessStore.load(file);
        store.setLink(SERVER, DGC);
        store.putMember(SERVER, ALICE, new AccessStore.Member("p1", "Alice", 1));
        store.putMember(SERVER, BOB, new AccessStore.Member("p2", "Bob", 2));

        assertTrue(store.removeMember(SERVER, ALICE));
        assertFalse(store.removeMember(SERVER, ALICE));
        assertEquals(1, store.members(SERVER).size());

        store.unlink(SERVER);
        assertTrue(AccessStore.load(file).link(SERVER).isEmpty());
        assertTrue(AccessStore.load(file).members(SERVER).isEmpty());
    }

    @Test
    void parsesGameInfoPlayerIncludingNoGuild() throws Exception {
        ObjectMapper m = new ObjectMapper();
        GameInfoClient.AlbionPlayer inGuild = GameInfoClient.parsePlayer(m.readTree("""
                {"Id":"RL8ipezWT727w8HKBt3sUQ","Name":"Gridden","GuildId":"AqfO1SY5Rg6kSTjIZcVO3g",
                 "GuildName":"Dont Get Caught","AllianceId":"","KillFame":6374192}"""));
        assertEquals("AqfO1SY5Rg6kSTjIZcVO3g", inGuild.guildId());
        assertEquals("Dont Get Caught", inGuild.guildName());

        // Real responses use "" or null for "no guild".
        GameInfoClient.AlbionPlayer none = GameInfoClient.parsePlayer(m.readTree("""
                {"Id":"UC8xtL8WTP-kPWwP3VQsig","Name":"Gridden","GuildId":"","GuildName":null}"""));
        assertEquals("", none.guildId());
        assertEquals("", none.guildName());
    }
}
