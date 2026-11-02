package com.example.capstoneserver.simulation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * 엔진 결과 → 응답 값 반올림 (구현 명세서 2.4). 반올림 방식이 값마다 달라 정답 파일과 맞추려면 그대로 따라야 한다.
 */
final class Rounding {

    private Rounding() {}

    /** 만 원 double → 원. 금액만 new BigDecimal을 쓴다 */
    static long won(double man) {
        return new BigDecimal(man * 10000).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /** 개수(정수) → %, 소수 1자리. double로 계산하지 않는다 (0.2885 × 100 = 28.8499… 문제) */
    static BigDecimal percent(long count, long total) {
        return BigDecimal.valueOf(count * 100).divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_UP);
    }

    /** double에서 바로 반올림하는 값 (오차·z·가능성 범위). 98.05가 98.0이 되지 않게 valueOf를 쓴다 */
    static BigDecimal decimal(double value, int scale) {
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP);
    }

    /** 화면에 보이는 소수 1자리 확률 → 정수 (74.7 → 75) */
    static long wholePercent(BigDecimal percent) {
        return percent.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /** 첫 문장용 금액: 4,440만 원 / 1억 2,745만 원 / 1억 원 / −500만 원 (명세서 7.6) */
    static String koreanMan(double man) {
        long rounded = BigDecimal.valueOf(man).setScale(0, RoundingMode.HALF_UP).longValueExact();
        long abs = Math.abs(rounded);
        long eok = abs / 10000;
        long rest = abs % 10000;
        NumberFormat comma = NumberFormat.getNumberInstance(Locale.KOREA);

        String text;
        if (eok == 0) {
            text = comma.format(rest) + "만 원";
        } else if (rest == 0) {
            text = eok + "억 원";
        } else {
            text = eok + "억 " + comma.format(rest) + "만 원";
        }
        return (rounded < 0 ? "−" : "") + text;
    }
}
