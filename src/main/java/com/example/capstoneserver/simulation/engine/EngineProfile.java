package com.example.capstoneserver.simulation.engine;

import com.example.capstoneserver.input.dto.SimulationInput.EmploymentType;
import java.util.List;

/**
 * 엔진이 받는 프로필 (구현 명세서 2.1). 금액은 만 원, 금리는 연 소수.
 * generalRate가 null이면 GENERAL.rate를 쓴다.
 */
public record EngineProfile(
        double age,
        double expYears,
        EmploymentType employment,
        double netIncome,
        double fixed,
        double variable,
        double cash0,
        double invest0,
        double save,
        double investMonthly,
        Double generalRate,
        double thr,
        List<EngineLoan> loans,
        List<EngineEvent> events
) {

    public EngineProfile withVariable(double newVariable) {
        return new EngineProfile(age, expYears, employment, netIncome, fixed, newVariable, cash0, invest0, save,
                investMonthly, generalRate, thr, loans, events);
    }

    public EngineProfile withSave(double newSave) {
        return new EngineProfile(age, expYears, employment, netIncome, fixed, variable, cash0, invest0, newSave,
                investMonthly, generalRate, thr, loans, events);
    }
}
