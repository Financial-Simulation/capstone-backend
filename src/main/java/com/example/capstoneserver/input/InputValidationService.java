package com.example.capstoneserver.input;

import static com.example.capstoneserver.global.util.Money.toKorean;

import com.example.capstoneserver.global.exception.FieldValidationException;
import com.example.capstoneserver.global.exception.GeneralException;
import com.example.capstoneserver.global.response.ErrorStatus;
import com.example.capstoneserver.global.response.Warning;
import com.example.capstoneserver.input.dto.InputStep;
import com.example.capstoneserver.input.dto.SimulationInput;
import com.example.capstoneserver.input.dto.SimulationInput.Event;
import com.example.capstoneserver.input.dto.SimulationInput.ExpenseGroup;
import com.example.capstoneserver.input.dto.SimulationInput.LoanItem;
import com.example.capstoneserver.input.dto.ValidateResponse;
import com.example.capstoneserver.input.dto.ValidateResponse.PolicySaving;
import com.example.capstoneserver.input.dto.ValidateResponse.Summary;
import com.example.capstoneserver.loan.LoanCalculator;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 입력 검사. 저장하지 않고 오류·경고·확정 합계만 돌려준다.
 *
 * 들어온 값만 검사한다. 빈 칸은 검사하지 않는다 (필수 칸 확인은 프론트가 '다음' 버튼에서 한다).
 * 합계·경고도 계산에 필요한 값이 다 있을 때만 만든다. 없으면 null / 경고 없음.
 * 필수 칸까지 확인하는 검사는 checkRequired()로, /simulate 처럼 계산 직전에 쓴다.
 */
@Service
@RequiredArgsConstructor
public class InputValidationService {

    // 청년미래적금
    private static final int POLICY_MAX_AGE = 34;
    private static final long POLICY_MAX_GROSS_SALARY = 75_000_000;    // 총급여 7,500만 원
    private static final long POLICY_MONTHLY_LIMIT = 500_000;          // 월 50만 원까지
    private static final long POLICY_MATCH_MAX_GROSS_SALARY = 60_000_000;  // 정부 지원금(일반형 6%)은 총급여 6,000만 원 이하 (우리은행 상품설명서)

    private final Validator validator;
    private final LoanCalculator loanCalculator;

    public ValidateResponse validate(InputStep step, SimulationInput input) {
        checkFields(input, validator.validate(input));  // 1. 칸별 규칙 (나이 19~39 등) → COMMON400
        checkCrossRules(input);                          // 2. 여러 칸을 같이 보는 규칙 → INPUT4001 등

        // 3. 확정 합계·경고
        List<Warning> warnings = new ArrayList<>();
        Summary summary = summarize(input);
        PolicySaving policySaving = policySaving(input, warnings);

        warnIfExpenseOverIncome(summary, warnings);
        warnIfMonthlyShortfall(summary, warnings);
        warnIfGoalAlreadyMet(input, summary, warnings);

        return new ValidateResponse(step, summary, policySaving, warnings);
    }

    /** 계산 직전 검사: 필수 칸까지 전부 확인한다 (/simulate 등에서 사용). */
    public void checkRequired(SimulationInput input) {
        checkFields(input, validator.validate(input, InputStep.ALL.groupsUpToHere()));
        checkCrossRules(input);
    }

    // ===== 1. 칸별 규칙 =====

    private void checkFields(SimulationInput input, Iterable<ConstraintViolation<SimulationInput>> violations) {
        Map<String, String> errors = new TreeMap<>();
        for (ConstraintViolation<SimulationInput> v : violations) {
            errors.putIfAbsent(v.getPropertyPath().toString(), v.getMessage());
        }
        if (!errors.isEmpty()) {
            throw new FieldValidationException(errors);
        }
    }

    // ===== 2. 여러 칸을 같이 보는 규칙 (관련 칸이 다 있을 때만) =====

