package com.example.capstoneserver.market;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.capstoneserver.input.dto.LoanType;
import com.example.capstoneserver.input.dto.SimulationInput;
import com.example.capstoneserver.simulation.ParamsRepository;
import com.example.capstoneserver.simulation.dto.MetaResponse;
import com.example.capstoneserver.simulation.engine.EngineParams;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class MarketRateServiceTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final ParamsRepository params = new ParamsRepository(mapper);

    /** 2026-11-27에 ECOS에서 받은 2026년 10월 값 */
    private static final Map<EcosSeries, MarketRates.Rate> OCTOBER = Map.of(
            EcosSeries.LIQUID, rate("0.81", "2026-10"),
            EcosSeries.GENERAL_SAVING, rate("3.08", "2026-10"),
            EcosSeries.OVERDRAFT, rate("5.9", "2026-10"),
            EcosSeries.MORTGAGE, rate("4.98", "2026-10"),
            EcosSeries.CREDIT, rate("6.43", "2026-10"),
            EcosSeries.JEONSE, rate("4.63", "2026-10"));

    @AfterEach
    void resetDefaults() {
        RateDefaults.reset();
    }

    @Test
    void 키가_없으면_아무것도_바꾸지_않는다() {
        service(source(false, OCTOBER)).refresh();

        assertThat(params.getEngineParams()).isSameAs(params.getBaseEngineParams());
        assertThat(RateDefaults.generalSaving()).isEqualByComparingTo("3.03");
        assertThat(RateDefaults.loan(LoanType.MORTGAGE)).isEqualByComparingTo("4.7");
        assertThat(RateDefaults.forMeta().asOf()).isNull();
    }

    @Test
    void 받은_금리를_엔진_값에_덮어쓰고_params_json_값은_그대로_둔다() {
        service(source(true, OCTOBER)).refresh();

        EngineParams p = params.getEngineParams();
        assertThat(p.stats().liquid().annualRate()).isEqualTo(0.0081);
        assertThat(p.general().rate()).isEqualTo(0.0308);
        assertThat(p.stats().overdraft().annualRate()).isEqualTo(0.059);
        assertThat(p.stats().inflation()).isSameAs(params.getBaseEngineParams().stats().inflation());

        // 정답 대조 테스트가 쓰는 params.json 값은 그대로
        assertThat(params.getBaseEngineParams().stats().liquid().annualRate()).isEqualTo(0.0068);
        assertThat(new ParamsRepository(mapper).getEngineParams().general().rate()).isEqualTo(0.0303);
    }

    @Test
    void 비운_칸은_받은_금리로_채운다() {
        service(source(true, OCTOBER)).refresh();

        assertThat(new SimulationInput.SavingPlan(500_000L, null, null, null, null, null, null).generalSavingRate())
                .isEqualByComparingTo("3.08");
        assertThat(loan(LoanType.MORTGAGE, null).resolvedRate()).isEqualByComparingTo("4.98");
        assertThat(loan(LoanType.CREDIT, null).resolvedRate()).isEqualByComparingTo("6.43");
        assertThat(loan(LoanType.JEONSE, null).resolvedRate()).isEqualByComparingTo("4.63");
        // 한국은행 통계가 없는 대출은 코드 기본값, 사용자가 넣은 금리는 그대로
        assertThat(loan(LoanType.STUDENT, null).resolvedRate()).isEqualByComparingTo("1.7");
        assertThat(loan(LoanType.OTHER, null).resolvedRate()).isEqualByComparingTo("5.0");
        assertThat(loan(LoanType.JEONSE, new BigDecimal("3.1")).resolvedRate()).isEqualByComparingTo("3.1");
    }

    @Test
    void meta_근거의_금리_칸과_기본값도_바뀐다() {
        service(source(true, OCTOBER)).refresh();

        JsonNode rates = ratesEntry(params.getMeta());
        assertThat(rates.get("value").asString()).isEqualTo("연 0.81% · 3.08% · 5.90%");
        assertThat(rates.get("asOf").asString()).isEqualTo("2026-10");
        assertThat(rates.get("grade").asString()).isEqualTo("B");
        assertThat(ratesEntry(params.getBaseMeta()).get("value").asString()).isEqualTo("연 0.68% · 3.03% · 5.31%");

        MetaResponse.DefaultRates defaults = RateDefaults.forMeta();
        assertThat(defaults.asOf()).isEqualTo("2026-10");
        assertThat(defaults.generalSavingRate()).isEqualByComparingTo("3.08");
        assertThat(defaults.loanRates().get(LoanType.JEONSE)).isEqualByComparingTo("4.63");
        assertThat(defaults.loanRates().get(LoanType.STUDENT)).isEqualByComparingTo("1.7");
    }

    @Test
    void 실패한_항목은_마지막_값을_그대로_둔다() {
        Map<EcosSeries, MarketRates.Rate> values = new EnumMap<>(OCTOBER);
        MarketRateService service = service(source(true, values));
        service.refresh();

        values.remove(EcosSeries.LIQUID);                       // 통장 금리 요청이 실패
        values.put(EcosSeries.MORTGAGE, rate("5.02", "2026-11"));
        service.refresh();

        assertThat(service.current().liquid()).isEqualTo(rate("0.81", "2026-10"));
        assertThat(service.current().mortgage()).isEqualTo(rate("5.02", "2026-11"));
        assertThat(params.getEngineParams().stats().liquid().annualRate()).isEqualTo(0.0081);
        assertThat(RateDefaults.loan(LoanType.MORTGAGE)).isEqualByComparingTo("5.02");
    }

    @Test
    void 처음부터_받지_못하면_params_json_값_그대로() {
        service(source(true, Map.of())).refresh();

        assertThat(params.getEngineParams().general().rate()).isEqualTo(0.0303);
        assertThat(RateDefaults.loan(LoanType.MORTGAGE)).isEqualByComparingTo("4.7");
        assertThat(RateDefaults.forMeta().asOf()).isNull();
    }

    private MarketRateService service(RateSource source) {
        return new MarketRateService(source, params);
    }

    /** 가짜 ECOS: 정해 둔 값을 돌려주고, 값이 없는 항목은 요청이 실패한 것처럼 예외 */
    private static RateSource source(boolean enabled, Map<EcosSeries, MarketRates.Rate> values) {
        return new RateSource() {
            @Override
            public boolean enabled() {
                return enabled;
            }

            @Override
            public Optional<MarketRates.Rate> latest(EcosSeries series) {
                if (!values.containsKey(series)) throw new IllegalStateException("ECOS 연결 실패");
                return Optional.of(values.get(series));
            }
        };
    }

    private static MarketRates.Rate rate(String value, String month) {
        return new MarketRates.Rate(new BigDecimal(value), month);
    }

    private static SimulationInput.LoanItem loan(LoanType type, BigDecimal annualRate) {
        return new SimulationInput.LoanItem(null, type, 10_000_000L, annualRate, 24, null);
    }

    private static JsonNode ratesEntry(JsonNode meta) {
        for (JsonNode p : meta.get("parameters")) {
            if ("rates".equals(p.get("key").asString())) return p;
        }
        throw new AssertionError("meta.parameters에 rates 칸이 없어요");
    }
}
