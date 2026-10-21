package com.example.capstoneserver.loan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.capstoneserver.input.dto.RepaymentType;
import com.example.capstoneserver.loan.dto.RepaymentPreviewResponse;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class LoanCalculatorTest {

    private final LoanCalculator calculator = new LoanCalculator();

    @Test
    void 원리금균등_명세서_예시와_같다() {
        // 학자금 1,200만 원, 연 1.9%, 96개월
        RepaymentPreviewResponse result = calculator.calculate(
                12_000_000, new BigDecimal("1.9"), 96, RepaymentType.EQUAL_PAYMENT);

        assertThat(result).isEqualTo(new RepaymentPreviewResponse(134_839, 115_839, 19_000, 134_839, 944_574));
    }

    @Test
    void 이자만_명세서_예시와_같다() {
        // 전세 5,000만 원, 연 4.3%
        RepaymentPreviewResponse result = calculator.calculate(
                50_000_000, new BigDecimal("4.3"), 24, RepaymentType.INTEREST_ONLY);

        assertThat(result.firstMonthPayment()).isEqualTo(179_167);
        assertThat(result.firstMonthPrincipal()).isZero();
        assertThat(result.lastMonthPayment()).isEqualTo(179_167);
    }

    @Test
    void 원금균등은_원금이_매달_같고_이자가_줄어든다() {
        // 1,200만 원, 연 12%(월 1%), 12개월 → 원금 매달 100만 원
        RepaymentPreviewResponse result = calculator.calculate(
                12_000_000, new BigDecimal("12"), 12, RepaymentType.EQUAL_PRINCIPAL);

        assertThat(result.firstMonthPrincipal()).isEqualTo(1_000_000);
        assertThat(result.firstMonthInterest()).isEqualTo(120_000);
        assertThat(result.lastMonthPayment()).isEqualTo(1_010_000);
    }

    @Test
    void 만기일시는_마지막_달에_원금을_갚는다() {
        RepaymentPreviewResponse result = calculator.calculate(
                12_000_000, new BigDecimal("12"), 12, RepaymentType.BULLET);

        assertThat(result.firstMonthPayment()).isEqualTo(120_000);
        assertThat(result.lastMonthPayment()).isEqualTo(12_120_000);
    }

    @Test
    void 금리가_0이면_원금을_나눠_갚는다() {
        RepaymentPreviewResponse result = calculator.calculate(
                1_200_000, BigDecimal.ZERO, 12, RepaymentType.EQUAL_PAYMENT);

        assertThat(result.firstMonthPayment()).isEqualTo(100_000);
        assertThat(result.totalInterest()).isZero();
    }
}
