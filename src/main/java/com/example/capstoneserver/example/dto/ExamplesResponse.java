package com.example.capstoneserver.example.dto;

import com.example.capstoneserver.benefit.dto.Eligibility;
import com.example.capstoneserver.input.dto.SimulationInput;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * GET /api/examples 응답.
 * 프론트는 받은 input으로 POST /api/simulate 를 부른다. 브라우저의 내 입력(simInput.v1)에는 저장하지 않는다.
 */
public record ExamplesResponse(List<Example> examples) {

    public record Example(
            String key,             // S1 ~ S4
            String label,           // 화면에 보이는 이름
            SimulationInput input,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            Eligibility eligibility // 혜택 질문 예시 답 (없으면 응답에서 빠짐). /api/benefits에 같이 보낸다
    ) {}
}
