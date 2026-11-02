package com.example.capstoneserver.report.dto;

import com.example.capstoneserver.input.dto.SimulationInput;
import com.example.capstoneserver.simulation.dto.PlanSpec;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** POST /api/reports 요청. plans는 선택(0~3개, /compare와 같은 규칙), seed가 null이면 20261007. */
public record ReportRequest(
        @NotNull(message = "입력을 보내 주세요") SimulationInput input,
        List<@Valid PlanSpec> plans,
        Long seed
) {}
