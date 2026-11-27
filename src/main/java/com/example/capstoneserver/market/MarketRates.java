package com.example.capstoneserver.market;

import java.math.BigDecimal;
import java.util.stream.Stream;

/**
 * 한국은행 ECOS에서 받은 최신 금리 (연 %, 한국은행 발표값 그대로). 항목마다 값과 기준 달이 있다.
 * 받지 못한 항목은 null이고, 그 항목은 params.json·코드 기본값을 그대로 쓴다.
 */
public record MarketRates(
        Rate liquid,
        Rate generalSaving,
        Rate overdraft,
        Rate mortgage,
        Rate credit,
        Rate jeonse
) {

    /** 값 (연 %)과 기준 달 (YYYY-MM) */
    public record Rate(BigDecimal value, String month) {}

    public static MarketRates empty() {
        return new MarketRates(null, null, null, null, null, null);
    }

    /** 항목 하나를 바꾼 사본 */
    public MarketRates with(EcosSeries series, Rate rate) {
        return new MarketRates(
                series == EcosSeries.LIQUID ? rate : liquid,
                series == EcosSeries.GENERAL_SAVING ? rate : generalSaving,
                series == EcosSeries.OVERDRAFT ? rate : overdraft,
                series == EcosSeries.MORTGAGE ? rate : mortgage,
                series == EcosSeries.CREDIT ? rate : credit,
                series == EcosSeries.JEONSE ? rate : jeonse);
    }

    /** 주어진 항목 가운데 가장 최근 기준 달 (YYYY-MM). 받은 항목이 없으면 null */
    public static String latestMonth(Rate... rates) {
        return Stream.of(rates)
                .filter(r -> r != null && r.month() != null)
                .map(Rate::month)
                .max(String::compareTo)
                .orElse(null);
    }
}
