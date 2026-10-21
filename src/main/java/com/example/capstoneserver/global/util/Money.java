package com.example.capstoneserver.global.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * 금액 관련 공통 도구.
 */
public final class Money {

    private Money() {}

    /** 원 단위 반올림 (HALF_UP) */
    public static long roundWon(double value) {
        return BigDecimal.valueOf(value).setScale(0, RoundingMode.HALF_UP).longValue();
    }

    /**
     * 사용자에게 보여 줄 금액 문장.
     * 465161 → "46만 5,161원", 500000 → "50만 원", 3000 → "3,000원"
     */
    public static String toKorean(long won) {
        NumberFormat comma = NumberFormat.getNumberInstance(Locale.KOREA);
        String sign = won < 0 ? "-" : "";
        long abs = Math.abs(won);
        long man = abs / 10_000;
        long rest = abs % 10_000;

        if (man == 0) return sign + comma.format(rest) + "원";
        if (rest == 0) return sign + comma.format(man) + "만 원";
        return sign + comma.format(man) + "만 " + comma.format(rest) + "원";
    }
}
