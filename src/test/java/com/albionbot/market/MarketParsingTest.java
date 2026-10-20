package com.albionbot.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketParsingTest {
    static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");

    @Test
    void parsesApiRow() throws Exception {
        String json = """
                {"item_id":"T4_BAG","city":"Black Market","quality":1,
                 "sell_price_min":0,"sell_price_min_date":"0001-01-01T00:00:00",
                 "sell_price_max":0,"sell_price_max_date":"0001-01-01T00:00:00",
                 "buy_price_min":10,"buy_price_min_date":"2026-10-19T10:00:00",
                 "buy_price_max":4200,"buy_price_max_date":"2026-10-19T11:30:00"}
                """;
        Price p = MarketClient.parse(new ObjectMapper().readTree(json));
        assertEquals("T4_BAG", p.itemId());
        assertEquals("Black Market", p.city());
        assertEquals(4200, p.buyMax());
        assertEquals(Instant.parse("2026-10-19T11:30:00Z"), p.buyMaxDate());
        assertEquals(null, p.sellMinDate()); // 0001-... means never seen
    }

    @Test
    void parseDateHandlesGarbageAndZSuffix() {
        assertEquals(Instant.parse("2026-10-19T11:30:00Z"), MarketClient.parseDate("2026-10-19T11:30:00Z"));
        assertEquals(null, MarketClient.parseDate(""));
        assertEquals(null, MarketClient.parseDate("not a date"));
    }

    @Test
    void chunkingKeepsEveryIdAndLimitsRequestSize() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 500; i++) ids.add("T6_SOME_LONG_ITEM_NAME_" + i + "@1");
        List<List<String>> chunks = MarketClient.chunk(ids);
        assertTrue(chunks.size() > 1);
        int total = 0;
        for (List<String> c : chunks) {
            total += c.size();
            assertTrue(String.join(",", c).length() <= 3000);
        }
        assertEquals(500, total);
    }

    @Test
    void priceBookHidesStaleAndEmptyRows() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        List<Price> rows = List.of(
                new Price("A", "Martlock", 1, 100, NOW.minus(Duration.ofHours(2)), 0, null),
                new Price("B", "Martlock", 1, 100, NOW.minus(Duration.ofHours(25)), 0, null),
                new Price("C", "Martlock", 1, 0, NOW, 0, null),
                new Price("D", "Martlock", 1, 100, null, 0, null));
        PriceBook book = new PriceBook(rows, Duration.ofHours(24), clock);
        assertEquals(100, book.sell("A", "Martlock", 1).getAsInt());
        assertFalse(book.sell("B", "Martlock", 1).isPresent()); // too old
        assertFalse(book.sell("C", "Martlock", 1).isPresent()); // price 0
        assertFalse(book.sell("D", "Martlock", 1).isPresent()); // no date
        assertFalse(book.sell("A", "Thetford", 1).isPresent()); // other city
        assertFalse(book.sell("A", "Martlock", 2).isPresent()); // other quality
        assertEquals(120, book.sellAge("A", "Martlock", 1).orElseThrow().toMinutes());
    }
}
