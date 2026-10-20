package com.albionbot.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;

/**
 * Client for the Albion Online Data Project price endpoint ({@code /api/v2/stats/prices}).
 * Requests are chunked to stay under URL length limits, throttled to respect the public rate limit
 * (180/min), retried on 429/5xx, and cached for {@code cacheTtl}. Responses are requested gzipped:
 * the API throttles uncompressed requests with 429.
 */
public final class MarketClient {
    private static final int MAX_IDS_CHARS = 3000;
    private static final long MIN_GAP_MS = 400;

    private record Key(String itemId, String city, int quality) {}

    /** {@code price == null} records "asked, API had nothing", so we do not ask again until expiry. */
    private record Cached(Instant fetchedAt, Price price) {}

    /** Sales history changes once a day, so it is cached much longer than prices. */
    private static final Duration HISTORY_TTL = Duration.ofHours(1);

    /** {@code demand == null} records "no sales in the window". */
    private record CachedDemand(Instant fetchedAt, Demand demand) {}

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<Key, Cached> cache = new ConcurrentHashMap<>();
    private final Map<Key, CachedDemand> historyCache = new ConcurrentHashMap<>();
    private final String baseUrl;
    private final Duration maxAge;
    private final Duration cacheTtl;
    private final Clock clock;
    private long lastRequestAt = 0;

    public MarketClient(String baseUrl, Duration maxAge, Duration cacheTtl, Clock clock) {
        this.baseUrl = baseUrl;
        this.maxAge = maxAge;
        this.cacheTtl = cacheTtl;
        this.clock = clock;
    }

    public PriceBook fetch(Collection<String> itemIds, Collection<String> locations, Collection<Integer> qualities)
            throws IOException, InterruptedException {
        return fetch(itemIds, locations, qualities, maxAge);
    }

    /** As above, but rows older than {@code maxAge} (instead of the client default) read as absent. */
    public PriceBook fetch(Collection<String> itemIds, Collection<String> locations, Collection<Integer> qualities,
                           Duration maxAge) throws IOException, InterruptedException {
        Instant now = clock.instant();
        List<String> stale = new ArrayList<>();
        for (String id : new TreeSet<>(itemIds)) {
            if (needsRefresh(id, locations, qualities, now)) stale.add(id);
        }
        for (List<String> chunk : chunk(stale)) {
            for (JsonNode row : request(chunk, locations, qualities)) {
                Price p = parse(row);
                cache.put(new Key(p.itemId(), p.city(), p.quality()), new Cached(now, p));
            }
        }
        // Mark combinations the API did not return, so they are not re-requested every call.
        for (String id : stale) {
            for (String loc : locations) {
                for (int q : qualities) {
                    Key k = new Key(id, loc, q);
                    Cached c = cache.get(k);
                    if (c == null || c.fetchedAt().isBefore(now)) cache.put(k, new Cached(now, null));
                }
            }
        }

        List<Price> rows = new ArrayList<>();
        for (String id : itemIds) {
            for (String loc : locations) {
                for (int q : qualities) {
                    Cached c = cache.get(new Key(id, loc, q));
                    if (c != null && c.price() != null) rows.add(c.price());
                }
            }
        }
        return new PriceBook(rows, maxAge, clock);
    }

    private boolean needsRefresh(String id, Collection<String> locations, Collection<Integer> qualities, Instant now) {
        for (String loc : locations) {
            for (int q : qualities) {
                Cached c = cache.get(new Key(id, loc, q));
                if (c == null || Duration.between(c.fetchedAt(), now).compareTo(cacheTtl) > 0) return true;
            }
        }
        return false;
    }

    static List<List<String>> chunk(List<String> ids) {
        List<List<String>> out = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        int len = 0;
        for (String id : ids) {
            if (!cur.isEmpty() && len + id.length() + 1 > MAX_IDS_CHARS) {
                out.add(cur);
                cur = new ArrayList<>();
                len = 0;
            }
            cur.add(id);
            len += id.length() + 1;
        }
        if (!cur.isEmpty()) out.add(cur);
        return out;
    }

    private JsonNode request(List<String> ids, Collection<String> locations, Collection<Integer> qualities)
            throws IOException, InterruptedException {
        String locs = String.join(",", locations).replace(" ", "%20");
        StringBuilder qs = new StringBuilder();
        for (int q : qualities) qs.append(qs.length() == 0 ? "" : ",").append(q);
        URI uri = URI.create(baseUrl + "/api/v2/stats/prices/" + String.join(",", ids)
                + ".json?locations=" + locs + "&qualities=" + qs);
        return get(uri);
    }

