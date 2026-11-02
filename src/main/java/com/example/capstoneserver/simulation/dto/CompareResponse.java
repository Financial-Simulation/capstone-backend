package com.example.capstoneserver.simulation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;

/** POST /api/compare 응답. 첫 항목은 항상 '지금 계획'(isBase=true, z·meaningful은 null). */
public record CompareResponse(long seed, int goalMonths, List<Plan> plans) {

    public record Plan(
            String name,
            @JsonProperty("isBase") boolean isBase,
            BigDecimal probability,
            BigDecimal diffFromBase,
            BigDecimal z,
            Boolean meaningful,
            String reach70Date,
            BigDecimal debtProbability,
            long worstCaseAtGoal,
            BigDecimal earlyTerminationProbability,
            Long expectedSubsidy,
            List<Long> yearlyP50,
            long medianAtGoal,
            long taxNetBenefit,
            Allocation allocation
    ) {}
}
