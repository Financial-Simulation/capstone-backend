package com.example.capstoneserver.input.dto;

import jakarta.validation.constraints.NotNull;

/**
 * POST /api/inputs/validate 요청.
 * 들어온 값만 검사하므로 step은 검사 범위에 영향이 없다. 응답에 그대로 돌려줄 뿐 (선택).
 */
public record ValidateRequest(
        InputStep step,

        @NotNull(message = "입력을 보내 주세요")
        SimulationInput input
) {}
