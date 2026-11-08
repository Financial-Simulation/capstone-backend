package com.example.capstoneserver.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.capstoneserver.input.dto.SimulationInput;
import com.example.capstoneserver.plan.dto.Adjustments;
import com.example.capstoneserver.simulation.dto.PlanSpec;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 리포트의 각 섹션이 원래 API의 정답과 같은지 본다 (정답 파일 T1: 세금 블록·비교 포함) */
@SpringBootTest
class ReportServiceTest {

    @Autowired
    ReportService reportService;

    @Autowired
    JsonMapper mapper;

    @Test
    void 리포트_섹션이_원래_API_결과와_같다() throws IOException {
        JsonNode c = goldenCase("T1");
        SimulationInput input = mapper.treeToValue(c.get("input"), SimulationInput.class);
        Adjustments adj = mapper.treeToValue(c.get("preview").get("adjustments"), Adjustments.class);

        JsonNode r = mapper.valueToTree(reportService.report(input, List.of(new PlanSpec("변동지출 10만 원 줄이기", adj)), null));

        JsonNode sim = c.get("simulate");
        List<String> bad = new ArrayList<>();
        if (!r.get("headline").equals(sim.get("headline"))) bad.add("headline");
        if (!sameNumbers(r.get("forecast").get("checkpoints"), sim.get("checkpoints"))) bad.add("checkpoints");
        if (!sameNumbers(r.get("tax"), sim.get("tax"))) bad.add("tax");
        if (!sameNumbers(r.get("risks"), sim.get("risks"))) bad.add("risks");
        JsonNode levers = c.get("reverse").get("levers");
        if (r.get("levers").size() != Math.min(3, levers.size())) bad.add("levers 개수");
        for (int i = 0; i < r.get("levers").size(); i++) {
            if (!sameNumbers(r.get("levers").get(i), levers.get(i))) bad.add("levers[" + i + "]");
        }
        if (r.get("comparison").size() != 2) bad.add("comparison 개수");   // 지금 계획 + 바꾼 계획
        if (!"1.7".equals(r.get("modelVersion").asString())) bad.add("modelVersion");
        if (r.get("parameters").size() < 10) bad.add("parameters");
        assertThat(bad).as("정답과 다른 섹션").isEmpty();
    }

    @Test
    void 비교할_계획이_없으면_비교는_빈_목록() throws IOException {
        SimulationInput input = mapper.treeToValue(goldenCase("S1").get("input"), SimulationInput.class);
        assertThat(reportService.report(input, null, null).comparison()).isEmpty();
    }

    private static JsonNode goldenCase(String key) throws IOException {
        try (InputStream in = new ClassPathResource("golden/analysis_golden.json").getInputStream()) {
            for (JsonNode c : JsonMapper.builder().build().readTree(in).get("cases")) {
                if (c.get("key").asString().equals(key)) return c;
            }
        }
        throw new IllegalStateException(key + " 없음");
    }

    /** 정답 파일에 있는 항목만, 숫자는 값으로 비교 (58과 58.0은 같다) */
    private static boolean sameNumbers(JsonNode actual, JsonNode expected) {
        if (expected.isObject()) {
            for (var e : expected.properties()) {
                JsonNode a = actual.get(e.getKey());
                if (a == null || !sameNumbers(a, e.getValue())) return false;
            }
            return true;
        }
        if (expected.isArray()) {
            if (actual.size() != expected.size()) return false;
            for (int i = 0; i < expected.size(); i++) if (!sameNumbers(actual.get(i), expected.get(i))) return false;
            return true;
        }
        if (expected.isNumber() && actual.isNumber()) return expected.decimalValue().compareTo(actual.decimalValue()) == 0;
        return expected.equals(actual);
    }
}
