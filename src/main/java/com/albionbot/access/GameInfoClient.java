package com.albionbot.access;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

/**
 * Albion Online's official game-info API (the one behind the killboard): looks up guilds and characters,
 * so the bot can check that a character really is in a guild. It is slow at times (10s+), so callers run it off
 * the event thread.
 */
public final class GameInfoClient {
    /** Game-info hosts per Albion server key, matching {@code Game.SERVERS}. */
    public static final Map<String, String> HOSTS = Map.of(
            "west", "https://gameinfo.albiononline.com/api/gameinfo",
            "east", "https://gameinfo-sgp.albiononline.com/api/gameinfo",
            "europe", "https://gameinfo-ams.albiononline.com/api/gameinfo");

    public record AlbionGuild(String id, String name) {}

    /** {@code guildId} is empty when the character is in no guild. */
    public record AlbionPlayer(String id, String name, String guildId, String guildName) {}

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    /** Guilds whose name matches {@code name} exactly, ignoring case. */
    public List<AlbionGuild> findGuilds(String server, String name) throws IOException, InterruptedException {
        List<AlbionGuild> out = new ArrayList<>();
        for (JsonNode g : search(server, name).path("guilds")) {
            AlbionGuild guild = new AlbionGuild(g.path("Id").asText(), g.path("Name").asText());
            if (guild.name().equalsIgnoreCase(name.trim())) out.add(guild);
        }
        return out;
    }

    /** Characters whose name matches {@code name} exactly, ignoring case (names can repeat after renames/deletions). */
    public List<AlbionPlayer> findPlayers(String server, String name) throws IOException, InterruptedException {
        List<AlbionPlayer> out = new ArrayList<>();
        for (JsonNode p : search(server, name).path("players")) {
            AlbionPlayer player = parsePlayer(p);
            if (player.name().equalsIgnoreCase(name.trim())) out.add(player);
        }
        return out;
    }

    /** Current state of one character, by id. Empty if the API does not know it any more. */
    public Optional<AlbionPlayer> player(String server, String playerId) throws IOException, InterruptedException {
        JsonNode p = get(server, "/players/" + URLEncoder.encode(playerId, StandardCharsets.UTF_8));
        return p == null ? Optional.empty() : Optional.of(parsePlayer(p));
    }

    static AlbionPlayer parsePlayer(JsonNode p) {
        return new AlbionPlayer(
                p.path("Id").asText(),
                p.path("Name").asText(),
                p.path("GuildId").asText(""),
                p.path("GuildName").isNull() ? "" : p.path("GuildName").asText(""));
    }

    private JsonNode search(String server, String q) throws IOException, InterruptedException {
        JsonNode res = get(server, "/search?q=" + URLEncoder.encode(q.trim(), StandardCharsets.UTF_8).replace("+", "%20"));
        return res == null ? mapper.createObjectNode() : res;
    }

    /** GET with gzip and a couple of retries; null on 404. */
    private JsonNode get(String server, String path) throws IOException, InterruptedException {
        String base = HOSTS.get(server);
        if (base == null) throw new IllegalArgumentException("Unknown Albion server: " + server);
        URI uri = URI.create(base + path);
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                HttpResponse<InputStream> res = http.send(
                        HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
                                .header("Accept-Encoding", "gzip").GET().build(),
                        HttpResponse.BodyHandlers.ofInputStream());
                int code = res.statusCode();
                if (code == 200) {
                    boolean gzip = res.headers().firstValue("Content-Encoding").map("gzip"::equalsIgnoreCase).orElse(false);
                    try (InputStream body = gzip ? new GZIPInputStream(res.body()) : res.body()) {
                        return mapper.readTree(body);
                    }
                }
                res.body().close();
                if (code == 404) return null;
                last = new IOException("Albion game-info API returned HTTP " + code);
                if (code != 429 && code < 500) throw last;
            } catch (java.net.http.HttpTimeoutException e) {
                last = e;
            }
            Thread.sleep(1500L << attempt);
        }
        throw last;
    }
}