    private void checkCrossRules(SimulationInput input) {
        var person = input.person();
        if (person != null && person.age() != null && person.careerYears() != null
                && person.careerYears() > person.age() - 15) {
            throw new GeneralException(ErrorStatus.CAREER_EXCEEDS_AGE);
        }

        var loans = input.loans();
        if (loans != null && loans.hasNoLoan() != null
                && loans.hasNoLoan() != loans.items().isEmpty()) {
            throw new GeneralException(ErrorStatus.LOAN_ITEMS_MISMATCH);
        }

        YearMonth start = input.startMonth() == null ? null : YearMonth.parse(input.startMonth());

        var goal = input.goal();
        if (start != null && goal != null && goal.targetDate() != null) {
            long months = ChronoUnit.MONTHS.between(start, YearMonth.parse(goal.targetDate()));
            if (months < 12) throw new GeneralException(ErrorStatus.GOAL_TOO_SOON);
            if (months > 120) throw new GeneralException(ErrorStatus.GOAL_TOO_LATE);
        }

        for (Event event : input.events()) {
            checkEvent(start, event);
        }
    }

    private void checkEvent(YearMonth start, Event e) {
        // 종류별로 꼭 있어야 하는 금액 칸
        boolean fieldsOk = switch (e.eventType()) {
            case ONE_TIME -> e.amount() != null;
            case SPEND_CHANGE -> e.monthlyChange() != null;
            case JOB_CHANGE -> e.incomeChangeRate() != null && e.gapMonths() != null;
            case MOVE -> e.deposit() != null && e.monthlyChange() != null && e.amount() != null;
        };
        if (!fieldsOk) {
            throw new GeneralException(ErrorStatus.EVENT_FIELD_MISMATCH);
        }

        if (start != null) {
            long months = ChronoUnit.MONTHS.between(start, YearMonth.parse(e.occurDate()));
            if (months < 0 || months > 119) {
                throw new GeneralException(ErrorStatus.EVENT_DATE_OUT_OF_RANGE);
            }
        }

        if (e.eventType() == SimulationInput.EventType.JOB_CHANGE) {
            BigDecimal rate = e.incomeChangeRate();
            boolean rateOk = rate.compareTo(BigDecimal.valueOf(-90)) >= 0 && rate.compareTo(BigDecimal.valueOf(200)) <= 0;
            boolean gapOk = e.gapMonths() >= 0 && e.gapMonths() <= 24;
            if (!rateOk || !gapOk) {
                throw new GeneralException(ErrorStatus.EVENT_JOB_CHANGE_OUT_OF_RANGE);
            }
        }
    }

    // ===== 3. 확정 합계 (구할 수 없으면 null) =====

    private Summary summarize(SimulationInput input) {
        Long netIncome = input.income() == null ? null : input.income().monthlyNetIncome();
        Long fixed = input.expenses() == null ? null : totalOf(input.expenses().fixed());
        Long variable = input.expenses() == null ? null : totalOf(input.expenses().variable());

        Long loanPayment = null;
        Long loanBalance = null;
        var loans = input.loans();
        if (loans != null && loans.hasNoLoan() != null) {
            loanPayment = 0L;
            loanBalance = 0L;
            for (LoanItem loan : loans.items()) {
                loanPayment += loanCalculator.calculate(
                        loan.principal(), loan.resolvedRate(), loan.remainingMonths(), loan.resolvedRepaymentType()
                ).firstMonthPayment();
                loanBalance += loan.principal();
            }
        }

        Long surplus = allPresent(netIncome, fixed, variable, loanPayment)
                ? netIncome - fixed - variable - loanPayment
                : null;

        Long saving = input.savingPlan() == null ? null : input.savingPlan().monthlySaving();

        var assets = input.assets();
        Long totalAssets = assets != null && allPresent(assets.cash(), assets.deposits())
                ? assets.cash() + assets.deposits() + assets.investments()
                : null;

        return new Summary(netIncome, fixed, variable, loanPayment, surplus, saving, totalAssets, loanBalance);
    }

    private Long totalOf(ExpenseGroup group) {
        return group == null ? null : group.resolvedTotal();
    }

