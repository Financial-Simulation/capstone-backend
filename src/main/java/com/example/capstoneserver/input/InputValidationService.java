package com.example.capstoneserver.input;

import static com.example.capstoneserver.global.util.Money.toKorean;

import com.example.capstoneserver.global.exception.FieldValidationException;
import com.example.capstoneserver.global.exception.GeneralException;
import com.example.capstoneserver.global.response.ErrorStatus;
import com.example.capstoneserver.global.response.Warning;
import com.example.capstoneserver.input.dto.InputStep;
import com.example.capstoneserver.input.dto.SimulationInput;
import com.example.capstoneserver.input.dto.SimulationInput.Event;
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
 * step까지의 칸을 누적으로 검사한다 (예: ASSETS면 BASIC 칸도 함께 본다).
 */
@Service
@RequiredArgsConstructor
public class InputValidationService {

    // 청년미래적금
    private static final int POLICY_MAX_AGE = 34;
    private static final long POLICY_MAX_GROSS_SALARY = 75_000_000;    // 총급여 7,500만 원
    private static final long POLICY_MONTHLY_LIMIT = 500_000;          // 월 50만 원까지

    private final Validator validator;
    private final LoanCalculator loanCalculator;

    public ValidateResponse validate(InputStep step, SimulationInput input) {
        checkFields(step, input);       // 1. 칸별 규칙 (나이 19~39 등) → COMMON400
        checkCrossRules(step, input);   // 2. 여러 칸을 같이 보는 규칙 → INPUT4001 등

        // 3. 확정 합계·경고
        List<Warning> warnings = new ArrayList<>();
        Summary summary = summarize(step, input);
        PolicySaving policySaving = null;

        if (reached(step, InputStep.BASIC)) {
            warnIfExpenseOverIncome(summary, warnings);
        }
        if (reached(step, InputStep.ASSETS)) {
            warnIfMonthlyShortfall(summary, warnings);
            policySaving = policySaving(input, warnings);
        }
        if (reached(step, InputStep.GOAL)) {
            warnIfGoalAlreadyMet(input, summary, warnings);
        }

        return new ValidateResponse(step, summary, policySaving, warnings);
    }

    // ===== 1. 칸별 규칙 =====

    private void checkFields(InputStep step, SimulationInput input) {
        Map<String, String> errors = new TreeMap<>();
        for (ConstraintViolation<SimulationInput> v : validator.validate(input, step.groupsUpToHere())) {
            errors.putIfAbsent(v.getPropertyPath().toString(), v.getMessage());
        }
        if (!errors.isEmpty()) {
            throw new FieldValidationException(errors);
        }
    }

    // ===== 2. 여러 칸을 같이 보는 규칙 =====

