package com.example.capstoneserver.simulation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class SimulationControllerTest {

    @Autowired
    MockMvc mockMvc;

    String s1;

    @BeforeEach
    void loadInput() throws IOException {
        s1 = new ClassPathResource("examples/S1.json").getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void simulate_S1이_API_명세_예시와_같다() throws Exception {
        mockMvc.perform(post("/api/simulate").contentType(MediaType.APPLICATION_JSON).content("{\"input\":" + s1 + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("COMMON200"))
                .andExpect(jsonPath("$.result.seed").value(20261007))
                .andExpect(jsonPath("$.result.modelVersion").value("1.6"))
                .andExpect(jsonPath("$.result.goal.probability").value(58.0))
                .andExpect(jsonPath("$.result.goal.targetDate").value("2031-11"))
                .andExpect(jsonPath("$.result.deterministic.atGoal").value(44396264))
                .andExpect(jsonPath("$.result.bands.length()").value(121))
                .andExpect(jsonPath("$.result.warnings[0].code").value("WARN_MONTHLY_SHORTFALL"));
    }

    @Test
    void 같은_입력과_seed면_결과가_같다() throws Exception {
        String body = "{\"input\":" + s1 + ",\"seed\":7}";
        assertThat(simulate(body)).isEqualTo(simulate(body));
    }

    @Test
    void 필수_칸이_비면_계산하지_않는다() throws Exception {
        mockMvc.perform(post("/api/simulate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":{\"startMonth\":\"2026-11\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON400"))
                .andExpect(jsonPath("$.result.person").exists());
    }

    @Test
    void preview는_변경_요약을_함께_준다() throws Exception {
        mockMvc.perform(post("/api/simulate/preview").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":" + s1 + ",\"adjustments\":{\"monthlyVariableExpense\":850000}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.adjusted.probability").value(70.5))
                .andExpect(jsonPath("$.result.significance.z").value(16.86))
                .andExpect(jsonPath("$.result.changes[0].field").value("monthlyVariableExpense"));
    }

    @Test
    void compare_계획이_없거나_4개_이상이면_SIM4003() throws Exception {
        String plan = "{\"name\":\"a\",\"adjustments\":{\"monthlySaving\":100000}}";
        mockMvc.perform(post("/api/compare").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":" + s1 + ",\"plans\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SIM4003"));
        mockMvc.perform(post("/api/compare").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":" + s1 + ",\"plans\":[" + plan + "," + plan + "," + plan + "," + plan + "]}"))
                .andExpect(jsonPath("$.code").value("SIM4003"));
    }

    @Test
    void compare_첫_항목은_지금_계획이다() throws Exception {
        mockMvc.perform(post("/api/compare").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":" + s1 + ",\"plans\":[{\"name\":\"변동지출 줄이기\",\"adjustments\":{\"monthlyVariableExpense\":850000}}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.plans[0].isBase").value(true))
                .andExpect(jsonPath("$.result.plans[0].z").isEmpty())
                .andExpect(jsonPath("$.result.plans[1].isBase").value(false))
                .andExpect(jsonPath("$.result.plans[1].diffFromBase").value(12.5));
    }

    @Test
    void reverse_목표_확률이_범위_밖이면_거절한다() throws Exception {
        mockMvc.perform(post("/api/reverse").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":" + s1 + ",\"targetProbability\":40}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON400"));
    }

    @Test
    void robust_접수하면_202이고_폴링으로_결과를_받는다() throws Exception {
        String response = mockMvc.perform(post("/api/robust").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":" + s1 + "}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value("COMMON202"))
                .andExpect(jsonPath("$.result.trials").value(60))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String jobId = JsonPath.read(response, "$.result.jobId");

        String status = "RUNNING";
        for (int i = 0; i < 600 && status.equals("RUNNING"); i++) {
            Thread.sleep(100);
            String poll = mockMvc.perform(get("/api/robust/" + jobId))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            status = JsonPath.read(poll, "$.result.status");
        }
        assertThat(status).isEqualTo("DONE");

        mockMvc.perform(get("/api/robust/" + jobId))
                .andExpect(jsonPath("$.result.probability.median").value(66.4))
                .andExpect(jsonPath("$.result.progress").value(100));
    }

    @Test
    void 없는_작업을_조회하면_JOB404() throws Exception {
        mockMvc.perform(get("/api/robust/rb-none"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("JOB404"));
    }

    @Test
    void meta는_파라미터_근거를_돌려준다() throws Exception {
        mockMvc.perform(get("/api/meta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.modelVersion").value("1.6"))
                .andExpect(jsonPath("$.result.defaultSeed").value(20261007))
                .andExpect(jsonPath("$.result.parameters[0].grade").exists());
    }

    private String simulate(String body) throws Exception {
        return mockMvc.perform(post("/api/simulate").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
