package com.example.capstoneserver.example.dto;

import com.example.capstoneserver.input.dto.SimulationInput;
import java.util.List;

/**
 * GET /api/examples 응답.
 * 프론트는 받은 input으로 POST /api/simulate 를 부른다. 브라우저의 내 입력(simInput.v1)에는 저장하지 않는다.
 */
public record ExamplesResponse(List<Example> examples) {

    public record Example(
            String key,             // S1 ~ S3
            String label,           // 화면에 보이는 이름
            SimulationInput input
    ) {}
}
