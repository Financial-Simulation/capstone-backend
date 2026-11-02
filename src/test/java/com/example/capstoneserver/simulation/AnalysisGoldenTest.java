package com.example.capstoneserver.simulation;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.capstoneserver.input.dto.SimulationInput;
import com.example.capstoneserver.plan.dto.Adjustments;
import com.example.capstoneserver.simulation.dto.PlanSpec;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 구현 명세서 8.4: 입력 8개마다 API 결과가 정답 파일과 같은지 본다.
 * 숫자는 반올림 후 값으로, 문장은 글자까지 비교한다. 응답에만 있는 필드(changes, searched 등)는 보지 않는다.
 * 정답 파일은 기준 저장소 engine/analysis_golden.json을 복사한 것.
 */
@SpringBootTest
class AnalysisGoldenTest {

    /** 정답 파일의 입력 기록·디버깅용 항목. 응답에는 없다 */
    private static final Set<String> SKIPPED = Set.of("adjustments", "trialsDetail");

    private static JsonNode golden;

    @Autowired
    SimulationService service;

    @Autowired
    JsonMapper mapper;

    @BeforeAll
    static void load() throws IOException {
        try (InputStream in = new ClassPathResource("golden/analysis_golden.json").getInputStream()) {
            golden = JsonMapper.builder().build().readTree(in);
        }
    }

    @Test
    void simulate가_정답과_같다() {
        forEachCase((key, c, input) -> assertSame(key + ".simulate", c.get("simulate"), service.simulate(input, null)));
    }

    @Test
    void preview가_정답과_같다() {
        forEachCase((key, c, input) -> {
            Adjustments adj = mapper.treeToValue(c.get("preview").get("adjustments"), Adjustments.class);
            assertSame(key + ".preview", c.get("preview"), service.preview(input, adj, null));
        });
    }

    @Test
    void compare가_정답과_같다() {
        forEachCase((key, c, input) -> assertSame(key + ".compare", c.get("compare"),
                service.compare(input, plans(c.get("compare").get("plans")), null)));
    }

    @Test
    void reverse가_정답과_같다() {
        forEachCase((key, c, input) -> assertSame(key + ".reverse", c.get("reverse"), service.reverse(input, null, null)));
    }

    @Test
    void robust가_정답과_같다() {
        forEachCase((key, c, input) -> {
            Adjustments adj = mapper.treeToValue(c.get("preview").get("adjustments"), Adjustments.class);
            List<PlanSpec> plans = List.of(new PlanSpec("변동지출 10만 원 줄이기", adj));
            assertSame(key + ".robust", c.get("robust"),
                    service.runRobust(service.prepareRobust(input, plans), done -> {}));
        });
    }

    private interface CaseCheck {
        void check(String key, JsonNode c, SimulationInput input);
    }

    private void forEachCase(CaseCheck check) {
        assertThat(golden.get("cases").size()).isEqualTo(8);
        for (JsonNode c : golden.get("cases")) {
            check.check(c.get("key").asString(), c, mapper.treeToValue(c.get("input"), SimulationInput.class));
        }
    }

    /** 비교 계획만 뽑는다. 첫 항목은 '지금 계획' */
    private List<PlanSpec> plans(JsonNode goldenPlans) {
        List<PlanSpec> plans = new ArrayList<>();
        for (JsonNode p : goldenPlans) {
            if (p.get("isBase").asBoolean()) continue;
            plans.add(new PlanSpec(p.get("name").asString(), mapper.treeToValue(p.get("adjustments"), Adjustments.class)));
        }
        return plans;
    }

    private void assertSame(String label, JsonNode expected, Object response) {
        List<String> diffs = new ArrayList<>();
        diff(label, expected, mapper.valueToTree(response), diffs);
        assertThat(diffs).as("정답과 다른 항목").isEmpty();
    }

    private void diff(String path, JsonNode expected, JsonNode actual, List<String> diffs) {
        if (actual == null) {
            diffs.add(path + ": 응답에 없음");
        } else if (expected.isObject()) {
            for (var entry : expected.properties()) {
                if (!SKIPPED.contains(entry.getKey())) {
                    diff(path + "." + entry.getKey(), entry.getValue(), actual.get(entry.getKey()), diffs);
                }
            }
        } else if (expected.isArray()) {
            if (expected.size() != actual.size()) {
                diffs.add(path + ": 길이 " + expected.size() + " vs " + actual.size());
                return;
            }
            for (int i = 0; i < expected.size(); i++) {
                diff(path + "[" + i + "]", expected.get(i), actual.get(i), diffs);
            }
        } else if (expected.isNumber() && actual.isNumber()) {
            BigDecimal e = new BigDecimal(expected.asString());
            BigDecimal a = new BigDecimal(actual.asString());
            if (e.compareTo(a) != 0) diffs.add(path + ": " + expected + " vs " + actual);
        } else if (!expected.equals(actual)) {
            diffs.add(path + ": " + expected + " vs " + actual);
        }
    }
}
