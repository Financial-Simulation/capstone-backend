package com.example.capstoneserver.simulation.dto;

import java.math.BigDecimal;
import java.util.List;

/** POST /api/reverse 응답. 구현 명세서 7.4. */
public record ReverseResponse(
        long seed,
        BigDecimal currentProbability,
        BigDecimal targetProbability,
        long monthlySurplus,
        VariableExpenseCut requiredVariableExpenseCut,
        MonthlySaving requiredMonthlySaving,
        Period requiredPeriod,
        List<EmergencyStrategy> emergencyStrategies,
        List<Lever> levers
) {

    public record VariableExpenseCut(Long amount, BigDecimal probability, String searched) {}

    public record MonthlySaving(
            Long amount,
            BigDecimal maxProbability,
            Long atAmount,
            List<GridPoint> grid,
            String searched,
            String reason,
            String note
    ) {}

    public record GridPoint(long amount, BigDecimal probability) {}

    public record Period(Integer months, String reach70Date) {}

    public record EmergencyStrategy(long amount, BigDecimal probability, BigDecimal earlyTerminationProbability, Long expectedSubsidy) {}

    /** flat=true: 차이가 2%p 미만 → 화면에 '거의 차이 없음' */
    public record Lever(String type, String label, BigDecimal probability, BigDecimal diff, boolean flat) {}
}