    private PolicySaving policySaving(SimulationInput input, List<Warning> warnings) {
        Integer age = input.person() == null ? null : input.person().age();
        Long netIncome = input.income() == null ? null : input.income().monthlyNetIncome();
        Long saving = input.savingPlan() == null ? null : input.savingPlan().monthlySaving();
        if (!allPresent(age, netIncome, saving)) {
            return null;
        }
        Long gross = input.income().annualGrossSalary();   // 세전 연봉을 넣었으면 그 값으로 판정 (v1.7)
        SimulationInput.SavingPlan sp = input.savingPlan();
        long housing = sp.housingMonthly() == null ? 0 : sp.housingMonthly();
        long pension = sp.pensionMonthly() == null ? 0 : sp.pensionMonthly();

        String reason = null;
        if (age > POLICY_MAX_AGE) {
            reason = "만 34세를 넘어 청년미래적금 대상이 아니에요. 일반 적금으로 계산해요.";
        } else if (gross != null ? gross > POLICY_MAX_GROSS_SALARY : grossSalaryOver(netIncome)) {
            reason = "총급여가 7,500만 원을 넘어 청년미래적금 대상이 아니에요. 일반 적금으로 계산해요.";
        }

        if (reason != null) {
            warnings.add(new Warning("INFO_POLICY_INELIGIBLE", reason));
            return new PolicySaving(false, reason, 0, rest(saving, 0, housing, pension));
        }
        if (gross != null ? gross > POLICY_MATCH_MAX_GROSS_SALARY : netIncome * 12 * 100 > POLICY_MATCH_MAX_GROSS_SALARY * 88) {
            warnings.add(new Warning("INFO_POLICY_NO_MATCH",
                    "총급여가 6,000만 원을 넘어 청년미래적금 정부 지원금은 없어요. 가입과 이자는 그대로예요."));
        }
        long cap = sp.policyMonthly() == null ? POLICY_MONTHLY_LIMIT : Math.min(sp.policyMonthly(), POLICY_MONTHLY_LIMIT);
        long policy = Math.min(saving, cap);
        return new PolicySaving(true, null, policy, rest(saving, policy, housing, pension));
    }

    /** 일반 적금으로 가는 돈: 월 저축액에서 청년미래 → 청약 → 연금저축 순서로 떼고 남는 돈 (엔진과 같은 규칙) */
    private static long rest(long saving, long policy, long housing, long pension) {
        long h = Math.min(housing, Math.max(saving - policy, 0));
        long p = Math.min(pension, Math.max(saving - policy - h, 0));
        return saving - policy - h - p;
    }

    /** 총급여 = 실수령액 × 12 ÷ 0.88 > 7,500만 원 (소수 오차를 피하려고 양변에 0.88을 곱해 비교) */
    private boolean grossSalaryOver(long monthlyNetIncome) {
        return monthlyNetIncome * 12 * 100 > POLICY_MAX_GROSS_SALARY * 88;
    }

    // ===== 3. 경고 (필요한 값이 있을 때만) =====

    private void warnIfExpenseOverIncome(Summary s, List<Warning> warnings) {
        if (!allPresent(s.monthlyNetIncome(), s.fixedTotal(), s.variableTotal())) return;

        long expense = s.fixedTotal() + s.variableTotal();
        if (expense >= s.monthlyNetIncome()) {
            warnings.add(new Warning("WARN_EXPENSE_OVER_INCOME",
                    "지출(" + toKorean(expense) + ")이 월 실수령액(" + toKorean(s.monthlyNetIncome())
                            + ") 이상이라 매달 모자라요."));
        }
    }

    private void warnIfMonthlyShortfall(Summary s, List<Warning> warnings) {
        if (!allPresent(s.monthlySurplus(), s.monthlySaving())) return;

        if (s.monthlySaving() > s.monthlySurplus()) {
            warnings.add(new Warning("WARN_MONTHLY_SHORTFALL",
                    "저축액이 매달 남는 돈(" + toKorean(s.monthlySurplus()) + ")보다 커서 계획만큼 넣지 못하는 달이 생겨요."));
        }
    }

    private void warnIfGoalAlreadyMet(SimulationInput input, Summary s, List<Warning> warnings) {
        Long target = input.goal() == null ? null : input.goal().targetAmount();
        if (!allPresent(target, s.totalAssets())) return;

        if (target <= s.totalAssets()) {
            warnings.add(new Warning("WARN_GOAL_ALREADY_MET",
                    "이미 모은 돈(" + toKorean(s.totalAssets()) + ")이 목표 금액(" + toKorean(target)
                            + ") 이상이에요. 목표를 더 높게 잡아 볼까요?"));
        }
    }

    private static boolean allPresent(Object... values) {
        for (Object v : values) {
            if (v == null) return false;
        }
        return true;
    }
}
