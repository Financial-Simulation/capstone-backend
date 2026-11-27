package com.example.capstoneserver.simulation;

import com.example.capstoneserver.market.MarketRates;
import com.example.capstoneserver.simulation.engine.EngineParams;
import com.example.capstoneserver.simulation.engine.EngineParams.General;
import com.example.capstoneserver.simulation.engine.EngineParams.Housing;
import com.example.capstoneserver.simulation.engine.EngineParams.Insurance;
import com.example.capstoneserver.simulation.engine.EngineParams.Pension;
import com.example.capstoneserver.simulation.engine.EngineParams.Policy;
import com.example.capstoneserver.simulation.engine.EngineParams.Stats;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import lombok.Getter;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * resources/params.json을 서버가 켜질 때 한 번 읽는다 (파일은 읽기 전용).
 * 파일이 깨져 있으면 서버가 아예 안 켜져서 바로 알 수 있다.
 * 값을 바꾸는 순서는 기준 저장소 README 참고 (kernel.py 수정 → build_params.py → check_params.js → 이 파일 교체).
 * 통장·일반 적금·마이너스 금리만은 한국은행 최신 값을 받으면 그 위에 덮어쓴다 (market/MarketRateService, ECOS 키가 있을 때만).
 */
@Getter
@Component
public class ParamsRepository {

    private final String modelVersion;
    private final Robust robust;
    /** params.json 그대로. 정답 대조 테스트는 ParamsRepository를 새로 만들어 이 값으로 계산한다 */
    private final EngineParams baseEngineParams;
    private final JsonNode baseMeta;
    /** 계산에 쓰는 값: params.json 위에 한국은행 최신 금리를 덮어쓴 것. 받은 값이 없으면 params.json 그대로 */
    private volatile EngineParams engineParams;
    private volatile JsonNode meta;

    public ParamsRepository(JsonMapper jsonMapper) {
        try (InputStream in = new ClassPathResource("params.json").getInputStream()) {
            ParamsFile file = jsonMapper.readValue(in, ParamsFile.class);
            this.modelVersion = file.modelVersion();
            this.baseEngineParams = new EngineParams(file.params(), file.policy(), file.general(), file.housing(),
                    file.pension(), file.taxRules().insurance());
            this.robust = file.robust();
            this.baseMeta = file.meta();
        } catch (IOException e) {
            throw new UncheckedIOException("params.json을 읽지 못했어요", e);
        }
        this.engineParams = baseEngineParams;
        this.meta = baseMeta;
    }

    /**
     * 한국은행 최신 금리를 덮어쓴다: 통장(params.liquid)·일반 적금(general.rate)·마이너스(params.overdraft).
     * 받지 못한 항목은 params.json 값 그대로. /meta 근거의 "rates" 칸도 같은 값·기준 달·출처로 바꾼다.
     */
    public void applyMarketRates(MarketRates rates) {
        EngineParams base = baseEngineParams;
        Stats s = base.stats();
        Stats stats = new Stats(s.inflation(), s.wage(), s.unemp(), s.ui(), s.spend(), s.shock(),
                rates.overdraft() == null ? s.overdraft() : new EngineParams.Rate(fraction(rates.overdraft())),
                rates.liquid() == null ? s.liquid() : new EngineParams.Rate(fraction(rates.liquid())));
        General g = base.general();
        General general = rates.generalSaving() == null ? g
                : new General(fraction(rates.generalSaving()), g.earlyRatio(), g.tax());
        EngineParams next = new EngineParams(stats, base.policy(), general, base.housing(), base.pension(), base.insurance());
        this.meta = withRatesEntry(baseMeta, next, rates);
        this.engineParams = next;
    }

    /** 연 % → 연 소수 (3.08 → 0.0308) */
    private static double fraction(MarketRates.Rate rate) {
        return rate.value().movePointLeft(2).doubleValue();
    }

    private static JsonNode withRatesEntry(JsonNode baseMeta, EngineParams p, MarketRates rates) {
        String asOf = MarketRates.latestMonth(rates.liquid(), rates.generalSaving(), rates.overdraft());
        if (asOf == null || baseMeta == null || !baseMeta.has("parameters")) return baseMeta;
        JsonNode copy = baseMeta.deepCopy();
        for (JsonNode parameter : copy.get("parameters")) {
            if (parameter instanceof ObjectNode entry && entry.has("key") && "rates".equals(entry.get("key").asString())) {
                entry.put("value", "연 " + percent(p.stats().liquid().annualRate()) + "% · " + percent(p.general().rate())
                        + "% · " + percent(p.stats().overdraft().annualRate()) + "%");
                entry.put("source", "한국은행 ECOS 예금은행 가중평균금리 (수시입출식 저축성예금 잔액·정기적금 신규취급액·일반신용대출 잔액)");
                entry.put("asOf", asOf);
            }
        }
        return copy;
    }

    /** 연 소수 → 소수 둘째 자리 % 글자 (0.059 → "5.90") */
    private static String percent(double fraction) {
        return BigDecimal.valueOf(fraction).movePointRight(2).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ParamsFile(String modelVersion, Stats params, Policy policy, General general, Housing housing,
                              Pension pension, TaxRules taxRules, Robust robust, JsonNode meta) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TaxRules(Insurance insurance) {}

    /** 가능성 범위 계산에서 바꾸는 범위와 급전 확률표 (구현 명세서 7.5) */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Robust(
            int trials,
            int pathsPerTrial,
            int paramSeed,
            int noiseSeedBase,
            Map<String, double[]> uniform,
            Map<String, Double> exitByStructure,
            double[] shockAmountMultipliers,
            String[] cohortVariants,
            @JsonProperty("shockProbByStructureAmountCohort") Map<String, Double> shockProb
    ) {}
}
