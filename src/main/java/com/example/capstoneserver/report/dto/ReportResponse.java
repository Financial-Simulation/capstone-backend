package com.example.capstoneserver.report.dto;

import com.example.capstoneserver.global.response.Warning;
import com.example.capstoneserver.input.dto.ValidateResponse;
import com.example.capstoneserver.simulation.dto.CompareResponse;
import com.example.capstoneserver.simulation.dto.ReverseResponse;
import com.example.capstoneserver.simulation.dto.SimulateResponse;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * POST /api/reports 응답. 리포트 한 페이지에 들어갈 값을 다른 API 결과에서 그대로 모은다.
 * 미래 전망·위험·원인·세금은 /simulate, 개선 포인트는 /reverse의 levers 상위 3개, 비교는 /compare, 근거는 /meta.
 * PDF·이미지 저장은 프론트가 한다.
 */
public record ReportResponse(
        String generatedAt,
        long seed,
        String modelVersion,
        String startMonth,
        ValidateResponse.Summary inputSummary,
        String headline,
        SimulateResponse.Goal goal,
        Forecast forecast,
        SimulateResponse.Risks risks,
        SimulateResponse.Causes causes,
        SimulateResponse.Tax tax,
        List<ReverseResponse.Lever> levers,
        List<CompareResponse.Plan> comparison,
        JsonNode parameters,
        List<Warning> warnings,
        String disclaimer
) {

    public record Forecast(List<SimulateResponse.Checkpoint> checkpoints, long deterministicAtGoal) {}
}
