package com.example.capstoneserver.simulation.dto;

import tools.jackson.databind.JsonNode;

/** GET /api/meta 응답. 근거 값은 params.json의 meta를 그대로 돌려준다 (FR-10). */
public record MetaResponse(
        String modelVersion,
        int paths,
        long defaultSeed,
        int horizonMonths,
        JsonNode gradeLegend,
        JsonNode editableInputs,
        JsonNode parameters,
        JsonNode otherGrades,
        JsonNode validation
) {}
