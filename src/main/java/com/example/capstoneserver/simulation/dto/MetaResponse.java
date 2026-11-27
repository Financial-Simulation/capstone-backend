package com.example.capstoneserver.simulation.dto;

import com.example.capstoneserver.input.dto.LoanType;
import java.math.BigDecimal;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * GET /api/meta 응답. 근거 값은 params.json의 meta를 그대로 돌려준다 (FR-10).
 * 한국은행 최신 금리를 받았으면 parameters의 "rates" 칸이 그 값·기준 달로 바뀐다 (market/MarketRateService).
 */
public record MetaResponse(
        String modelVersion,
        int paths,
        long defaultSeed,
        int horizonMonths,
        JsonNode gradeLegend,
        JsonNode editableInputs,
        JsonNode parameters,
        JsonNode otherGrades,
        JsonNode validation,
        DefaultRates defaultRates
) {

    /**
     * 비운 칸에 서버가 넣는 금리 (연 %): 일반 적금, 대출 종류별.
     * asOf는 한국은행 값의 기준 달 (YYYY-MM), 코드 기본값이면 null
     */
    public record DefaultRates(BigDecimal generalSavingRate, Map<LoanType, BigDecimal> loanRates, String asOf, String source) {}
}
