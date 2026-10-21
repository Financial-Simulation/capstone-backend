package com.example.capstoneserver.event.dto;

import com.example.capstoneserver.input.dto.SimulationInput.EventType;
import com.example.capstoneserver.input.dto.SimulationInput.PresetKey;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.List;

/**
 * GET /api/events/presets 응답.
 * 프론트가 기본값을 입력창에 채우고, occurDate·name을 붙여 input.events[]에 넣는다.
 */
public record PresetsResponse(List<Preset> presets) {

    public record Preset(
            PresetKey presetKey,
            String name,
            String basis,               // 기본 금액의 근거 (지금은 모두 가정값)
            List<EventTemplate> events  // 자동차처럼 한 프리셋이 일 여러 개를 만들 수 있다
    ) {}

    /** 예정된 일 한 건의 기본값. 종류에 맞는 칸만 채워지고 나머지는 응답에서 빠진다. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EventTemplate(
            EventType eventType,
            Long amount,
            Long monthlyChange,
            BigDecimal incomeChangeRate,
            Integer gapMonths,
            Long deposit
    ) {
        public static EventTemplate oneTime(long amount) {
            return new EventTemplate(EventType.ONE_TIME, amount, null, null, null, null);
        }

        public static EventTemplate spendChange(long monthlyChange) {
            return new EventTemplate(EventType.SPEND_CHANGE, null, monthlyChange, null, null, null);
        }

        public static EventTemplate jobChange(String incomeChangeRate, int gapMonths) {
            return new EventTemplate(EventType.JOB_CHANGE, null, null, new BigDecimal(incomeChangeRate), gapMonths, null);
        }

        public static EventTemplate move(long deposit, long monthlyChange, long amount) {
            return new EventTemplate(EventType.MOVE, amount, monthlyChange, null, null, deposit);
        }
    }
}
