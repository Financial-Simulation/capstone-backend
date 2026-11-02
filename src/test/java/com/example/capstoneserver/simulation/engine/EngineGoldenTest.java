package com.example.capstoneserver.simulation.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.capstoneserver.input.dto.RepaymentType;
import com.example.capstoneserver.input.dto.SimulationInput.EmploymentType;
import com.example.capstoneserver.loan.LoanCalculator;
import com.example.capstoneserver.simulation.ParamsRepository;
import com.example.capstoneserver.simulation.engine.EngineResult.Flag;
import com.example.capstoneserver.simulation.engine.NoiseSource.PathNoise;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 구현 명세서 8.1, 8.2: 파이썬 기준 구현의 난수를 그대로 넣어 결과가 같은지 본다.
 * 통과 기준: saved·det·match 상대오차 1e-9 이하, 플래그·첫 해지 시점 완전 일치.
 * 정답 파일은 기준 저장소 engine/golden.json을 복사한 것.
 */
class EngineGoldenTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final double TOLERANCE = 1e-9;
    private static final Map<String, Flag> FLAG_KEYS = Map.ofEntries(
            Map.entry("unemp", Flag.UNEMP), Map.entry("overdraft", Flag.OVERDRAFT),
            Map.entry("brk1_sp", Flag.BRK1_SP), Map.entry("brk1_gen", Flag.BRK1_GEN),
            Map.entry("brkG", Flag.BRK_G), Map.entry("mature1", Flag.MATURE1),
            Map.entry("limit", Flag.LIMIT), Map.entry("rejoin", Flag.REJOIN),
            Map.entry("c1_sp", Flag.C1_SP), Map.entry("c1_unemp", Flag.C1_UNEMP),
            Map.entry("c1_shock", Flag.C1_SHOCK), Map.entry("c1_deficit", Flag.C1_DEFICIT));

    private static JsonNode golden;
    private static EngineParams params;
    private final SimulationEngine engine = new SimulationEngine(new LoanCalculator());

    @BeforeAll
    static void load() throws IOException {
        try (InputStream in = new ClassPathResource("golden/golden.json").getInputStream()) {
            golden = MAPPER.readTree(in);
        }
        params = new ParamsRepository(MAPPER).getEngineParams();
    }

    @Test
    void 파이썬_기준_구현과_12개_프로필이_모두_일치한다() {
        int n = golden.get("N").asInt();
        int t = golden.get("T").asInt();
        NoiseSource noise = fileNoise(golden.get("noise"));
        double worst = 0;

        assertThat(golden.get("cases").size()).isEqualTo(12);
        for (JsonNode c : golden.get("cases")) {
            String name = c.get("name").asString();
            EngineProfile pr = profile(c.get("pr"));
            JsonNode opt = c.get("opt");
            double buffer = opt.has("bufferFirst") ? opt.get("bufferFirst").asDouble() : 0;
            EngineParams p = opt.has("rejoin") && !opt.get("rejoin").asBoolean() ? params.withoutRejoin() : params;

            EngineResult r = engine.simulate(pr, p, noise, n, t, buffer, true);
            EngineResult d = engine.simulate(pr, p, NoiseSource.zero(), 1, t, buffer, true);

            for (int path = 0; path < n; path++) {
                for (int m = 0; m <= t; m++) {
                    worst = Math.max(worst, relErr(r.at(path, m), c.get("saved").get(path).get(m).asDouble(), name));
                }
                worst = Math.max(worst, relErr(r.matchPaid()[path], c.get("match").get(path).asDouble(), name));
                assertThat(r.firstBrk()[path]).as("%s 경로 %d 첫 해지", name, path).isEqualTo(c.get("firstBrk").get(path).asInt());
                for (Map.Entry<String, Flag> flag : FLAG_KEYS.entrySet()) {
                    assertThat((int) r.flags()[flag.getValue().ordinal()][path])
                            .as("%s 경로 %d 플래그 %s", name, path, flag.getKey())
                            .isEqualTo(c.get("flags").get(flag.getKey()).get(path).asInt());
                }
            }
            for (int m = 0; m <= t; m++) {
                worst = Math.max(worst, relErr(d.at(0, m), c.get("det").get(m).asDouble(), name));
            }
        }
        System.out.println("엔진 정답 대조 최대 상대오차: " + worst);
    }

    @Test
    void sfc32_난수가_명세서_확인값과_같다() {
        NoiseSource noise = NoiseSource.sfc32(20261007, 2000);
        PathNoise p0 = noise.noise(0, 120);
        PathNoise p1000 = noise.noise(1000, 120);

        assertThat(p0.z1()[0]).isEqualTo(-0.57882532845290124);
        assertThat(p0.z1()[1]).isEqualTo(1.1554114528094823);
        assertThat(p0.z1()[2]).isEqualTo(0.37425810223580386);
        assertThat(p1000.z1()[0]).isEqualTo(0.57882532845290124);
        assertThat(p1000.z1()[1]).isEqualTo(-1.1554114528094823);
        assertThat(p0.z2()[0]).isEqualTo(0.44112529436245818);
        assertThat(p0.uOnset()[0]).isEqualTo(0.56416278285905719);
        assertThat(p0.uOnset()[1]).isEqualTo(0.048801576253026724);
        assertThat(p0.uAmt()[119]).isEqualTo(0.43579852511174977);
        assertThat(p1000.uOnset()[0]).isEqualTo(0.51368772634305060);
        assertThat(noise.noise(0, 36).z2()[0]).isEqualTo(-0.59076490877080257);
    }

    private static double relErr(double actual, double expected, String name) {
        double err = Math.abs(actual - expected) / Math.max(1, Math.abs(expected));
        assertThat(err).as("%s: %s vs %s", name, actual, expected).isLessThanOrEqualTo(TOLERANCE);
        return err;
    }

    private static NoiseSource fileNoise(JsonNode noise) {
        return (p, t) -> new PathNoise(row(noise, "z1", p), row(noise, "z2", p), row(noise, "u_onset", p),
                row(noise, "u_dur", p), row(noise, "u_invol", p), row(noise, "u_shock", p), row(noise, "u_amt", p));
    }

    private static double[] row(JsonNode noise, String key, int p) {
        JsonNode values = noise.get(key).get(p);
        double[] out = new double[values.size()];
        for (int i = 0; i < out.length; i++) out[i] = values.get(i).asDouble();
        return out;
    }

    private static EngineProfile profile(JsonNode pr) {
        List<EngineLoan> loans = new ArrayList<>();
        if (pr.has("loans")) {
            for (JsonNode l : pr.get("loans")) {
                loans.add(new EngineLoan(l.get("balance").asDouble(), l.get("rate").asDouble(), l.get("months").asInt(),
                        method(l.has("method") ? l.get("method").asString() : "annuity")));
            }
        }
        List<EngineEvent> events = new ArrayList<>();
        if (pr.has("events")) {
            for (JsonNode e : pr.get("events")) {
                int t = e.get("t").asInt();
                events.add(switch (e.get("kind").asString()) {
                    case "lump" -> EngineEvent.lump(t, e.get("amount").asDouble());
                    case "spend" -> EngineEvent.spend(t, e.get("delta").asDouble());
                    case "income" -> EngineEvent.income(t, e.get("pct").asDouble(), e.get("gap").asInt());
                    case "move" -> EngineEvent.move(t, e.get("deposit").asDouble(), e.get("delta").asDouble(), e.get("cost").asDouble());
                    default -> throw new IllegalStateException();
                });
            }
        }
        return new EngineProfile(
                pr.get("age").asDouble(),
                pr.get("exp_years").asDouble(),
                pr.get("employment").asString().equals("계약") ? EmploymentType.CONTRACT : EmploymentType.REGULAR,
                pr.get("net_income").asDouble(),
                pr.get("fixed").asDouble(),
                pr.get("variable").asDouble(),
                pr.get("cash0").asDouble(),
                pr.has("invest0") ? pr.get("invest0").asDouble() : 0,
                pr.get("save").asDouble(),
                pr.has("invest_monthly") ? pr.get("invest_monthly").asDouble() : 0,
                pr.has("general_rate") ? pr.get("general_rate").asDouble() : null,
                pr.has("thr") ? pr.get("thr").asDouble() : 50,
                loans,
                events);
    }

    private static RepaymentType method(String method) {
        return switch (method) {
            case "annuity" -> RepaymentType.EQUAL_PAYMENT;
            case "equal_principal" -> RepaymentType.EQUAL_PRINCIPAL;
            case "bullet" -> RepaymentType.BULLET;
            case "interest_only" -> RepaymentType.INTEREST_ONLY;
            default -> throw new IllegalStateException(method);
        };
    }
}
