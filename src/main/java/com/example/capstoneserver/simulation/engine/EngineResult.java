package com.example.capstoneserver.simulation.engine;

/**
 * 엔진 한 번의 결과.
 *
 * @param saved     경로별 모은 돈 (경로 × (T + 1), 한 줄로 펼침). collect를 껐으면 null
 * @param flags     [플래그][경로] 한 번이라도 켜졌으면 1
 * @param firstBrk  청년미래적금을 처음 해지한 달 (없으면 -1)
 * @param matchPaid 실제 받은 지원금 (만 원)
 */
public record EngineResult(double[] saved, byte[][] flags, int[] firstBrk, double[] matchPaid, boolean eligible, int n, int t) {

    public enum Flag {
        UNEMP, OVERDRAFT, BRK1_SP, BRK1_GEN, BRK_G, MATURE1, LIMIT, REJOIN, C1_SP, C1_UNEMP, C1_SHOCK, C1_DEFICIT
    }

    /** 경로 p의 m개월 뒤 모은 돈 */
    public double at(int p, int m) {
        return saved[p * (t + 1) + m];
    }

    public int count(Flag flag) {
        int sum = 0;
        for (byte b : flags[flag.ordinal()]) sum += b;
        return sum;
    }
}
