package com.example.capstoneserver.benefit.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

/**
 * 혜택을 판정할 때만 쓰는 선택 질문 (결과 화면 '혜택을 더 정확히 알고 싶다면'). 모두 비워도 된다 (null = 모름).
 * 계산 입력(SimulationInput)과 따로 브라우저에 저장되고 /api/benefits에만 보낸다.
 */
public record Eligibility(
        Boolean livingApart,             // 부모님과 따로 사나요 (청년월세 · 내일저축계좌)
        Boolean sme,                     // 지금 회사가 중소기업인가요 (소득세 감면 · 청년미래적금 우대형)
        @Pattern(regexp = "^\\d{4}-(0[1-9]|1[0-2])$", message = "YYYY-MM 형식으로 입력해 주세요")
        String hireMonth,                // 지금 회사에 들어간 달
        Boolean taxReliefNow,            // 이미 중소기업 소득세 감면을 받고 있나요
        @Min(value = 0, message = "0~120개월 사이로 입력해 주세요")
        @Max(value = 120, message = "0~120개월 사이로 입력해 주세요")
        Integer militaryMonths,          // 군 복무 개월 (나이 계산에서 빼 줌)
        Boolean parentsIncomeLow,        // 부모님 가구 소득 중위 100% 이하 · 재산 4.7억 이하인가요 (청년월세)
        @Min(value = 0, message = "0원 ~ 10억 원 사이로 입력해 주세요")
        @Max(value = 1_000_000_000, message = "0원 ~ 10억 원 사이로 입력해 주세요")
        Long rentDeposit,                // 지금 집 보증금
        @Min(value = 0, message = "0원 ~ 100만 원 사이로 입력해 주세요")
        @Max(value = 1_000_000, message = "0원 ~ 100만 원 사이로 입력해 주세요")
        Long monthlyTransit              // 한 달 대중교통비 (K-패스)
) {
    public static final Eligibility EMPTY = new Eligibility(null, null, null, null, null, null, null, null);
}
