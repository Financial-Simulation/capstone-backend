package com.example.capstoneserver.plan;

import com.example.capstoneserver.global.exception.FieldValidationException;
import com.example.capstoneserver.input.dto.SimulationInput;
import com.example.capstoneserver.input.dto.SimulationInput.Emergency;
import com.example.capstoneserver.input.dto.SimulationInput.ExpenseGroup;
import com.example.capstoneserver.input.dto.SimulationInput.Expenses;
import com.example.capstoneserver.input.dto.SimulationInput.Goal;
import com.example.capstoneserver.input.dto.SimulationInput.Income;
import com.example.capstoneserver.input.dto.SimulationInput.InputMode;
import com.example.capstoneserver.input.dto.SimulationInput.SavingPlan;
import com.example.capstoneserver.plan.dto.Adjustments;
import com.example.capstoneserver.plan.dto.Change;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * '지금 계획'에 조정값을 덮어써서 '바꾼 계획'을 만든다.
 * /simulate/preview, /compare, /robust, /reports 가 함께 쓴다.
 *
 * 원래 입력은 바꾸지 않고 새 입력을 만든다 (같은 입력으로 여러 계획을 비교해야 하므로).
 * 입력은 미리 checkRequired()를 통과한 상태여야 하고, 만든 뒤에도 checkRequired()로 다시 검사한다.
 */
@Component
public class PlanAdjuster {

    public SimulationInput apply(SimulationInput input, Adjustments adj) {
        if (adj == null || adj.isEmpty()) {
            throw new FieldValidationException(Map.of("adjustments", "바꿀 값을 하나 이상 보내 주세요"));
        }

        Income income = adj.monthlyNetIncome() == null
                ? input.income()
                : new Income(adj.monthlyNetIncome());

        // 지출은 조정하면 입력 방식(DETAIL/TOTAL)과 상관없이 합계로 덮어쓴다
        Expenses expenses = new Expenses(
                adj.monthlyFixedExpense() == null ? input.expenses().fixed() : totalOnly(adj.monthlyFixedExpense()),
                adj.monthlyVariableExpense() == null ? input.expenses().variable() : totalOnly(adj.monthlyVariableExpense())
        );

        SavingPlan sp = input.savingPlan();
        SavingPlan savingPlan = new SavingPlan(
                pick(adj.monthlySaving(), sp.monthlySaving()),
                sp.generalSavingRate(),
                pick(adj.monthlyInvestment(), sp.monthlyInvestment()),
                pick(adj.emergencyFirstAmount(), sp.emergencyFirstAmount())
        );

        Emergency emergency = new Emergency(pick(adj.breakThreshold(), input.emergency().breakThreshold()));

        Goal g = input.goal();
        Goal goal = new Goal(
                g.name(),
                pick(adj.goalTargetAmount(), g.targetAmount()),
                pick(adj.goalTargetDate(), g.targetDate())
        );

        return new SimulationInput(
                input.startMonth(),
                input.person(),
                income,
                expenses,
                input.assets(),
                savingPlan,
                emergency,
                input.loans(),
                goal,
                pick(adj.events(), input.events())
        );
    }

    /** 변경 요약. 실제로 값이 바뀐 것만 담는다. */
    public List<Change> changes(SimulationInput before, SimulationInput after) {
        List<Change> changes = new ArrayList<>();

        money(changes, "monthlyNetIncome", "월 실수령액",
                before.income().monthlyNetIncome(), after.income().monthlyNetIncome());
        money(changes, "monthlyFixedExpense", "월 고정비",
                before.expenses().fixed().resolvedTotal(), after.expenses().fixed().resolvedTotal());
        money(changes, "monthlyVariableExpense", "월 변동비",
                before.expenses().variable().resolvedTotal(), after.expenses().variable().resolvedTotal());
        money(changes, "monthlySaving", "월 저축액",
                before.savingPlan().monthlySaving(), after.savingPlan().monthlySaving());
        money(changes, "monthlyInvestment", "월 투자액",
                before.savingPlan().monthlyInvestment(), after.savingPlan().monthlyInvestment());
        money(changes, "emergencyFirstAmount", "비상금 먼저 모으기",
                before.savingPlan().emergencyFirstAmount(), after.savingPlan().emergencyFirstAmount());
        money(changes, "breakThreshold", "버틸 금액",
                before.emergency().breakThreshold(), after.emergency().breakThreshold());
        money(changes, "goalTargetAmount", "목표 금액",
                before.goal().targetAmount(), after.goal().targetAmount());

        String dateBefore = before.goal().targetDate();
        String dateAfter = after.goal().targetDate();
        if (!dateBefore.equals(dateAfter)) {
            long months = ChronoUnit.MONTHS.between(YearMonth.parse(dateBefore), YearMonth.parse(dateAfter));
            changes.add(new Change("goalTargetDate", "목표 시점", dateBefore, dateAfter, months));
        }

        if (!before.events().equals(after.events())) {
            int countBefore = before.events().size();
            int countAfter = after.events().size();
            changes.add(new Change("events", "예정된 일", countBefore, countAfter, countAfter - countBefore));
        }

        return changes;
    }

    private void money(List<Change> changes, String field, String label, Long before, Long after) {
        if (!Objects.equals(before, after)) {
            changes.add(new Change(field, label, before, after, after - before));
        }
    }

    private static ExpenseGroup totalOnly(long total) {
        return new ExpenseGroup(InputMode.TOTAL, List.of(), total);
    }

    private static <T> T pick(T adjusted, T original) {
        return adjusted != null ? adjusted : original;
    }
}
