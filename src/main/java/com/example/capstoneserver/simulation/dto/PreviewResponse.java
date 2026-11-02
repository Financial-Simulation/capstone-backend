package com.example.capstoneserver.simulation.dto;

import com.example.capstoneserver.plan.dto.Change;
import java.math.BigDecimal;
import java.util.List;

/** POST /api/simulate/preview 응답. 구현 명세서 7.3. */
public record PreviewResponse(
        long seed,
        Summary current,
        Summary adjusted,
        List<Row> table,
        Significance significance,
        List<Change> changes
) {

    public record Summary(BigDecimal probability, String reach70Date, List<Long> yearlyP50) {}

    /** metric에 따라 값이 %(BigDecimal) 또는 원(Long). 청년미래적금 비대상이면 해당 행은 모두 null */
    public record Row(String metric, Number current, Number adjusted, Number diff) {}

    public record Significance(BigDecimal z, boolean meaningful, String rule) {}
}
