package com.example.capstoneserver.benefit;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.capstoneserver.benefit.dto.BenefitsResponse;
import com.example.capstoneserver.benefit.dto.BenefitsResponse.Item;
import com.example.capstoneserver.benefit.dto.BenefitsResponse.Status;
import com.example.capstoneserver.benefit.dto.Eligibility;
import com.example.capstoneserver.input.dto.SimulationInput;
import com.example.capstoneserver.input.dto.SimulationInput.Event;
import com.example.capstoneserver.input.dto.SimulationInput.EventType;
import com.example.capstoneserver.input.dto.SimulationInput.PresetKey;
import com.example.capstoneserver.simulation.ParamsRepository;
import com.example.capstoneserver.simulation.SimulationService;
import com.example.capstoneserver.simulation.engine.WageTax;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 받을 수 있는 혜택 (/api/benefits). 판정은 benefits.json 기준(2026), 효과는 들어오는 돈을 지출 변화 이벤트로 넣은 계산과 같아야 한다.
 * 정답 파일 golden/benefits_golden.json은 프론트 목업(src/mocks/data/benefits_golden.json)과 같은 파일이다.
 * 다시 만들 때: -Dbenefits.write=<경로>로 이 테스트를 돌린다.
 */
@SpringBootTest
class BenefitServiceTest {

    private static final Eligibility FULL = new Eligibility(true, true, "2026-03", false, 0, true, 10_000_000L, 60_000L);

    @Autowired
    BenefitService service;

    @Autowired
    SimulationService simulationService;

    @Autowired
    ParamsRepository paramsRepository;

    @Autowired
    JsonMapper mapper;

    @Test
    void 기준_가능성은_simulate와_같다() throws IOException {
        for (JsonNode c : cases()) {
            SimulationInput input = input(c.get("input"));
            var expected = c.get("simulate").get("goal").get("probability").decimalValue();
            assertThat(service.benefits(input, null, null, null).probability().compareTo(expected)).as(key(c)).isEqualTo(0);
            assertThat(simulationService.probability(input, List.of(), null).compareTo(expected)).as(key(c) + " 이벤트 없음").isEqualTo(0);
        }
    }

    @Test
    void 받아요가_위_못_받아요가_아래_같은_상태는_효과가_큰_것이_위() throws IOException {
        for (JsonNode c : cases()) {
            for (Eligibility e : List.of(Eligibility.EMPTY, FULL)) {
                List<Item> items = service.benefits(input(c.get("input")), null, e, null).items();
                for (int i = 1; i < items.size(); i++) {
                    Item a = items.get(i - 1), b = items.get(i);
                    assertThat(a.status().ordinal()).as(key(c) + " 순서").isLessThanOrEqualTo(b.status().ordinal());
                    if (a.status() == b.status() && a.effect() != null && b.effect() != null) {
                        assertThat(a.effect().diff().compareTo(b.effect().diff())).as(key(c) + " 효과 순서").isGreaterThanOrEqualTo(0);
                    }
                }
                assertThat(items.stream().map(Item::key).distinct().count()).as(key(c) + " 중복 없음").isEqualTo((long) items.size());
                for (Item it : items) {
                    assertThat(it.summary().length()).as(key(c) + " " + it.key() + " 짧은 한 줄: " + it.summary()).isLessThanOrEqualTo(25);
                }
            }
        }
    }

    @Test
    void 청년월세_월급이_기준을_넘으면_못_받고_2027년_안내를_붙인다() throws IOException {
        Item rent = item(service.benefits(input(golden("T1")), null, FULL, null), "youthRent");
        assertThat(rent.status()).isEqualTo(Status.NO);
        assertThat(rent.detail()).contains("약 220만 원");
        assertThat(rent.summary()).isEqualTo("세전 월급 약 220만 원 초과");
        assertThat(rent.notice()).contains("2027년");
        assertThat(rent.effect()).isNull();
    }

    @Test
    void 청년월세_받으면_월세만큼_24개월을_더해_다시_계산한다() throws IOException {
        SimulationInput low = input(lowIncome(golden("T1"), 1_850_000));
        BenefitsResponse r = service.benefits(low, null, FULL, null);
        Item rent = item(r, "youthRent");
        assertThat(rent.status()).isEqualTo(Status.YES);
        assertThat(rent.amount()).isEqualTo("월 20만 원 × 24개월 (최대 480만 원)");
        assertThat(rent.ask()).isEmpty();

        var expected = simulationService.probability(low, List.of(spend("2027-05", -200_000), spend("2029-05", 200_000)), null);
        assertThat(rent.effect().probability().compareTo(expected)).isEqualTo(0);
        assertThat(rent.effect().diff().compareTo(expected.subtract(r.probability()))).isEqualTo(0);
        assertThat(rent.effect().diff().signum()).isPositive();
    }

