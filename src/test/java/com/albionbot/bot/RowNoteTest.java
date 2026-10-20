package com.albionbot.bot;

import com.albionbot.market.Demand;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RowNoteTest {
    @Test
    void flagsOldSpikeAndRare() {
        Demand busy = new Demand("X", 50, 1000, 7);
        assertEquals("", CommandHandler.rowNote(false, false, busy, 1100));
        assertEquals("O", CommandHandler.rowNote(true, false, busy, 1100));
        assertEquals("S", CommandHandler.rowNote(false, false, busy, 2000));
        assertEquals("R", CommandHandler.rowNote(false, false, null, 1000));
        assertEquals("OSR", CommandHandler.rowNote(true, false, new Demand("X", 0.5, 1000, 1), 2000));
    }

    @Test
    void historyPricesAreFlaggedAndNeverCalledSpikes() {
        Demand busy = new Demand("X", 50, 1000, 7);
        assertEquals("H", CommandHandler.rowNote(false, true, busy, 1000));
        assertEquals("OH", CommandHandler.rowNote(true, true, busy, 1000), "old material price + history BM price");
        assertEquals("HR", CommandHandler.rowNote(false, true, new Demand("X", 0.5, 1000, 3), 1000));
    }

    @Test
    void staleNoticeOnlyWhenNothingIsFresh() {
        assertEquals("", CommandHandler.staleNotice(3, 10), "some fresh rows: they are ranked first, no banner");
        assertEquals("", CommandHandler.staleNotice(10, 10));
        assertEquals("", CommandHandler.staleNotice(0, 0), "empty list has its own message");
        org.junit.jupiter.api.Assertions.assertTrue(CommandHandler.staleNotice(0, 4).contains("/scan"));
    }

    @Test
    void topFlipRowFitsWithoutWrapping() {
        // Widest realistic /topflip row: long name, 7-figure prices, a big loss, every flag.
        String table = Fmt.table(
                java.util.List.of("Item", "In", "Cost", "BM", "Profit", "/day", "Note"),
                java.util.List.of(java.util.List.of(Fmt.trunc("Elder's Bloodletter", 14), CommandHandler.shortCity("Fort Sterling"),
                        Fmt.silver(1_250_000), Fmt.silver(1_990_000), Fmt.signed(-1_250_000), Fmt.perDay(1520), "OSR")),
                1);
        for (String line : table.split("\n")) {
            org.junit.jupiter.api.Assertions.assertTrue(line.length() <= 52, line.length() + " chars: " + line);
        }
    }
}
