package com.example.capstoneserver.simulation.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * POST /api/simulate/story 응답 (데모 '미래 다시 보기'). /simulate와 같은 미래 2,000개 중
 * 목표 시점에 모은 돈 순서로 상위 5% · 가운데 · 하위 5%에 해당하는 미래 하나씩을 달마다 보여 준다. 금액은 원.
 *
 * @param stories BEST(상위 5%) · MIDDLE(가운데) · WORST(하위 5%) 순서
 */
public record StoryResponse(long seed, int paths, String startMonth, int goalMonths, long targetAmount, List<Story> stories) {

    /**
     * @param kind     BEST · MIDDLE · WORST
     * @param quantile 0.95 · 0.50 · 0.05 (/simulate의 분위수와 같은 정의: 정렬한 뒤 min(N − 1, ⌊q × N⌋)번째)
     * @param path     미래 번호 (0~1999)
     * @param atGoal   목표 시점에 모은 돈 (/simulate 그래프의 그 분위수 값과 같다)
     */
    public record Story(String kind, BigDecimal quantile, int path, long atGoal, boolean reached, List<Month> months) {}

    /**
     * 한 달.
     *
     * @param afterMonths 지금부터 몇 달 뒤의 상태인지 (1~120, /simulate bands의 칸 번호)
     * @param month       그 일이 일어난 달 (YYYY-MM, 시작 달 + afterMonths − 1)
     * @param events      JOB_LOSS · REHIRE · SHOCK · PLANNED · REFUND · BREAK_GENERAL · BREAK_HOUSING · BREAK_POLICY ·
     *                    BREAK_POLICY_SPECIAL · BREAK_PENSION · MATURE_POLICY · REJOIN_POLICY · OVERDRAFT(빌리기 시작) · DEBT_CLEARED(다 갚음) · GOAL_REACHED
     * @param inflation   그해 물가 상승률 (%, 소수 2자리)
     */
    public record Month(int afterMonths, String month, boolean employed, boolean unemploymentBenefit, List<String> events,
                        long salary, long income, long living, long shock, long planned, long loan,
                        long refund, long subsidy, long received, long taxPaid,
                        Deposits deposits, Balances balances, long total, BigDecimal inflation) {}

    /** 그달 넣은 돈 */
    public record Deposits(long policy, long housing, long pension, long general, long invest) {}

    /** 달 말 잔액. 청약 · 연금저축은 지금 깨면 받는 세후 금액, overdraft는 빌린 돈(마이너스) */
    public record Balances(long cash, long overdraft, long policy, long housing, long pension, long general, long invest, long deposit) {}
}
