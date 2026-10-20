package com.albionbot.calc;

/**
 * Buy a finished item in a city, sell it to a Black Market buy order.
 *
 * @param bmFromHistory {@code blackMarketPrice} is the 7-day average sale price, because the buy order was old or missing
 */
public record FlipResult(
        String itemId,
        int quality,
        String buyCity,
        int buyPrice,
        int blackMarketPrice,
        double netRevenue,
        double profit,
        double roi,
        boolean bmFromHistory) {}
