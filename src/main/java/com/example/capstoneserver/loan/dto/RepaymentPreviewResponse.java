package com.example.capstoneserver.loan.dto;

/**
 * 대출 한 건의 상환 요약 (모두 원 단위).
 */
public record RepaymentPreviewResponse(
        long firstMonthPayment,     // 첫 달 상환액
        long firstMonthPrincipal,   // 첫 달 상환액 중 원금
        long firstMonthInterest,    // 첫 달 상환액 중 이자
        long lastMonthPayment,      // 마지막 달 상환액
        long totalInterest          // 남은 기간 총이자
) {}
