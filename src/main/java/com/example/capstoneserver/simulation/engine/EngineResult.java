package com.example.capstoneserver.simulation.engine;

/**
 * 엔진 한 번의 결과.
 *
 * @param saved     경로별 모은 돈 (경로 × (T + 1), 한 줄로 펼침). collect를 껐으면 null
 * @param flags     [플래그][경로] 한 번이라도 켜졌으면 1
 * @param firstBrk  청년미래적금을 처음 해지한 달 (없으면 -1)
 * @param matchPaid 실제 받은 지원금 (만 원)
 * @param tax       [항목][경로] 누계 (만 원). 항목 순서는 TaxItem
 * @param alloc     실제 월 배분 (만 원): 청년미래, 청약, 연금저축. 합이 저축액을 넘으면 뒤 상품을 줄인 값
 */
public record EngineResult(double[] saved, byte[][] flags, int[] firstBrk, double[] matchPaid, double[][] tax,
                           Allocation alloc, boolean eligible, int n, int t) {

    public enum Flag {
        UNEMP, OVERDRAFT, BRK1_SP, BRK1_GEN, BRK_G, MATURE1, LIMIT, REJOIN, C1_SP, C1_UNEMP, C1_SHOCK, C1_DEFICIT,
        BRK_H, BRK_PEN
    }

    /** 돌려받은 세금(연말정산 환급), 낸 이자소득세, 청약 추징, 연금저축 기타소득세 */
    public enum TaxItem { REFUND, INTEREST_TAX, CLAWBACK, PENSION_TAX }

    public record Allocation(double policy, double housing, double pension) {}

    /** 경로 p의 m개월 뒤 모은 돈 */
    public double at(int p, int m) {
        return saved[p * (t + 1) + m];
    }

    public int count(Flag flag) {
        int sum = 0;
        for (byte b : flags[flag.ordinal()]) sum += b;
        return sum;
    }

    /** 경로 평균 (만 원) */
    public double meanTax(TaxItem item) {
        double sum = 0;
        for (double v : tax[item.ordinal()]) sum += v;
        return sum / n;
    }
}
