package com.example.capstoneserver.simulation.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * POST /api/robust, GET /api/robust/{jobId} 응답.
 * RUNNING·FAILED면 진행 정보만 있고 결과 필드(probability, plans, message)는 null.
 */
public record RobustResponse(
        String jobId,
        Status status,
        int progress,
        int trials,
        Integer pathsPerTrial,
        Probability probability,
        List<Plan> plans,
        String message
) {

    public enum Status { RUNNING, DONE, FAILED }

    public record Probability(BigDecimal median, BigDecimal low, BigDecimal high, String range) {}

    public record Plan(String name, BigDecimal diffMedian, BigDecimal diffLow, BigDecimal diffHigh, int conclusionKept, int trials) {}

    /** 계산 결과만 묶은 것. 작업 정보는 RobustJobService가 붙인다 */
    public record Result(int trials, int pathsPerTrial, Probability probability, List<Plan> plans, String message) {}
}
