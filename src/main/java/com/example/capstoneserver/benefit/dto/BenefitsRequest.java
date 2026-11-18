package com.example.capstoneserver.benefit.dto;

import com.example.capstoneserver.input.dto.SimulationInput;
import com.example.capstoneserver.plan.dto.Adjustments;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** adjustments가 있으면 바꾼 계획(결과 화면 막대) 기준으로 판정하고 효과를 계산한다 */
public record BenefitsRequest(
        @NotNull(message = "입력을 보내 주세요") SimulationInput input,
        Adjustments adjustments,
        @Valid Eligibility eligibility,
        Long seed
) {}
