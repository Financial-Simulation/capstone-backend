package com.example.capstoneserver.simulation.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.capstoneserver.input.dto.SimulationInput.EmploymentType;
import com.example.capstoneserver.loan.LoanCalculator;
import com.example.capstoneserver.simulation.ParamsRepository;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * 기록 모드(trace)가 계산을 바꾸지 않는지 본다: 미래 하나를 다시 돌려 달마다 기록한 모은 돈이
 * simulate의 그 미래 값과 원 단위가 아니라 double까지 같아야 한다.
 */
class EngineTraceTest {

    private static final int PATHS = 300;
    private static final int MONTHS = 120;

    private final SimulationEngine engine = new SimulationEngine(new LoanCalculator());
    private final EngineParams params = new ParamsRepository(JsonMapper.builder().build()).getEngineParams();
    private final NoiseSource noise = NoiseSource.sfc32(20261007, PATHS);

    @Test
    void 기록한_모은_돈이_simulate와_같다_상품_있음() {
        // 청년미래 · 청약 · 연금저축 · 월세가 모두 있어 세금 계산이 켜지는 프로필
        assertSameAsSimulate(new EngineProfile(27, 2, EmploymentType.REGULAR, 250, 95, 95, 200, 0, 50, 0, null, 50,
                List.of(), List.of(), 3400.0, 40, true, 11, 30.0, 10, 10));
    }

    @Test
    void 기록한_모은_돈이_simulate와_같다_상품_없음() {
        assertSameAsSimulate(new EngineProfile(27, 2, EmploymentType.REGULAR, 250, 95, 95, 200, 0, 50, 0, null, 50,
                List.of(), List.of()));
    }

    @Test
    void 청년미래_재가입은_일하는_중에만() {
        EngineProfile profile = new EngineProfile(27, 2, EmploymentType.REGULAR, 250, 95, 95, 200, 0, 50, 0, null, 50,
                List.of(), List.of());
        int rejoins = 0;
        List<String> bad = new ArrayList<>();
        for (int p = 0; p < PATHS; p++) {
            for (PathTrace.Month m : engine.trace(profile, params, noise, p, MONTHS, 0).months()) {
                if (!m.has(PathTrace.REJOIN_POLICY)) continue;
                rejoins++;
                if (!m.employed()) bad.add("path " + p + " month " + m.t());
            }
        }
        assertThat(rejoins > 0).as("재가입이 한 번은 있어야 검사가 된다").isTrue();
        assertThat(bad).as("실직 중 재가입").isEmpty();
    }

    private void assertSameAsSimulate(EngineProfile profile) {
        EngineResult result = engine.simulate(profile, params, noise, PATHS, MONTHS, 0, true);
        List<String> bad = new ArrayList<>();
        for (int p = 0; p < PATHS; p += 7) {
            PathTrace trace = engine.trace(profile, params, noise, p, MONTHS, 0);
            assertThat(trace.months().size()).isEqualTo(MONTHS);
            for (PathTrace.Month m : trace.months()) {
                if (Double.compare(m.total(), result.at(p, m.t() + 1)) != 0) {
                    bad.add("path " + p + " month " + m.t() + ": " + m.total() + " != " + result.at(p, m.t() + 1));
                }
            }
        }
        assertThat(bad).as("simulate와 다른 달").isEmpty();
    }
}
