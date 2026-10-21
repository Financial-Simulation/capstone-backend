package com.example.capstoneserver.example;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.capstoneserver.example.dto.ExamplesResponse.Example;
import com.example.capstoneserver.input.InputValidationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ExampleControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ExampleController exampleController;

    @Autowired
    InputValidationService inputValidationService;

    @Test
    void 예시_3개를_돌려준다() throws Exception {
        mockMvc.perform(get("/api/examples"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("COMMON200"))
                .andExpect(jsonPath("$.result.examples.length()").value(3))
                .andExpect(jsonPath("$.result.examples[0].key").value("S1"))
                .andExpect(jsonPath("$.result.examples[0].label").value("27세 개발자"))
                .andExpect(jsonPath("$.result.examples[0].input.income.monthlyNetIncome").value(2_500_000))
                .andExpect(jsonPath("$.result.examples[1].input.loans.items[0].principal").value(8_000_000))
                .andExpect(jsonPath("$.result.examples[2].input.person.employmentType").value("CONTRACT"))
                .andExpect(jsonPath("$.result.examples[2].input.events[0].eventType").value("MOVE"))
                // 계산용 도우미 값(resolvedTotal 등)이 응답에 섞여 나오면 안 됨
                .andExpect(jsonPath("$.result.examples[0].input.expenses.fixed.resolvedTotal").doesNotExist())
                .andExpect(jsonPath("$.result.examples[0].input.expenses.fixed.resolvedTotalInRange").doesNotExist());
    }

    @Test
    void 예시는_모두_계산_직전_검사를_통과한다() {
        // 예시를 누르면 바로 /simulate 로 가므로, 필수 칸까지 전부 맞아야 한다
        for (Example example : exampleController.examples().result().examples()) {
            assertThatCode(() -> inputValidationService.checkRequired(example.input()))
                    .as(example.key())
                    .doesNotThrowAnyException();
        }
    }
}
