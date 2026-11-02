package com.example.capstoneserver.simulation.dto;

import com.example.capstoneserver.global.response.Warning;
import java.math.BigDecimal;
import java.util.List;

/** POST /api/simulate 응답. 금액은 원, 확률은 %. 구현 명세서 7.2. */
public record SimulateResponse(
        long seed,
        int paths,
        String modelVersion,
        String startMonth,
        String headline,
        Goal goal,
        Deterministic deterministic,
        List<Checkpoint> checkpoints,
        List<Band> bands,
        Risks risks,
        Causes causes,
        List<EventMarker> eventMarkers,
        List<Warning> warnings
) {

    public record Goal(
            long targetAmount,
            String targetDate,
            int goalMonths,
            int hits,
            BigDecimal probability,
            BigDecimal probabilityError,
            Integer medianReachMonths,
            String medianReachDate,
            Integer reach70Months,
            String reach70Date
    ) {}

    public record Deterministic(long atGoal, long at120) {}

    public record Checkpoint(int afterMonths, long p5, long p10, long p50, long p90) {}

    public record Band(String month, long p5, long p10, long p50, long p90) {}

    public record Risks(Debt debt, PolicySaving policySaving) {}

    public record Debt(int count, BigDecimal probability, Integer medianFirstMonths, String medianFirstDate, long worstCaseAtGoal) {}

    /** 비대상이면 eligible=false이고 나머지는 null */
    public record PolicySaving(boolean eligible, Integer earlyTerminationCount, BigDecimal earlyTerminationProbability, Long expectedSubsidy) {}

    public record Causes(
            CauseCounts counts,
            BigDecimal unemployment,
            BigDecimal prolongedUnemployment,
            BigDecimal emergencyExpense,
            BigDecimal chronicDeficit
    ) {}

    public record CauseCounts(int unemployment, int prolongedUnemployment, int emergencyExpense, int chronicDeficit) {}

    public record EventMarker(String name, String month) {}
}
