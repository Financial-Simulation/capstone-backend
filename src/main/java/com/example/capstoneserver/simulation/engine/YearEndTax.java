package com.example.capstoneserver.simulation.engine;

import com.example.capstoneserver.simulation.engine.EngineParams.Insurance;

/**
 * 근로소득 연말정산 (1인 가구, 2026년 귀속). 원 단위, 원 미만 절사. tax.py·engine.js와 같은 규칙이다.
 * 홈택스 「2026년 귀속 연말정산 자동계산」 24개 사례와 결정세액이 원 단위까지 같다 (기준 저장소 test_tax.py).
 * 세율·공제 구간은 법령 값이라 params.json이 아니라 여기에 둔다. 4대보험 요율만 params.json에서 받는다.
 */
final class YearEndTax {

    private static final double MAN = 10000;
    /** 기본세율 (소득세법 §55): {상한, 세율, 하한까지의 누적세액} */
    private static final double[][] BRACKETS = {
            {1400 * MAN, 0.06, 0}, {5000 * MAN, 0.15, 84 * MAN}, {8800 * MAN, 0.24, 624 * MAN},
            {15000 * MAN, 0.35, 1536 * MAN}, {30000 * MAN, 0.38, 3760 * MAN}, {50000 * MAN, 0.40, 9406 * MAN},
            {100000 * MAN, 0.42, 17406 * MAN}, {Double.POSITIVE_INFINITY, 0.45, 38406 * MAN}};

    private YearEndTax() {}

    /** 근로소득공제 (소득세법 §47), 한도 2,000만 원 */
    static double earnedDeduction(double g) {
        double d = g <= 500 * MAN ? g * 0.70
                : g <= 1500 * MAN ? 350 * MAN + (g - 500 * MAN) * 0.40
                : g <= 4500 * MAN ? 750 * MAN + (g - 1500 * MAN) * 0.15
                : g <= 10000 * MAN ? 1200 * MAN + (g - 4500 * MAN) * 0.05
                : 1475 * MAN + (g - 10000 * MAN) * 0.02;
        return Math.floor(Math.min(Math.min(d, 2000 * MAN), g));
    }

    static double basicTax(double base) {
        double lo = 0;
        for (double[] b : BRACKETS) {
            if (base <= b[0]) return Math.floor(b[2] + (base - lo) * b[1]);
            lo = b[0];
        }
        return 0;
    }

    /** 근로소득세액공제 (소득세법 §59) */
    static double earnedCredit(double calc, double g) {
        double c = calc <= 130 * MAN ? calc * 0.55 : 71.5 * MAN + (calc - 130 * MAN) * 0.30;
        double cap = g <= 3300 * MAN ? 74 * MAN
                : g <= 7000 * MAN ? Math.max(66 * MAN, 74 * MAN - (g - 3300 * MAN) * 0.008)
                : g <= 12000 * MAN ? Math.max(50 * MAN, 66 * MAN - (g - 7000 * MAN) * 0.5)
                : Math.max(20 * MAN, 50 * MAN - (g - 12000 * MAN) * 0.5);
        return Math.floor(Math.min(c, cap));
    }

    /**
     * 결정세액(소득세). 특별공제+월세 방식과 표준세액공제(13만 원) 방식 중 작은 쪽.
     * 청약 소득공제 40% (무주택 세대주·총급여 7,000만 이하, 연 300만 한도), 연금계좌 15%/12% (연 600만 한도),
     * 월세 17%/15% (무주택 세대주·총급여 8,000만 이하, 연 1,000만 한도).
     */
    static double decided(double g0, double nps0, double oth0, double housingPaid, double pensionPaid, double rentPaid, boolean homeless) {
        double g = Math.floor(g0), nps = Math.floor(nps0), oth = Math.floor(oth0);
        if (!(g > 0)) return 0;
        double income = g - earnedDeduction(g);
        double hd = (homeless && g <= 7000 * MAN) ? Math.floor(Math.min(housingPaid, 300 * MAN) * 0.40) : 0;
        double ps = Math.floor(Math.min(pensionPaid, 600 * MAN) * (g <= 5500 * MAN ? 0.15 : 0.12));
        double rc = (homeless && g <= 8000 * MAN) ? Math.floor(Math.min(rentPaid, 1000 * MAN) * (g <= 5500 * MAN ? 0.17 : 0.15)) : 0;
        double out = Double.POSITIVE_INFINITY;
        for (boolean special : new boolean[] {true, false}) {
            double base = Math.max(0, income - 150 * MAN - nps - (special ? oth : 0) - hd);
            double calc = basicTax(base);
            double eic = earnedCredit(calc, g);
            out = Math.min(out, Math.max(0, calc - eic - ps - (special ? rc : 13 * MAN)));
        }
        return out;
    }

    /** 실제로 내는 돈: 소득세 + 지방소득세(10%), 각각 10원 미만 절사 */
    static double payable(double decided) {
        return Math.floor(decided / 10) * 10 + Math.floor(Math.floor(decided / 10) / 10) * 10;
    }

    /** 연간 4대보험 본인부담 근사: 일한 달의 평균 월급 × 요율 → {국민연금, 건강·장기요양·고용} (원) */
    static double[] insurance(Insurance ins, double yearGross, double months) {
        double m = months > 0 ? yearGross / Math.max(months, 1) : 0;
        double nps = months > 0 ? Math.min(Math.max(m, ins.npsFloor()), ins.npsCap()) * ins.nps() * months : 0;
        return new double[] {nps, m * (ins.health() * (1 + ins.ltc()) + ins.employ()) * months};
    }
}
