package com.example.capstoneserver.loan.dto;

import com.example.capstoneserver.input.dto.LoanType;
import com.example.capstoneserver.input.dto.RepaymentType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * POST /api/loans/repayment-preview 요청.
 * 규칙은 SimulationInput.LoanItem 한 건과 같다 (name 제외).
 */
public record RepaymentPreviewRequest(
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
