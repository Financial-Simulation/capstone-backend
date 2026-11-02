package com.example.capstoneserver.simulation;

import com.example.capstoneserver.input.dto.SimulationInput;
import com.example.capstoneserver.input.dto.SimulationInput.Event;
import com.example.capstoneserver.simulation.engine.EngineEvent;
import com.example.capstoneserver.simulation.engine.EngineLoan;
import com.example.capstoneserver.simulation.engine.EngineProfile;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * API 입력(원, %, YYYY-MM) → 엔진 프로필(만 원, 연 소수, 개월). 구현 명세서 2.1~2.2.
 * adjustments는 PlanAdjuster가 입력에 먼저 덮어쓰므로 여기서는 입력만 본다.
 * 입력은 InputValidationService.checkRequired()를 통과한 상태여야 한다.
 */
@Component
public class ProfileMapper {

    public EnginePlan toPlan(SimulationInput input) {
        YearMonth start = YearMonth.parse(input.startMonth());

        List<EngineLoan> loans = input.loans().items().stream()
                .map(l -> new EngineLoan(man(l.principal()), l.resolvedRate().doubleValue() / 100,
                        l.remainingMonths(), l.resolvedRepaymentType()))
                .toList();
        List<EngineEvent> events = input.events().stream().map(e -> toEvent(start, e)).toList();

        EngineProfile profile = new EngineProfile(
                input.person().age(),
                input.person().careerYears(),
                input.person().employmentType(),
                man(input.income().monthlyNetIncome()),
                man(input.expenses().fixed().resolvedTotal()),
                man(input.expenses().variable().resolvedTotal()),
                man(input.assets().cash() + input.assets().deposits()),
                man(input.assets().investments()),
                man(input.savingPlan().monthlySaving()),
                man(input.savingPlan().monthlyInvestment()),
                input.savingPlan().generalSavingRate().doubleValue() / 100,
                man(input.emergency().breakThreshold()),
                loans,
                events);

        return new EnginePlan(profile, man(input.goal().targetAmount()),
                months(start, input.goal().targetDate()), man(input.savingPlan().emergencyFirstAmount()));
    }

    private EngineEvent toEvent(YearMonth start, Event e) {
        int t = months(start, e.occurDate());
        return switch (e.eventType()) {
            case ONE_TIME -> EngineEvent.lump(t, man(e.amount()));
            case SPEND_CHANGE -> EngineEvent.spend(t, man(e.monthlyChange()));
            case JOB_CHANGE -> EngineEvent.income(t, e.incomeChangeRate().doubleValue() / 100, e.gapMonths());
            case MOVE -> EngineEvent.move(t, man(e.deposit()), man(e.monthlyChange()), man(e.amount()));
        };
    }

    private static int months(YearMonth start, String date) {
        return (int) ChronoUnit.MONTHS.between(start, YearMonth.parse(date));
    }

    private static double man(long won) {
        return won / 10000.0;
    }
}
