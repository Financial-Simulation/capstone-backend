package com.example.capstoneserver.simulation;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.capstoneserver.input.dto.SimulationInput;
import com.example.capstoneserver.simulation.dto.StoryResponse;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 미래 다시 보기 (데모): 고른 미래가 /simulate 그래프의 분위수와 맞는지 본다.
 * 가운데 미래의 목표 시점 값 = 그래프 중간값(p50), 하위 5% 미래 = 최악의 경우(p5). 정답 파일 S1 · T1.
 */
@SpringBootTest
class StoryTest {

    @Autowired
    SimulationService service;

    @Autowired
    JsonMapper mapper;

    @Test
    void S1_가운데와_하위_5퍼센트가_그래프_값과_같다() throws IOException {
        check("S1");
    }

    @Test
    void T1_세금_상품이_있어도_그래프_값과_같다() throws IOException {
        check("T1");
    }

    @Test
    void 급한_지출_사건에_유형과_금액이_함께_내려간다() throws IOException {
        JsonNode c = goldenCase("S1");
        StoryResponse story = service.story(mapper.treeToValue(c.get("input"), SimulationInput.class), null);

        List<StoryResponse.Month> shocks = story.stories().stream()
                .flatMap(s -> s.months().stream())
                .filter(m -> m.shock() > 0)
                .toList();

        assertThat(shocks).isNotEmpty();
        assertThat(shocks).allSatisfy(m -> {
            assertThat(m.events()).contains("SHOCK");
            assertThat(m.shockType()).isIn("CEREMONY", "MEDICAL", "TRAFFIC_ACCIDENT");
            assertThat(m.shockLabel()).isNotBlank();
        });
    }

    private void check(String key) throws IOException {
        JsonNode c = goldenCase(key);
        StoryResponse story = service.story(mapper.treeToValue(c.get("input"), SimulationInput.class), null);
        int gm = story.goalMonths();
        JsonNode band = c.get("simulate").get("bands").get(gm);

        assertThat(story.stories().stream().map(StoryResponse.Story::kind).toList()).as(key).isEqualTo(java.util.List.of("BEST", "MIDDLE", "WORST"));
        StoryResponse.Story best = story.stories().get(0);
        StoryResponse.Story middle = story.stories().get(1);
        StoryResponse.Story worst = story.stories().get(2);
        assertThat(middle.atGoal()).as(key + " 가운데 = 중간값").isEqualTo(band.get("p50").asLong());
        assertThat(worst.atGoal()).as(key + " 하위 5퍼센트 = 최악의 경우").isEqualTo(band.get("p5").asLong());
        assertThat(best.atGoal() >= band.get("p90").asLong()).as(key + " 상위 5퍼센트 ≥ 위 10퍼센트").isTrue();
        for (StoryResponse.Story s : story.stories()) {
            assertThat(s.months().size()).as(key + " " + s.kind()).isEqualTo(120);
            assertThat(s.months().get(gm - 1).total()).as(key + " " + s.kind() + " 목표 시점 값").isEqualTo(s.atGoal());
            assertThat(s.months().get(0).month()).isEqualTo(c.get("input").get("startMonth").asString());
        }
    }

    private static JsonNode goldenCase(String key) throws IOException {
        try (InputStream in = new ClassPathResource("golden/analysis_golden.json").getInputStream()) {
            for (JsonNode c : JsonMapper.builder().build().readTree(in).get("cases")) {
                if (c.get("key").asString().equals(key)) return c;
            }
        }
        throw new IllegalStateException(key + " 없음");
    }
}
