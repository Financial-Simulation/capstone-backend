package com.example.capstoneserver.simulation;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.capstoneserver.input.dto.SimulationInput;
import com.example.capstoneserver.plan.dto.Adjustments;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * /simulate에 adjustments를 주면 바꾼 계획으로 계산한다 (결과 화면 '계획 바꿔 보기' 막대).
 * 같은 seed라 /simulate/preview의 '바꾼 계획'과 확률 · 해마다 중간값이 같아야 한다. 정답 파일 11개 입력의 preview 조정값으로 본다.
 */
@SpringBootTest
class SimulateAdjustmentsTest {

    @Autowired
    SimulationService service;

    @Autowired
    JsonMapper mapper;

    @Test
    void adjustments를_주면_미리보기의_바꾼_계획과_같다() throws IOException {
        try (InputStream in = new ClassPathResource("golden/analysis_golden.json").getInputStream()) {
            for (JsonNode c : JsonMapper.builder().build().readTree(in).get("cases")) {
                String key = c.get("key").asString();
                SimulationInput input = mapper.treeToValue(c.get("input"), SimulationInput.class);
                Adjustments adj = mapper.treeToValue(c.get("preview").get("adjustments"), Adjustments.class);
                JsonNode adjusted = c.get("preview").get("adjusted");

                var result = service.simulate(input, adj, null);

                assertThat(result.goal().probability().compareTo(adjusted.get("probability").decimalValue()))
                        .as(key + " 확률").isEqualTo(0);
                JsonNode yearly = adjusted.get("yearlyP50");
                for (int i = 0; i < yearly.size(); i++) {
                    assertThat(result.bands().get(12 * (i + 1)).p50()).as(key + " " + (i + 1) + "년 중간값").isEqualTo(yearly.get(i).asLong());
                }
            }
        }
    }

    @Test
    void adjustments가_없으면_입력_그대로() throws IOException {
        try (InputStream in = new ClassPathResource("golden/analysis_golden.json").getInputStream()) {
            JsonNode c = JsonMapper.builder().build().readTree(in).get("cases").get(0);
            SimulationInput input = mapper.treeToValue(c.get("input"), SimulationInput.class);
            assertThat(service.simulate(input, null, null).goal().probability().compareTo(c.get("simulate").get("goal").get("probability").decimalValue()))
                    .isEqualTo(0);
        }
    }
}
