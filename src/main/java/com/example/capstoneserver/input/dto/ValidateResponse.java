package com.example.capstoneserver.input.dto;

import com.example.capstoneserver.global.response.Warning;
import java.util.List;

/**
 * POST /api/inputs/validate 응답.
 * 아직 안 나온 화면의 값은 null (예: BASIC 단계면 대출·저축·자산 합계는 null).
 */
public record ValidateResponse(
        InputStep step,
        Summary summary,
        PolicySaving policySaving,
        List<Warning> warnings
) {

    /** 확정 합계 (원). 프론트의 실시간 합계는 표시용이고 이 값이 확정값이다. */
    public record Summary(
            Long monthlyNetIncome,      // 월 실수령액
            Long fixedTotal,            // 고정지출 합계
            Long variableTotal,         // 변동지출 합계
            Long monthlyLoanPayment,    // 첫 달 대출 상환액 합계
            Long monthlySurplus,        // 매달 남는 돈 = 실수령액 − 고정 − 변동 − 대출 상환
            Long monthlySaving,         // 월 저축액
            Long totalAssets,           // 현금 + 예적금 + 투자
            Long totalLoanBalance       // 대출 잔액 합계
    ) {}

    /** 청년미래적금 자격과 월 저축액 배분 */
    public record PolicySaving(
            boolean eligible,
            String reason,              // 대상이 아니면 이유, 대상이면 null
            long policyMonthly,         // 청년미래적금으로 넣는 돈
            long generalMonthly         // 일반 적금으로 넣는 돈
    ) {}
}
