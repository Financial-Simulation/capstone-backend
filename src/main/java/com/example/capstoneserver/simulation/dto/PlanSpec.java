package com.example.capstoneserver.simulation.dto;

import com.example.capstoneserver.plan.dto.Adjustments;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 브라우저에 저장한 비교 계획 하나. 원본을 바꾸지 않고 덮어쓸 값만 가진다. */
public record PlanSpec(
        @NotNull(message = "계획 이름을 입력해 주세요")
        @Size(max = 20, message = "20자 이내로 입력해 주세요")
        String name,

        @NotNull(message = "바꿀 값을 하나 이상 보내 주세요")
        Adjustments adjustments
) {}
