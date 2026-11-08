package com.example.capstoneserver.simulation.engine;

import com.example.capstoneserver.input.dto.SimulationInput.EmploymentType;
import java.util.List;

/**
 * 엔진이 받는 프로필 (구현 명세서 2.1). 금액은 만 원, 금리는 연 소수.
 * generalRate가 null이면 GENERAL.rate를 쓴다.
 * 세금·상품 칸: grossY(세전 연봉, null이면 실수령 × 12 ÷ 0.88), rent(고정지출 중 월세), homeless(무주택 세대주),
 * startMonth(시작 달 1~12, 연말정산 시점), policyM(청년미래 월 납입, null이면 min(save, 50)), housingM·pensionM(청약·연금저축 월 납입).
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
        List<EngineEvent> events,
        Double grossY,
        double rent,
        boolean homeless,
        int startMonth,
        Double policyM,
        double housingM,
        double pensionM
) {

    /** 세금·상품 칸 없이 (세금 계산 안 함, 1월 시작) */
    public EngineProfile(double age, double expYears, EmploymentType employment, double netIncome, double fixed,
                         double variable, double cash0, double invest0, double save, double investMonthly,
                         Double generalRate, double thr, List<EngineLoan> loans, List<EngineEvent> events) {
        this(age, expYears, employment, netIncome, fixed, variable, cash0, invest0, save, investMonthly, generalRate, thr,
                loans, events, null, 0, true, 1, null, 0, 0);
    }

    public EngineProfile withVariable(double newVariable) {
        return new EngineProfile(age, expYears, employment, netIncome, fixed, newVariable, cash0, invest0, save,
                investMonthly, generalRate, thr, loans, events, grossY, rent, homeless, startMonth, policyM, housingM, pensionM);
    }

    public EngineProfile withSave(double newSave) {
        return new EngineProfile(age, expYears, employment, netIncome, fixed, variable, cash0, invest0, newSave,
                investMonthly, generalRate, thr, loans, events, grossY, rent, homeless, startMonth, policyM, housingM, pensionM);
    }

    /** 상품별 월 납입 배분 (자산 쪼개기 추천) */
    public EngineProfile withAllocation(Double newPolicyM, double newHousingM, double newPensionM) {
        return new EngineProfile(age, expYears, employment, netIncome, fixed, variable, cash0, invest0, save,
                investMonthly, generalRate, thr, loans, events, grossY, rent, homeless, startMonth, newPolicyM, newHousingM, newPensionM);
    }
}
