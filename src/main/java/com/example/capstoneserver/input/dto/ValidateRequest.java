package com.example.capstoneserver.input.dto;

import jakarta.validation.constraints.NotNull;

/**
 * POST /api/inputs/validate 요청.
 * input 안의 칸은 step에 따라 검사 범위가 달라서 @Valid 대신 서비스에서 검사한다.
 */
public record ValidateRequest(
        @NotNull(message = "검사 단계를 골라 주세요")
        InputStep step,

        @NotNull(message = "입력을 보내 주세요")
        SimulationInput input
) {}
