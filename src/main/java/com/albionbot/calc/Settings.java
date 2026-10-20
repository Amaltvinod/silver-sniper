package com.albionbot.calc;

/**
 * @param returnRate  fraction of returnable materials refunded (0.248 = 24.8%)
 * @param salesTax    market tax taken when selling (0.04 premium / 0.08 non-premium)
 * @param stationFee  silver per 100 nutrition at the crafting station
 */
public record Settings(double returnRate, double salesTax, double stationFee) {}