    /** GET with gzip, throttling, and retries on 429/5xx. */
    private JsonNode get(URI uri) throws IOException, InterruptedException {
        for (int attempt = 0; attempt < 4; attempt++) {
            throttle();
            HttpResponse<InputStream> res = http.send(
                    HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
                            .header("Accept-Encoding", "gzip").GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            int code = res.statusCode();
            if (code == 200) {
                try (InputStream body = decode(res)) {
                    return mapper.readTree(body);
                }
            }
            res.body().close();
            if (code == 429 || code >= 500) {
                Thread.sleep(2000L << attempt);
                continue;
            }
            throw new IOException("Market API returned HTTP " + code + " for " + uri.getPath());
        }
        throw new IOException("Market API kept failing (rate limited or down): " + baseUrl);
    }

    /**
     * Daily sales of each item at {@code location} (usually the Black Market) over {@link Demand#WINDOW}.
     * Items with no recorded sales are absent from the result.
     */
    public Map<String, Demand> demand(Collection<String> itemIds, String location, int quality)
            throws IOException, InterruptedException {
        Instant now = clock.instant();
        List<String> stale = new ArrayList<>();
        for (String id : new TreeSet<>(itemIds)) {
            CachedDemand c = historyCache.get(new Key(id, location, quality));
            if (c == null || Duration.between(c.fetchedAt(), now).compareTo(HISTORY_TTL) > 0) stale.add(id);
        }
        for (List<String> chunk : chunk(stale)) {
            URI uri = URI.create(baseUrl + "/api/v2/stats/history/" + String.join(",", chunk)
                    + ".json?locations=" + location.replace(" ", "%20") + "&qualities=" + quality + "&time-scale=24");
            Map<String, Demand> got = parseHistory(get(uri), now);
            for (String id : chunk) {
                historyCache.put(new Key(id, location, quality), new CachedDemand(now, got.get(id)));
            }
        }
        Map<String, Demand> out = new java.util.HashMap<>();
        for (String id : itemIds) {
            CachedDemand c = historyCache.get(new Key(id, location, quality));
            if (c != null && c.demand() != null) out.put(id, c.demand());
        }
        return out;
    }

    static Map<String, Demand> parseHistory(JsonNode series, Instant now) {
        Map<String, Demand> out = new java.util.HashMap<>();
        for (JsonNode s : series) {
            List<Demand.Day> days = new ArrayList<>();
            for (JsonNode d : s.path("data")) {
                days.add(new Demand.Day(parseDate(d.path("timestamp").asText("")),
                        d.path("item_count").asLong(0), d.path("avg_price").asDouble(0)));
            }
            String id = s.path("item_id").asText();
            Optional<Demand> demand = Demand.of(id, days, now);
            demand.ifPresent(dm -> out.put(id, dm));
        }
        return out;
    }

    /** HttpClient does not decompress on its own. */
    private static InputStream decode(HttpResponse<InputStream> res) throws IOException {
        boolean gzip = res.headers().firstValue("Content-Encoding").map(e -> e.equalsIgnoreCase("gzip")).orElse(false);
        return gzip ? new GZIPInputStream(res.body()) : res.body();
    }

    private synchronized void throttle() throws InterruptedException {
        long wait = lastRequestAt + MIN_GAP_MS - System.currentTimeMillis();
        if (wait > 0) Thread.sleep(wait);
        lastRequestAt = System.currentTimeMillis();
    }

    static Price parse(JsonNode row) {
        return new Price(
                row.path("item_id").asText(),
                row.path("city").asText(),
                row.path("quality").asInt(1),
                row.path("sell_price_min").asInt(0),
                parseDate(row.path("sell_price_min_date").asText("")),
                row.path("buy_price_max").asInt(0),
                parseDate(row.path("buy_price_max_date").asText("")));
    }

    /** API timestamps are UTC without zone info; "0001-01-01T00:00:00" means never seen. */
    static Instant parseDate(String s) {
        if (s == null || s.isBlank() || s.startsWith("0001")) return null;
        try {
            return LocalDateTime.parse(s.endsWith("Z") ? s.substring(0, s.length() - 1) : s).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