    @Test
    void 청년월세_모르는_것은_물어본다() throws IOException {
        SimulationInput low = input(lowIncome(golden("T1"), 1_850_000));
        Item rent = item(service.benefits(low, null, Eligibility.EMPTY, null), "youthRent");
        assertThat(rent.status()).isEqualTo(Status.CHECK);
        assertThat(rent.ask()).containsExactly("livingApart", "rentDeposit");
        assertThat(rent.effect()).isNotNull();   // 조건이 맞으면 얼마나 좋아지는지는 보여 준다

        // 만 30세 미만 · 본인 소득 중위 50% 미만이면 부모님 가구도 본다
        SimulationInput lower = input(lowIncome(golden("T1"), 1_300_000));
        Eligibility noParents = new Eligibility(true, null, null, null, null, null, 10_000_000L, null);
        assertThat(item(service.benefits(lower, null, noParents, null), "youthRent").ask()).containsExactly("parentsIncomeLow");
        Eligibility parentsOver = new Eligibility(true, null, null, null, null, false, 10_000_000L, null);
        assertThat(item(service.benefits(lower, null, parentsOver, null), "youthRent").status()).isEqualTo(Status.NO);
    }

    @Test
    void 청년월세_따로_안_살거나_재산이_많거나_나이가_넘으면_못_받는다() throws IOException {
        SimulationInput low = input(lowIncome(golden("T1"), 1_850_000));
        Eligibility together = new Eligibility(false, null, null, null, null, null, null, null);
        assertThat(item(service.benefits(low, null, together, null), "youthRent").detail()).isEqualTo("부모님과 따로 살아야 받을 수 있어요.");
        Eligibility rich = new Eligibility(true, null, null, null, null, null, 200_000_000L, null);
        assertThat(item(service.benefits(low, null, rich, null), "youthRent").status()).isEqualTo(Status.NO);
        assertThat(item(service.benefits(input(golden("S3")), null, FULL, null), "youthRent").detail()).isEqualTo("만 19~34세만 받을 수 있어요.");
    }

    @Test
    void 소득세_감면_금액은_결정세액의_90퍼센트에_지방세를_더하고_5년만_넣는다() throws IOException {
        SimulationInput t1 = input(golden("T1"));
        BenefitsResponse r = service.benefits(t1, null, FULL, null);
        Item sme = item(r, "smeTaxRelief");
        assertThat(sme.status()).isEqualTo(Status.YES);

        double gross = 2_500_000 * 12 / 0.88;
        long yearly = Math.round(Math.min(WageTax.decided(paramsRepository.getEngineParams().insurance(), gross) * 0.9, 2_000_000) * 1.1);
        long monthly = Math.round(yearly / 12.0);
        assertThat(yearly).isPositive();
        assertThat(sme.amount()).isEqualTo("연 약 " + BenefitService.won(yearly) + " (월 약 " + BenefitService.won(monthly) + ")");

        // 2026-03 입사 → 2031-03까지. 지금(2026-11)부터 52개월
        var expected = simulationService.probability(t1, List.of(spend("2026-11", -monthly), spend("2031-03", monthly)), null);
        assertThat(sme.effect().probability().compareTo(expected)).isEqualTo(0);
    }

    @Test
    void 소득세_감면_입사_시기와_나이() throws IOException {
        SimulationInput t1 = input(golden("T1"));
        assertThat(item(service.benefits(t1, null, new Eligibility(null, true, "2027-01", null, null, null, null, null), null), "smeTaxRelief").detail())
                .isEqualTo("지금 법으로는 2026년 12월 31일까지 입사한 사람만 받아요.");
        assertThat(item(service.benefits(t1, null, new Eligibility(null, true, "2020-01", null, null, null, null, null), null), "smeTaxRelief").detail())
                .isEqualTo("입사하고 5년이 지나 감면 기간이 끝났어요.");
        Item ask = item(service.benefits(t1, null, Eligibility.EMPTY, null), "smeTaxRelief");
        assertThat(ask.status()).isEqualTo(Status.CHECK);
        assertThat(ask.ask()).containsExactly("sme", "hireMonth");
        Item already = item(service.benefits(t1, null, new Eligibility(null, true, null, true, null, null, null, null), null), "smeTaxRelief");
        assertThat(already.status()).isEqualTo(Status.YES);
        assertThat(already.effect()).isNull();

        // S3: 만 36세 입사 → 군 복무를 모르면 물어보고, 24개월이면 34세로 본다
        SimulationInput s3 = input(golden("S3"));
        Item military = item(service.benefits(s3, null, new Eligibility(null, true, "2026-01", null, null, null, null, null), null), "smeTaxRelief");
        assertThat(military.ask()).containsExactly("militaryMonths");
        assertThat(item(service.benefits(s3, null, new Eligibility(null, true, "2026-01", null, 24, null, null, null), null), "smeTaxRelief").status())
                .isEqualTo(Status.YES);
        assertThat(item(service.benefits(s3, null, new Eligibility(null, true, "2026-01", null, 0, null, null, null), null), "smeTaxRelief").status())
                .isEqualTo(Status.NO);
    }

