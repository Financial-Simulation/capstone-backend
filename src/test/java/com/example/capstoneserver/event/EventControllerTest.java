package com.example.capstoneserver.event;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class EventControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void 프리셋_5종을_명세서_예시대로_돌려준다() throws Exception {
        mockMvc.perform(get("/api/events/presets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("COMMON200"))
                .andExpect(jsonPath("$.result.presets.length()").value(5))

                // 독립: 이사 (보증금·주거비 변화·이사 비용)
                .andExpect(jsonPath("$.result.presets[0].presetKey").value("INDEPENDENCE"))
                .andExpect(jsonPath("$.result.presets[0].basis").value("가정값, 사용자가 고침"))
                .andExpect(jsonPath("$.result.presets[0].events[0].eventType").value("MOVE"))
                .andExpect(jsonPath("$.result.presets[0].events[0].deposit").value(5_000_000))
                .andExpect(jsonPath("$.result.presets[0].events[0].monthlyChange").value(450_000))
                .andExpect(jsonPath("$.result.presets[0].events[0].amount").value(800_000))

                // 결혼: 종류에 안 맞는 칸은 응답에 없어야 함
                .andExpect(jsonPath("$.result.presets[2].events[0].amount").value(8_000_000))
                .andExpect(jsonPath("$.result.presets[2].events[0].deposit").doesNotExist())

                // 이직
                .andExpect(jsonPath("$.result.presets[3].events[0].incomeChangeRate").value(10.0))
                .andExpect(jsonPath("$.result.presets[3].events[0].gapMonths").value(2))

                // 자동차: 일 2개 (구매 + 매달 유지비)
                .andExpect(jsonPath("$.result.presets[4].events.length()").value(2))
                .andExpect(jsonPath("$.result.presets[4].events[1].eventType").value("SPEND_CHANGE"))
                .andExpect(jsonPath("$.result.presets[4].events[1].monthlyChange").value(300_000));
    }
}
