package com.albionbot.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DemandTest {
    static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");

    @Test
    void averagesOnlyDaysWithDataInsideTheWindow() {
        List<Demand.Day> days = List.of(
                new Demand.Day(Instant.parse("2026-10-01T00:00:00Z"), 9999, 1),   // outside the 7-day window
                new Demand.Day(Instant.parse("2026-10-16T00:00:00Z"), 300, 4000),
                new Demand.Day(Instant.parse("2026-10-18T00:00:00Z"), 100, 5000)); // 10-17 missing, not zero
        Demand d = Demand.of("T4_BAG", days, NOW).orElseThrow();
        assertEquals(200, d.perDay(), 1e-9);
        assertEquals((300 * 4000 + 100 * 5000) / 400.0, d.avgPrice(), 1e-9);
        assertEquals(2, d.days());
    }

    @Test
    void noRecentSalesMeansNoDemand() {
        assertTrue(Demand.of("X", List.of(new Demand.Day(Instant.parse("2026-09-01T00:00:00Z"), 50, 10)), NOW).isEmpty());
        assertTrue(Demand.of("X", List.of(), NOW).isEmpty());
    }

    @Test
    void spikeIsFiftyPercentAboveAverage() {
        Demand d = new Demand("X", 10, 1000, 3);
        assertFalse(d.isSpike(1500));
        assertTrue(d.isSpike(1501));
    }

    @Test
    void parsesHistoryResponse() throws Exception {
        String json = """
                [{"location":"Black Market","item_id":"T4_BAG","quality":1,"data":[
                   {"item_count":523,"avg_price":4244,"timestamp":"2026-10-18T00:00:00"},
                   {"item_count":1016,"avg_price":4317,"timestamp":"2026-10-17T00:00:00"}]},
                 {"location":"Black Market","item_id":"T8_OLD","quality":1,"data":[
                   {"item_count":5,"avg_price":1,"timestamp":"2026-01-01T00:00:00"}]}]
                """;
        Map<String, Demand> m = MarketClient.parseHistory(new ObjectMapper().readTree(json), NOW);
        assertEquals(769.5, m.get("T4_BAG").perDay(), 1e-9);
        assertFalse(m.containsKey("T8_OLD"), "only sales inside the window count");
    }
}
