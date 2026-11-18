package com.example.capstoneserver.simulation.engine;

import com.example.capstoneserver.simulation.engine.EngineParams.Insurance;

/**
 * 근로소득 결정세액(소득세)만 따로 꺼낸다. 결과 화면 '중소기업 취업자 소득세 감면' 금액 어림에만 쓴다.
 * 엔진 규칙은 바꾸지 않는다 (YearEndTax 그대로 부른다).
 */
public final class WageTax {

    private WageTax() {}

    /** 1년 내내 일한 총급여(원)의 결정세액(소득세, 지방소득세 빼고). 청약 · 연금 · 월세 공제 없이 */
    public static double decided(Insurance insurance, double annualGrossWon) {
        double[] ins = YearEndTax.insurance(insurance, annualGrossWon, 12);
        return YearEndTax.decided(annualGrossWon, ins[0], ins[1], 0, 0, 0, true);
    }
}
