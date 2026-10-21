package com.example.capstoneserver.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.capstoneserver.global.exception.FieldValidationException;
import com.example.capstoneserver.global.exception.GeneralException;
import com.example.capstoneserver.input.InputValidationService;
import com.example.capstoneserver.input.dto.SimulationInput;
import com.example.capstoneserver.input.dto.SimulationInput.Event;
import com.example.capstoneserver.input.dto.SimulationInput.EventType;
import com.example.capstoneserver.input.dto.SimulationInput.InputMode;
import com.example.capstoneserver.input.dto.SimulationInput.PresetKey;
import com.example.capstoneserver.loan.LoanCalculator;
import com.example.capstoneserver.plan.dto.Adjustments;
import com.example.capstoneserver.plan.dto.Change;
import jakarta.validation.Validation;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PlanAdjusterTest {

    private final PlanAdjuster adjuster = new PlanAdjuster();
    private final InputValidationService inputValidationService = new InputValidationService(
            Validation.buildDefaultValidatorFactory().getValidator(), new LoanCalculator());

    private SimulationInput s1;

    @BeforeEach
    void setUp() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/examples/S1.json")) {
            s1 = JsonMapper.builder().build().readValue(in, SimulationInput.class);
        }
    }

    @Test
    void 변동비_조정_명세서_예시와_같다() {
        SimulationInput adjusted = adjuster.apply(s1, only().monthlyVariableExpense(850_000L).build());

        assertThat(adjusted.expenses().variable().inputMode()).isEqualTo(InputMode.TOTAL);
        assertThat(adjusted.expenses().variable().resolvedTotal()).isEqualTo(850_000L);
        assertThat(adjuster.changes(s1, adjusted)).containsExactly(
                new Change("monthlyVariableExpense", "월 변동비", 950_000L, 850_000L, -100_000));
    }

    @Test
    void 원래_입력은_바뀌지_않는다() {
        adjuster.apply(s1, only().monthlySaving(700_000L).build());

        assertThat(s1.savingPlan().monthlySaving()).isEqualTo(500_000L);
    }

    @Test
    void 조정하지_않은_값은_그대로다() {
        SimulationInput adjusted = adjuster.apply(s1, only().monthlySaving(700_000L).build());

        assertThat(adjusted.savingPlan().monthlySaving()).isEqualTo(700_000L);
        assertThat(adjusted.savingPlan().generalSavingRate()).isEqualByComparingTo("3.03");
        assertThat(adjusted.income()).isEqualTo(s1.income());
        assertThat(adjusted.loans()).isEqualTo(s1.loans());
        assertThat(adjusted.goal().name()).isEqualTo("5년 뒤 3,000만 원");
    }

    @Test
    void 원래와_같은_값은_변경_요약에_없다() {
        SimulationInput adjusted = adjuster.apply(s1, only().monthlySaving(500_000L).build());

        assertThat(adjuster.changes(s1, adjusted)).isEmpty();
    }

    @Test
    void 목표_시점은_개월_차이로_요약한다() {
        SimulationInput adjusted = adjuster.apply(s1, only().goalTargetDate("2032-11").build());

        assertThat(adjuster.changes(s1, adjusted)).containsExactly(
                new Change("goalTargetDate", "목표 시점", "2031-11", "2032-11", 12));
    }

    @Test
    void 예정된_일은_목록_전체를_바꾼다() {
        Event wedding = new Event(PresetKey.WEDDING, "결혼", "2028-05", EventType.ONE_TIME,
                8_000_000L, null, null, null, null);
        SimulationInput adjusted = adjuster.apply(s1, only().events(List.of(wedding)).build());

        assertThat(adjusted.events()).containsExactly(wedding);
        assertThat(adjuster.changes(s1, adjusted)).containsExactly(
                new Change("events", "예정된 일", 0, 1, 1));
    }

    @Test
    void 조정값이_비어_있으면_COMMON400() {
        assertThatThrownBy(() -> adjuster.apply(s1, only().build()))
                .isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> adjuster.apply(s1, null))
                .isInstanceOf(FieldValidationException.class);
    }

    @Test
    void 조정한_뒤의_값도_입력_규칙으로_검사한다() {
        // 목표 시점을 시작 달 + 6개월로 당기면 GOAL4001
        SimulationInput adjusted = adjuster.apply(s1, only().goalTargetDate("2027-05").build());

        assertThatThrownBy(() -> inputValidationService.checkRequired(adjusted))
                .isInstanceOf(GeneralException.class)
                .hasMessageContaining("12개월");
    }

    @Test
    void 조정한_뒤_범위를_넘으면_칸별_오류() {
        SimulationInput adjusted = adjuster.apply(s1, only().monthlySaving(-1L).build());

        assertThatThrownBy(() -> inputValidationService.checkRequired(adjusted))
                .isInstanceOfSatisfying(FieldValidationException.class, e ->
                        assertThat(e.getErrors()).containsKey("savingPlan.monthlySaving"));
    }

    // ===== 테스트용: 필요한 칸만 채운 Adjustments 만들기 =====

    private static AdjBuilder only() {
        return new AdjBuilder();
    }

    private static class AdjBuilder {
        Long monthlyVariableExpense, monthlySaving;
        String goalTargetDate;
        List<Event> events;

        AdjBuilder monthlyVariableExpense(Long v) { monthlyVariableExpense = v; return this; }
        AdjBuilder monthlySaving(Long v) { monthlySaving = v; return this; }
        AdjBuilder goalTargetDate(String v) { goalTargetDate = v; return this; }
        AdjBuilder events(List<Event> v) { events = v; return this; }

        Adjustments build() {
            return new Adjustments(null, null, monthlyVariableExpense, monthlySaving,
                    null, null, null, null, goalTargetDate, events);
        }
    }
}
