package com.example.capstoneserver.simulation.dto;

import com.example.capstoneserver.input.dto.SimulationInput;
import com.example.capstoneserver.plan.dto.Adjustments;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;

/** 계산 API 요청 본문. input의 칸별 검사는 서비스의 checkRequired()가 한다. seed가 null이면 20261007. */
public final class SimulationRequests {

    private SimulationRequests() {}

    public record Simulate(
            @NotNull(message = "입력을 보내 주세요") SimulationInput input,
            Long seed
    ) {}

    /** 미래 다시 보기 (데모): 상위 5% · 가운데 · 하위 5% 미래를 달마다 */
    public record Story(
            @NotNull(message = "입력을 보내 주세요") SimulationInput input,
            Long seed
    ) {}

    public record Preview(
            @NotNull(message = "입력을 보내 주세요") SimulationInput input,
            @NotNull(message = "바꿀 값을 하나 이상 보내 주세요") Adjustments adjustments,
            Long seed
    ) {}

    public record Compare(
            @NotNull(message = "입력을 보내 주세요") SimulationInput input,
            List<@Valid PlanSpec> plans,
            Long seed
    ) {}

    public record Reverse(
            @NotNull(message = "입력을 보내 주세요") SimulationInput input,

            @DecimalMin(value = "50", message = "50~95% 사이로 입력해 주세요")
            @DecimalMax(value = "95", message = "50~95% 사이로 입력해 주세요")
            BigDecimal targetProbability,

            Long seed
    ) {}

    public record Robust(
            @NotNull(message = "입력을 보내 주세요") SimulationInput input,
            List<@Valid PlanSpec> plans
    ) {}

    public record Recommend(
            @NotNull(message = "입력을 보내 주세요") SimulationInput input
    ) {}
}
