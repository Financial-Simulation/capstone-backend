package com.example.capstoneserver.input.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * 사용자 입력 전체. 브라우저(simInput.v1)에 저장되고, 계산 API가 매번 본문 input으로 받는다.
 * 노션 「선빈 api 명세서」의 'SimulationInput' 표를 그대로 옮긴 것.
 *
 * - @NotNull(groups = InputStep.Xxx.class) : 그 화면까지 왔으면 반드시 채워야 하는 칸
 * - @Min, @Max 등 (꼬리표 없음)               : 값이 있으면 항상 검사하는 범위
 * - 여러 칸을 같이 봐야 하는 규칙(경력 ≤ 나이 − 15 등)은 오류 코드가 따로 있어서 서비스에서 검사한다.
 */
public record SimulationInput(

        @NotNull(groups = InputStep.Basic.class, message = "기준 연월을 입력해 주세요")
        @Pattern(regexp = YEAR_MONTH, message = "YYYY-MM 형식으로 입력해 주세요")
        String startMonth,

        @NotNull(groups = InputStep.Basic.class, message = "나이·경력을 입력해 주세요")
        @Valid Person person,

        @NotNull(groups = InputStep.Basic.class, message = "소득을 입력해 주세요")
        @Valid Income income,

        @NotNull(groups = InputStep.Basic.class, message = "지출을 입력해 주세요")
        @Valid Expenses expenses,

        @NotNull(groups = InputStep.Assets.class, message = "자산을 입력해 주세요")
        @Valid Assets assets,

        @NotNull(groups = InputStep.Assets.class, message = "저축 계획을 입력해 주세요")
        @Valid SavingPlan savingPlan,

        @Valid Emergency emergency,

        @NotNull(groups = InputStep.Assets.class, message = "대출 정보를 입력해 주세요")
        @Valid Loans loans,

        @NotNull(groups = InputStep.Goal.class, message = "목표를 입력해 주세요")
        @Valid Goal goal,

        List<@Valid Event> events
) {

    static final String YEAR_MONTH = "^\\d{4}-(0[1-9]|1[0-2])$";

    public SimulationInput {
        if (emergency == null) emergency = new Emergency(null);
        if (events == null) events = List.of();
    }

    // ===== 01-1 =====

    public record Person(
            @NotNull(groups = InputStep.Basic.class, message = "나이를 입력해 주세요")
            @Min(value = 19, message = "19~39 사이로 입력해 주세요")
            @Max(value = 39, message = "19~39 사이로 입력해 주세요")
            Integer age,

            @NotNull(groups = InputStep.Basic.class, message = "경력을 입력해 주세요")
            @Min(value = 0, message = "0~10년 사이로 입력해 주세요")
            @Max(value = 10, message = "0~10년 사이로 입력해 주세요")
            Integer careerYears,

            EmploymentType employmentType
    ) {
        public Person {
            if (employmentType == null) employmentType = EmploymentType.REGULAR;
        }
    }

    public enum EmploymentType { REGULAR, CONTRACT }

    public record Income(
            @NotNull(groups = InputStep.Basic.class, message = "월 실수령액을 입력해 주세요")
            @Min(value = 10_000, message = "1만 원 ~ 3,000만 원 사이로 입력해 주세요")
            @Max(value = 30_000_000, message = "1만 원 ~ 3,000만 원 사이로 입력해 주세요")
            Long monthlyNetIncome
    ) {}

    public record Expenses(
            @NotNull(groups = InputStep.Basic.class, message = "고정지출을 입력해 주세요")
            @Valid ExpenseGroup fixed,

            @NotNull(groups = InputStep.Basic.class, message = "변동지출을 입력해 주세요")
            @Valid ExpenseGroup variable
    ) {}

    /** 지출 한 묶음. DETAIL이면 items 합계, TOTAL이면 total을 쓴다. */
    public record ExpenseGroup(
            @NotNull(groups = InputStep.Basic.class, message = "입력 방식을 골라 주세요")
            InputMode inputMode,

            List<@Valid ExpenseItem> items,

            @Min(value = 0, message = "0원 이상으로 입력해 주세요")
            Long total
    ) {
        public ExpenseGroup {
            if (items == null) items = List.of();
        }

        /** 이 묶음의 확정 합계. 아직 입력 전이면 null */
        public Long resolvedTotal() {
            if (inputMode == InputMode.DETAIL) {
                return items.stream().mapToLong(i -> i.amount() == null ? 0 : i.amount()).sum();
            }
            return total;
        }

        @JsonIgnore
        @AssertTrue(message = "합계는 0원 ~ 3,000만 원 사이여야 해요")
        public boolean isResolvedTotalInRange() {
            Long sum = resolvedTotal();
            return sum == null || (sum >= 0 && sum <= 30_000_000);
        }
    }

    public enum InputMode { DETAIL, TOTAL }

    public record ExpenseItem(
            @Size(max = 20, message = "20자 이내로 입력해 주세요")
            String name,

            @NotNull(message = "금액을 입력해 주세요")
            @Min(value = 0, message = "0원 이상으로 입력해 주세요")
            Long amount
    ) {}

    // ===== 01-2 =====

    public record Assets(
            @NotNull(groups = InputStep.Assets.class, message = "현금·입출금 금액을 입력해 주세요")
            @Min(value = 0, message = "0원 이상으로 입력해 주세요")
            Long cash,

            @NotNull(groups = InputStep.Assets.class, message = "예·적금 금액을 입력해 주세요")
            @Min(value = 0, message = "0원 이상으로 입력해 주세요")
            Long deposits,

            @Min(value = 0, message = "0원 ~ 10억 원 사이로 입력해 주세요")
            @Max(value = 1_000_000_000, message = "0원 ~ 10억 원 사이로 입력해 주세요")
            Long investments
    ) {
        public Assets {
            if (investments == null) investments = 0L;
        }

        @JsonIgnore
        @AssertTrue(message = "현금과 예·적금을 합쳐 10억 원 이하로 입력해 주세요")
        public boolean isCashAndDepositsInRange() {
            if (cash == null || deposits == null) return true;
            return cash + deposits <= 1_000_000_000;
        }
    }

    public record SavingPlan(
            @NotNull(groups = InputStep.Assets.class, message = "월 저축액을 입력해 주세요")
            @Min(value = 0, message = "0원 ~ 3,000만 원 사이로 입력해 주세요")
            @Max(value = 30_000_000, message = "0원 ~ 3,000만 원 사이로 입력해 주세요")
            Long monthlySaving,

            @DecimalMin(value = "0", message = "0~10% 사이로 입력해 주세요")
            @DecimalMax(value = "10", message = "0~10% 사이로 입력해 주세요")
            BigDecimal generalSavingRate,

            @Min(value = 0, message = "0원 ~ 3,000만 원 사이로 입력해 주세요")
            @Max(value = 30_000_000, message = "0원 ~ 3,000만 원 사이로 입력해 주세요")
            Long monthlyInvestment,

            Long emergencyFirstAmount
    ) {
        private static final Set<Long> EMERGENCY_FIRST_OPTIONS =
                Set.of(0L, 1_000_000L, 2_000_000L, 3_000_000L, 5_000_000L);

        public SavingPlan {
            if (generalSavingRate == null) generalSavingRate = new BigDecimal("3.03");
            if (monthlyInvestment == null) monthlyInvestment = 0L;
            if (emergencyFirstAmount == null) emergencyFirstAmount = 0L;
        }

        @JsonIgnore
        @AssertTrue(message = "0 / 100만 / 200만 / 300만 / 500만 원 중에서 골라 주세요")
        public boolean isEmergencyFirstAmountAllowed() {
            return EMERGENCY_FIRST_OPTIONS.contains(emergencyFirstAmount);
        }
    }

    public record Emergency(
            @Min(value = 0, message = "0원 ~ 1억 원 사이로 입력해 주세요")
            @Max(value = 100_000_000, message = "0원 ~ 1억 원 사이로 입력해 주세요")
            Long breakThreshold
    ) {
        public Emergency {
            if (breakThreshold == null) breakThreshold = 500_000L;
        }
    }

    public record Loans(
            @NotNull(groups = InputStep.Assets.class, message = "대출이 있는지 골라 주세요")
            Boolean hasNoLoan,

            List<@Valid LoanItem> items
    ) {
        public Loans {
            if (items == null) items = List.of();
        }
    }

    public record LoanItem(
            @Size(max = 20, message = "20자 이내로 입력해 주세요")
            String name,

            @NotNull(message = "대출 종류를 골라 주세요")
            LoanType loanType,

            @NotNull(message = "남은 원금을 입력해 주세요")
            @Min(value = 1, message = "1원 이상으로 입력해 주세요")
            Long principal,

            @DecimalMin(value = "0", message = "0~20% 사이로 입력해 주세요")
            @DecimalMax(value = "20", message = "0~20% 사이로 입력해 주세요")
            BigDecimal annualRate,

            @NotNull(message = "남은 기간을 입력해 주세요")
            @Min(value = 1, message = "1~480개월 사이로 입력해 주세요")
            @Max(value = 480, message = "1~480개월 사이로 입력해 주세요")
            Integer remainingMonths,

            RepaymentType repaymentType
    ) {
        /** 비워 두면 대출 종류별 기본 금리 */
        public BigDecimal resolvedRate() {
            return annualRate != null ? annualRate : loanType.getDefaultRate();
        }

        /** 비워 두면 대출 종류별 기본 상환 방식 */
        public RepaymentType resolvedRepaymentType() {
            return repaymentType != null ? repaymentType : loanType.getDefaultRepaymentType();
        }
    }

    // ===== 02 =====

    public record Goal(
            @Size(max = 30, message = "30자 이내로 입력해 주세요")
            String name,

            @NotNull(groups = InputStep.Goal.class, message = "목표 금액을 입력해 주세요")
            @Min(value = 10_000, message = "1만 원 ~ 10억 원 사이로 입력해 주세요")
            @Max(value = 1_000_000_000, message = "1만 원 ~ 10억 원 사이로 입력해 주세요")
            Long targetAmount,

            @NotNull(groups = InputStep.Goal.class, message = "목표 시점을 입력해 주세요")
            @Pattern(regexp = YEAR_MONTH, message = "YYYY-MM 형식으로 입력해 주세요")
            String targetDate
    ) {
        public Goal {
            if (name == null || name.isBlank()) name = "내 목표";
        }
    }

    // ===== 03 =====

    /**
     * 예정된 일. eventType에 따라 쓰는 금액 칸이 다르다.
     * ONE_TIME: amount / SPEND_CHANGE: monthlyChange / JOB_CHANGE: incomeChangeRate, gapMonths
     * MOVE: deposit, monthlyChange, amount
     * 종류와 칸이 맞는지(EVENT4001), 범위(EVENT4002·4004)는 서비스에서 검사한다.
     */
    public record Event(
            @NotNull(message = "프리셋을 골라 주세요")
            PresetKey presetKey,

            @Size(max = 20, message = "20자 이내로 입력해 주세요")
            String name,

            @NotNull(message = "발생 시점을 입력해 주세요")
            @Pattern(regexp = YEAR_MONTH, message = "YYYY-MM 형식으로 입력해 주세요")
            String occurDate,

            @NotNull(message = "종류를 골라 주세요")
            EventType eventType,

            @Min(value = 0, message = "0원 이상으로 입력해 주세요")
            Long amount,

            Long monthlyChange,

            BigDecimal incomeChangeRate,

            Integer gapMonths,

            @Min(value = 0, message = "0원 이상으로 입력해 주세요")
            Long deposit
    ) {}

    public enum PresetKey { INDEPENDENCE, JEONSE_MOVE, WEDDING, JOB_CHANGE, CAR, CUSTOM }

    public enum EventType { ONE_TIME, SPEND_CHANGE, JOB_CHANGE, MOVE }
}
