package com.example.capstoneserver.simulation.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.capstoneserver.input.dto.SimulationInput.EmploymentType;
import com.example.capstoneserver.loan.LoanCalculator;
import com.example.capstoneserver.simulation.ParamsRepository;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * 상품 배분이 월 저축액을 넘지 않는지 본다.
 * 청년미래도 청약·연금저축처럼 월 저축액까지만 넣는다 (예: 월 저축 20만 원에 청년미래 50만 원 → 20만 원).
 */
class EngineAllocationTest {

    private static final int PATHS = 200;
    private static final int MONTHS = 120;

    private final SimulationEngine engine = new SimulationEngine(new LoanCalculator());
    private final EngineParams params = new ParamsRepository(JsonMapper.builder().build()).getEngineParams();
    private final NoiseSource noise = NoiseSource.sfc32(20261007, PATHS);

    @Test
    void 청년미래가_월_저축액보다_크면_월_저축액까지만_넣는다() {
        assertCappedToSaving(0, 0);
    }

    @Test
    void 청약_연금저축을_함께_넣어도_청년미래는_월_저축액까지만() {
        assertCappedToSaving(10, 10);
    }

    /** 월 저축 20만 원 + 청년미래 50만 원 = 청년미래 20만 원을 넣은 것과 결과가 같아야 한다 (단위: 만 원) */
    private void assertCappedToSaving(double housingM, double pensionM) {
        EngineResult over = run(profile(20, 50.0, housingM, pensionM));
        EngineResult same = run(profile(20, 20.0, housingM, pensionM));

        assertThat(over.alloc().policy()).isEqualTo(20.0);
        assertThat(over.alloc().housing()).isEqualTo(0.0);   // 청년미래가 저축액을 다 쓰므로 뒤 상품은 0
        assertThat(over.alloc().pension()).isEqualTo(0.0);
        assertThat(Arrays.equals(over.saved(), same.saved())).as("모은 돈이 청년미래 20만 원 입력과 같다").isTrue();
        assertThat(Arrays.equals(over.matchPaid(), same.matchPaid())).as("정부 기여금도 20만 원 기준").isTrue();
    }

    private EngineResult run(EngineProfile profile) {
        return engine.simulate(profile, params, noise, PATHS, MONTHS, 0, true);
    }

    private static EngineProfile profile(double save, Double policyM, double housingM, double pensionM) {
        return new EngineProfile(27, 2, EmploymentType.REGULAR, 250, 90, 80, 300, 0, save, 0, null, 50,
                List.of(), List.of(), 3000.0, 0, true, 1, policyM, housingM, pensionM);
    }
}