    @Test
    void 청년미래적금과_우대형() throws IOException {
        BenefitsResponse t1 = service.benefits(input(golden("T1")), null, Eligibility.EMPTY, null);
        assertThat(item(t1, "youthFuture").status()).isEqualTo(Status.YES);
        assertThat(item(t1, "youthFuturePreferential").ask()).containsExactly("sme");
        assertThat(item(service.benefits(input(golden("T1")), null, FULL, null), "youthFuturePreferential").detail()).contains("중위 150%");
        assertThat(item(service.benefits(input(golden("S3")), null, null, null), "youthFuture").status()).isEqualTo(Status.NO);
        assertThat(item(service.benefits(input(golden("T2")), null, null, null), "youthFuture").detail()).contains("6,000만 원");
    }

    @Test
    void 나머지_정책() throws IOException {
        BenefitsResponse full = service.benefits(input(golden("T1")), null, FULL, null);
        Item kpass = item(full, "kpass");
        assertThat(kpass.amount()).isEqualTo("월 약 18,000원");
        assertThat(kpass.effect().diff().signum()).isGreaterThanOrEqualTo(0);
        assertThat(item(full, "naeil").status()).isEqualTo(Status.NO);   // 혼자 살고 월 실수령 250만 원
        Item naeilLow = item(service.benefits(input(lowIncome(golden("T1"), 1_850_000)), null, FULL, null), "naeil");
        assertThat(naeilLow.status()).isEqualTo(Status.CHECK);
        assertThat(naeilLow.detail()).contains("월 128만 원");
        assertThat(item(full, "employmentSupport").status()).isEqualTo(Status.LATER);
        assertThat(item(full, "rentCredit").status()).isEqualTo(Status.YES);
        assertThat(item(service.benefits(input(golden("E2")), null, FULL, null), "beotimmok").detail()).isEqualTo("총급여(어림)가 5,000만 원을 넘어요.");
        assertThat(item(service.benefits(input(golden("E2")), null, FULL, null), "naeil").status()).isEqualTo(Status.NO);
    }

    /** 프론트 목업용 정답: 11개 입력 × (질문 없음 · 다 답함) + 소득이 낮은 T1 */
    @Test
    void 정답_파일과_같다() throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode c : cases()) {
            out.add(entry(key(c), c.get("input"), Eligibility.EMPTY));
            out.add(entry(key(c) + "_FULL", c.get("input"), FULL));
        }
        out.add(entry("T1_LOW_FULL", lowIncome(golden("T1"), 1_850_000), FULL));
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("about", "BenefitServiceTest가 만든다. 백엔드 src/test/resources/golden/benefits_golden.json과 프론트 src/mocks/data/benefits_golden.json은 같은 파일");
        file.put("cases", out);
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(file);

        String write = System.getProperty("benefits.write");
        if (write != null) Files.writeString(Path.of(write), json + "\n", StandardCharsets.UTF_8);
        try (InputStream in = new ClassPathResource("golden/benefits_golden.json").getInputStream()) {
            assertThat(mapper.readTree(json)).isEqualTo(mapper.readTree(in));
        }
    }

    private Map<String, Object> entry(String key, JsonNode input, Eligibility e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("input", input);
        m.put("eligibility", e);
        m.put("benefits", service.benefits(input(input), null, e, null));
        return m;
    }

    // ===== 도우미 =====

    private Iterable<JsonNode> cases() throws IOException {
        try (InputStream in = new ClassPathResource("golden/analysis_golden.json").getInputStream()) {
            return JsonMapper.builder().build().readTree(in).get("cases");
        }
    }

    private JsonNode golden(String key) throws IOException {
        for (JsonNode c : cases()) if (key.equals(key(c))) return c.get("input");
        throw new IllegalArgumentException(key);
    }

    private static String key(JsonNode c) {
        return c.get("key").asString();
    }

    private SimulationInput input(JsonNode node) {
        return mapper.treeToValue(node, SimulationInput.class);
    }

    /** T1에서 월 실수령만 낮추고, 적자가 나지 않게 지출을 130만 원(고정 90만 · 변동 40만)으로 맞춘다 */
    private static JsonNode lowIncome(JsonNode input, long net) {
        JsonNode copy = input.deepCopy();
        ((ObjectNode) copy.get("income")).put("monthlyNetIncome", net);
        ((ObjectNode) copy.get("expenses").get("fixed")).put("total", 900_000);
        ((ObjectNode) copy.get("expenses").get("variable")).put("total", 400_000);
        return copy;
    }

    private static Item item(BenefitsResponse r, String key) {
        return r.items().stream().filter(i -> i.key().equals(key)).findFirst().orElseThrow();
    }

    private static Event spend(String month, long change) {
        return new Event(PresetKey.CUSTOM, "x", month, EventType.SPEND_CHANGE, null, change, null, null, null);
    }
}
