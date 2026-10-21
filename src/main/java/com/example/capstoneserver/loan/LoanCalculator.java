package com.example.capstoneserver.loan;

import com.example.capstoneserver.global.util.Money;
import com.example.capstoneserver.input.dto.RepaymentType;
import com.example.capstoneserver.loan.dto.RepaymentPreviewResponse;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * 대출 상환 계산기. 화면 미리보기(/loans/repayment-preview)와 입력 검사·시뮬레이션이 함께 쓴다.
 * 그래야 화면에 보이는 상환액과 시뮬레이션 값이 어긋나지 않는다.
 *
 * 계산은 소수점 그대로(double) 하고, 결과를 내보낼 때만 원 단위로 반올림한다.
 */
@Component
public class LoanCalculator {

    /**
     * @param principal       남은 원금 (원)
     * @param annualRatePct   연 이자율 (%, 3.5% → 3.5)
     * @param months          남은 기간 (개월)
     * @param type            상환 방식
     */
    public RepaymentPreviewResponse calculate(long principal, BigDecimal annualRatePct, int months, RepaymentType type) {
        double monthlyRate = annualRatePct.doubleValue() / 100 / 12;
        double balance = principal;
        double equalPayment = equalPayment(principal, monthlyRate, months);

        double firstPayment = 0, firstPrincipal = 0, firstInterest = 0;
        double lastPayment = 0;
        double totalInterest = 0;

        for (int month = 1; month <= months; month++) {
            double interest = balance * monthlyRate;
            double principalPaid = principalPaid(type, month, months, principal, balance, equalPayment, interest);
            double payment = principalPaid + interest;

            balance -= principalPaid;
            totalInterest += interest;

            if (month == 1) {
                firstPayment = payment;
                firstPrincipal = principalPaid;
                firstInterest = interest;
            }
            if (month == months) {
                lastPayment = payment;
            }
        }

        return new RepaymentPreviewResponse(
                Money.roundWon(firstPayment),
                Money.roundWon(firstPrincipal),
                Money.roundWon(firstInterest),
                Money.roundWon(lastPayment),
                Money.roundWon(totalInterest)
        );
    }

    /** 이번 달에 갚는 원금 */
    private double principalPaid(RepaymentType type, int month, int months,
                                 long principal, double balance, double equalPayment, double interest) {
        return switch (type) {
            // 원리금균등: 매달 같은 금액을 내고, 그중 이자를 뺀 만큼이 원금
            case EQUAL_PAYMENT -> equalPayment - interest;
            // 원금균등: 원금을 n등분해서 매달 같은 원금
            case EQUAL_PRINCIPAL -> (double) principal / months;
            // 만기일시: 마지막 달에 원금 전부
            case BULLET -> month == months ? balance : 0;
            // 이자만: 원금은 현금흐름 밖 (전세 보증금 반환으로 상환, 보고서 6.3)
            case INTEREST_ONLY -> 0;
        };
    }

    /** 원리금균등 월 납입액 */
    private double equalPayment(long principal, double monthlyRate, int months) {
        if (monthlyRate == 0) {
            return (double) principal / months;
        }
        return principal * monthlyRate / (1 - Math.pow(1 + monthlyRate, -months));
    }
}
