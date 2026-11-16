package com.example.capstoneserver.input.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.capstoneserver.input.InputValidationService;
import com.example.capstoneserver.loan.LoanCalculator;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class SimulationInputValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    // 명세서 '공통 규칙'의 S1 예시
    private static final String S1 = """
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

    // 01-1 화면까지만 채운 입력
    private static final String BASIC_ONLY = """
            {
              "startMonth": "2026-11",
              "person": { "age": 27, "careerYears": 2 },
              "income": { "monthlyNetIncome": 2500000 },
              "expenses": {
                "fixed": { "inputMode": "TOTAL", "total": 950000 },
                "variable": { "inputMode": "TOTAL", "total": 950000 }
              }
            }
            """;

    @Test
    void S1_예시는_전체_검사를_통과한다() {
        assertThat(validate(S1, InputStep.ALL)).isEmpty();
    }

    @Test
    void 나이가_범위_밖이면_필드별_메시지가_나온다() {
        String input = S1.replace("\"age\": 27", "\"age\": 17");

        assertThat(validate(input, InputStep.ALL))
                .containsEntry("person.age", "19~39 사이로 입력해 주세요");
    }

    @Test
    void 지금_화면까지만_검사한다() {
        // 01-1에서 '다음' → 아직 안 나온 자산·목표 칸은 비어 있어도 된다
        assertThat(validate(BASIC_ONLY, InputStep.BASIC)).isEmpty();

        // 01-2에서 '다음' → 자산·저축·대출 칸이 비어 있으면 오류
        assertThat(validate(BASIC_ONLY, InputStep.ASSETS))
                .containsKeys("assets", "savingPlan", "loans")
                .doesNotContainKey("goal");
    }

    @Test
    void 비워둔_칸에는_기본값이_들어간다() {
        SimulationInput input = jsonMapper.readValue(BASIC_ONLY, SimulationInput.class);

        assertThat(input.person().employmentType()).isEqualTo(SimulationInput.EmploymentType.REGULAR);
        assertThat(input.emergency().breakThreshold()).isEqualTo(500_000L);
        assertThat(input.events()).isEmpty();
    }

    @Test
    void 대출_금리를_비우면_종류별_기본값을_쓴다() {
        SimulationInput.LoanItem loan = new SimulationInput.LoanItem(
                null, LoanType.JEONSE, 100_000_000L, null, 24, null);

        assertThat(loan.resolvedRate()).isEqualByComparingTo("4.3");
        assertThat(loan.resolvedRepaymentType()).isEqualTo(RepaymentType.INTEREST_ONLY);
    }

    @Test
    void 청약은_월_50만_원_연금저축은_월_150만_원까지() {
        String ok = S1.replace("\"emergencyFirstAmount\": 0 }", "\"emergencyFirstAmount\": 0, \"housingMonthly\": 500000, \"pensionMonthly\": 1500000 }");
        assertThat(validate(ok, InputStep.ALL)).isEmpty();

        String over = S1.replace("\"emergencyFirstAmount\": 0 }", "\"emergencyFirstAmount\": 0, \"housingMonthly\": 510000, \"pensionMonthly\": 1510000 }");
        assertThat(validate(over, InputStep.ALL))
                .containsEntry("savingPlan.housingMonthly", "0원 ~ 50만 원 사이로 입력해 주세요")
                .containsEntry("savingPlan.pensionMonthly", "0원 ~ 150만 원 사이로 입력해 주세요 (연금계좌 한도 연 1,800만 원)");
    }

    @Test
    void 세전_연봉이_실수령액보다_많이_작으면_오류() {
        // 월 실수령 250만 원 × 12 = 3,000만 원. 세전 × 1.1이 이보다 작으면 오류
        String low = S1.replace("\"monthlyNetIncome\": 2500000 }", "\"monthlyNetIncome\": 2500000, \"annualGrossSalary\": 27000000 }");
        assertThat(validate(low, InputStep.ALL))
                .containsEntry("income.grossConsistentWithNet", "세전 연봉이 월 실수령액 × 12보다 작아요. 세금을 떼기 전 금액을 넣어 주세요");

        // 비과세 식대를 뺀 총급여처럼 실수령 × 12보다 조금 작은 값은 통과 (10% 여유)
        String edge = S1.replace("\"monthlyNetIncome\": 2500000 }", "\"monthlyNetIncome\": 2500000, \"annualGrossSalary\": 27300000 }");
        assertThat(validate(edge, InputStep.ALL)).isEmpty();
    }

    @Test
    void 청년미래가_월_저축액보다_크면_월_저축액까지만_넣는다() {
        // 엔진과 같은 규칙: 월 저축 20만 원에 청년미래 50만 원 → 청년미래 20만 원, 일반 적금 0원
        String json = S1.replace("\"monthlySaving\": 500000", "\"monthlySaving\": 200000")
                .replace("\"emergencyFirstAmount\": 0 }", "\"emergencyFirstAmount\": 0, \"policyMonthly\": 500000 }");
        SimulationInput input = jsonMapper.readValue(json, SimulationInput.class);

        ValidateResponse.PolicySaving policy = new InputValidationService(validator, new LoanCalculator())
                .validate(InputStep.ALL, input).policySaving();

        assertThat(policy.policyMonthly()).isEqualTo(200000L);
        assertThat(policy.generalMonthly()).isEqualTo(0L);
    }

    private Map<String, String> validate(String json, InputStep step) {
        SimulationInput input = jsonMapper.readValue(json, SimulationInput.class);
        Set<ConstraintViolation<SimulationInput>> violations = validator.validate(input, step.groupsUpToHere());
        return violations.stream().collect(Collectors.toMap(
                v -> v.getPropertyPath().toString(),
                ConstraintViolation::getMessage,
                (a, b) -> a));
    }
}
