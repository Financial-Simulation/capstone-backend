package com.example.capstoneserver.simulation.dto;

import java.math.BigDecimal;

/**
 * POST /api/allocations/recommend 응답 (자산 쪼개기 자동 추천).
 * 격자 안의 배분을 같은 미래 2,000개로 모두 계산해 두 가지를 고른다.
 *
 * @param evaluated       계산한 배분 수
 * @param bestProbability 목표 달성 수가 가장 많은 배분 (같으면 혜택이 큰 쪽)
 * @param bestBenefit     달성 수가 지금보다 2%p(40개) 넘게 줄지 않는 배분 중 세금 순이득 + 정부 지원금이 가장 큰 배분
 */
public record RecommendResponse(long monthlySaving, int evaluated, Option current, Option bestProbability,
                                Option bestBenefit, String rule) {

    /**
     * 배분 하나의 결과. 세금·지원금은 목표 시점까지 받는 돈의 평균 (원).
     * subsidy는 목표 시점까지라 /simulate의 expectedSubsidy(48개월 안)와 다를 수 있다.
     */
    public record Option(Allocation allocation, int hits, BigDecimal probability, long taxNetBenefit, long subsidy,
                         long totalBenefit, BigDecimal policyBreakProbability, BigDecimal diffFromBase) {}
}
