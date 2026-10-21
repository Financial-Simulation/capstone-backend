package com.example.capstoneserver.input;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * POST /api/inputs/validate 를 실제로 호출해 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class InputControllerTest {

    @Autowired
    MockMvc mockMvc;

    // 명세서 '공통 규칙'의 S1 예시
    static final String S1 = """
            {
              "startMonth": "2026-11",
              "person": { "age": 27, "careerYears": 2, "employmentType": "REGULAR" },
              "income": { "monthlyNetIncome": 2500000 },
              "expenses": {
                "fixed": { "inputMode": "TOTAL", "items": [], "total": 950000 },
                "variable": { "inputMode": "TOTAL", "items": [], "total": 950000 }
              },
              "assets": { "cash": 2000000, "deposits": 0, "investments": 0 },
              "savingPlan": { "monthlySaving": 500000, "generalSavingRate": 3.03, "monthlyInvestment": 0, "emergencyFirstAmount": 0 },
              "emergency": { "breakThreshold": 500000 },
              "loans": { "hasNoLoan": false, "items": [
                { "name": "학자금대출", "loanType": "STUDENT", "principal": 12000000,
                  "annualRate": 1.9, "remainingMonths": 96, "repaymentType": "EQUAL_PAYMENT" } ] },
              "goal": { "name": "5년 뒤 3,000만 원", "targetAmount": 30000000, "targetDate": "2031-11" },
              "events": []
            }
            """;

    @Test
    void S1_ASSETS_명세서_응답_예시와_같다() throws Exception {
        call("ASSETS", S1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSuccess").value(true))
                .andExpect(jsonPath("$.code").value("COMMON200"))
                .andExpect(jsonPath("$.result.step").value("ASSETS"))
                .andExpect(jsonPath("$.result.summary.monthlyNetIncome").value(2_500_000))
                .andExpect(jsonPath("$.result.summary.fixedTotal").value(950_000))
                .andExpect(jsonPath("$.result.summary.variableTotal").value(950_000))
                .andExpect(jsonPath("$.result.summary.monthlyLoanPayment").value(134_839))
                .andExpect(jsonPath("$.result.summary.monthlySurplus").value(465_161))
                .andExpect(jsonPath("$.result.summary.monthlySaving").value(500_000))
                .andExpect(jsonPath("$.result.summary.totalAssets").value(2_000_000))
                .andExpect(jsonPath("$.result.summary.totalLoanBalance").value(12_000_000))
                .andExpect(jsonPath("$.result.policySaving.eligible").value(true))
                .andExpect(jsonPath("$.result.policySaving.policyMonthly").value(500_000))
                .andExpect(jsonPath("$.result.policySaving.generalMonthly").value(0))
                .andExpect(jsonPath("$.result.warnings.length()").value(1))
                .andExpect(jsonPath("$.result.warnings[0].code").value("WARN_MONTHLY_SHORTFALL"))
                .andExpect(jsonPath("$.result.warnings[0].message")
                        .value("저축액이 매달 남는 돈(46만 5,161원)보다 커서 계획만큼 넣지 못하는 달이 생겨요."));
    }

    @Test
    void 나이가_범위_밖이면_COMMON400과_칸별_메시지() throws Exception {
        call("ASSETS", S1.replace("\"age\": 27", "\"age\": 17"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.isSuccess").value(false))
                .andExpect(jsonPath("$.code").value("COMMON400"))
                .andExpect(jsonPath("$.result['person.age']").value("19~39 사이로 입력해 주세요"));
    }

    @Test
    void 경력이_나이_빼기_15보다_크면_INPUT4001() throws Exception {
        call("BASIC", S1.replace("\"age\": 27, \"careerYears\": 2", "\"age\": 20, \"careerYears\": 6"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INPUT4001"));
    }

    @Test
    void 대출_없음인데_목록이_있으면_LOAN4001() throws Exception {
        call("ASSETS", S1.replace("\"hasNoLoan\": false", "\"hasNoLoan\": true"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LOAN4001"));
    }

    @Test
    void 목표_시점이_12개월_안이면_GOAL4001() throws Exception {
        call("GOAL", S1.replace("\"2031-11\"", "\"2027-05\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("GOAL4001"));
    }

    @Test
    void BASIC_단계는_대출_합계를_비워둔다() throws Exception {
        call("BASIC", S1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.summary.monthlyNetIncome").value(2_500_000))
                .andExpect(jsonPath("$.result.summary.monthlyLoanPayment").doesNotExist())
                .andExpect(jsonPath("$.result.policySaving").doesNotExist());
    }

    @Test
    void 대출_상환액_미리보기_명세서_예시와_같다() throws Exception {
        mockMvc.perform(post("/api/loans/repayment-preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "loanType": "STUDENT", "principal": 12000000, "annualRate": 1.9,
                                  "remainingMonths": 96, "repaymentType": "EQUAL_PAYMENT" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.firstMonthPayment").value(134_839))
                .andExpect(jsonPath("$.result.firstMonthPrincipal").value(115_839))
                .andExpect(jsonPath("$.result.firstMonthInterest").value(19_000))
                .andExpect(jsonPath("$.result.lastMonthPayment").value(134_839))
                .andExpect(jsonPath("$.result.totalInterest").value(944_574));
    }

    private ResultActions call(String step, String input) throws Exception {
        return mockMvc.perform(post("/api/inputs/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"step\": \"" + step + "\", \"input\": " + input + " }"));
    }
}
