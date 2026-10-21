package com.example.capstoneserver.plan.dto;

import com.example.capstoneserver.input.dto.SimulationInput.Event;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;
import java.util.stream.Stream;

/**
 * 계획 조정값. '지금 계획'(입력 그대로)에 덮어쓸 값만 담는다. null이면 원래 입력을 쓴다.
 * 노션 「선빈 api 명세서」의 'adjustments' 표.
 *
 * 범위 검사는 여기서 하지 않는다. 조정을 적용한 뒤의 입력을 SimulationInput 규칙으로 다시 검사한다.
 */
public record Adjustments(
        Long monthlyNetIncome,          // → income.monthlyNetIncome
        Long monthlyFixedExpense,       // → expenses.fixed (합계로 덮어씀)
        Long monthlyVariableExpense,    // → expenses.variable (합계로 덮어씀)
        Long monthlySaving,             // → savingPlan.monthlySaving
        Long monthlyInvestment,         // → savingPlan.monthlyInvestment
        Long emergencyFirstAmount,      // → savingPlan.emergencyFirstAmount
        Long breakThreshold,            // → emergency.breakThreshold
        Long goalTargetAmount,          // → goal.targetAmount
        String goalTargetDate,          // → goal.targetDate
        List<Event> events              // → events[] (목록 전체 교체)
) {

    /** 바꿀 값이 하나도 없는지 */
    @JsonIgnore
    public boolean isEmpty() {
        return Stream.of(monthlyNetIncome, monthlyFixedExpense, monthlyVariableExpense, monthlySaving,
                        monthlyInvestment, emergencyFirstAmount, breakThreshold, goalTargetAmount,
                        goalTargetDate, events)
                .allMatch(v -> v == null);
    }
}
