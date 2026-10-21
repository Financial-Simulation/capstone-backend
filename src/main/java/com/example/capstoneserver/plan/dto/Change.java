package com.example.capstoneserver.plan.dto;

/**
 * 변경 요약 한 줄. 예) { field: "monthlyVariableExpense", label: "월 변동비", before: 950000, after: 850000, delta: -100000 }
 *
 * - 금액: before·after는 원, delta는 after − before
 * - 목표 시점(goalTargetDate): before·after는 "YYYY-MM", delta는 개월 차이
 * - 예정된 일(events): before·after는 개수, delta는 개수 차이
 */
public record Change(
        String field,
        String label,
        Object before,
        Object after,
        long delta
) {}