    private void checkCrossRules(InputStep step, SimulationInput input) {
        if (reached(step, InputStep.BASIC)) {
            var person = input.person();
            if (person.careerYears() > person.age() - 15) {
                throw new GeneralException(ErrorStatus.CAREER_EXCEEDS_AGE);
            }
        }

        if (reached(step, InputStep.ASSETS)) {
            var loans = input.loans();
            if (loans.hasNoLoan() != loans.items().isEmpty()) {
                throw new GeneralException(ErrorStatus.LOAN_ITEMS_MISMATCH);
            }
        }

        YearMonth start = YearMonth.parse(input.startMonth());

        if (reached(step, InputStep.GOAL)) {
            long months = ChronoUnit.MONTHS.between(start, YearMonth.parse(input.goal().targetDate()));
            if (months < 12) throw new GeneralException(ErrorStatus.GOAL_TOO_SOON);
            if (months > 120) throw new GeneralException(ErrorStatus.GOAL_TOO_LATE);
        }

        if (reached(step, InputStep.EVENTS)) {
            for (Event event : input.events()) {
                checkEvent(start, event);
            }
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

        long months = ChronoUnit.MONTHS.between(start, YearMonth.parse(e.occurDate()));
        if (months < 0 || months > 119) {
            throw new GeneralException(ErrorStatus.EVENT_DATE_OUT_OF_RANGE);
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

    // ===== 3. 확정 합계 =====

    private Summary summarize(InputStep step, SimulationInput input) {
        long netIncome = input.income().monthlyNetIncome();
        long fixed = input.expenses().fixed().resolvedTotal();
        long variable = input.expenses().variable().resolvedTotal();

        if (!reached(step, InputStep.ASSETS)) {
            return new Summary(netIncome, fixed, variable, null, null, null, null, null);
        }

        long loanPayment = 0;
        long loanBalance = 0;
        for (LoanItem loan : input.loans().items()) {
            loanPayment += loanCalculator.calculate(
                    loan.principal(), loan.resolvedRate(), loan.remainingMonths(), loan.resolvedRepaymentType()
            ).firstMonthPayment();
            loanBalance += loan.principal();
        }

        var assets = input.assets();
        return new Summary(
                netIncome,
                fixed,
                variable,
                loanPayment,
                netIncome - fixed - variable - loanPayment,
                input.savingPlan().monthlySaving(),
                assets.cash() + assets.deposits() + assets.investments(),
                loanBalance
        );
    }

    private PolicySaving policySaving(SimulationInput input, List<Warning> warnings) {
        long saving = input.savingPlan().monthlySaving();
        String reason = null;

        if (input.person().age() > POLICY_MAX_AGE) {
            reason = "만 34세를 넘어 청년미래적금 대상이 아니에요. 일반 적금으로 계산해요.";
        } else if (grossSalaryOver(input.income().monthlyNetIncome())) {
            reason = "총급여가 7,500만 원을 넘어 청년미래적금 대상이 아니에요. 일반 적금으로 계산해요.";
        }

        if (reason != null) {
            warnings.add(new Warning("INFO_POLICY_INELIGIBLE", reason));
            return new PolicySaving(false, reason, 0, saving);
        }
        long policy = Math.min(saving, POLICY_MONTHLY_LIMIT);
        return new PolicySaving(true, null, policy, saving - policy);
    }

    /** 총급여 = 실수령액 × 12 ÷ 0.88 > 7,500만 원 (소수 오차를 피하려고 양변에 0.88을 곱해 비교) */
    private boolean grossSalaryOver(long monthlyNetIncome) {
        return monthlyNetIncome * 12 * 100 > POLICY_MAX_GROSS_SALARY * 88;
    }

    // ===== 3. 경고 =====

    private void warnIfExpenseOverIncome(Summary s, List<Warning> warnings) {
        long expense = s.fixedTotal() + s.variableTotal();
        if (expense >= s.monthlyNetIncome()) {
            warnings.add(new Warning("WARN_EXPENSE_OVER_INCOME",
                    "지출(" + toKorean(expense) + ")이 월 실수령액(" + toKorean(s.monthlyNetIncome())
                            + ") 이상이라 매달 모자라요."));
        }
    }

    private void warnIfMonthlyShortfall(Summary s, List<Warning> warnings) {
        if (s.monthlySaving() > s.monthlySurplus()) {
            warnings.add(new Warning("WARN_MONTHLY_SHORTFALL",
                    "저축액이 매달 남는 돈(" + toKorean(s.monthlySurplus()) + ")보다 커서 계획만큼 넣지 못하는 달이 생겨요."));
        }
    }

    private void warnIfGoalAlreadyMet(SimulationInput input, Summary s, List<Warning> warnings) {
        long target = input.goal().targetAmount();
        if (target <= s.totalAssets()) {
            warnings.add(new Warning("WARN_GOAL_ALREADY_MET",
                    "이미 모은 돈(" + toKorean(s.totalAssets()) + ")이 목표 금액(" + toKorean(target)
                            + ") 이상이에요. 목표를 더 높게 잡아 볼까요?"));
        }
    }

    /** 지금 단계가 target 단계까지 왔는지 */
    private boolean reached(InputStep step, InputStep target) {
        return step.ordinal() >= target.ordinal();
    }
}
