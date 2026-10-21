package com.example.capstoneserver.input.dto;

import java.math.BigDecimal;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 대출 종류. 금리·상환 방식을 비워 두면 여기 기본값을 쓴다 (명세서 '대출 종류별 기본값' 표).
 */
@Getter
@RequiredArgsConstructor
public enum LoanType {

    STUDENT(new BigDecimal("1.7"), RepaymentType.EQUAL_PAYMENT),     // 학자금
    JEONSE(new BigDecimal("4.3"), RepaymentType.INTEREST_ONLY),      // 전세자금
    CREDIT(new BigDecimal("6.3"), RepaymentType.EQUAL_PAYMENT),      // 신용
    MORTGAGE(new BigDecimal("4.7"), RepaymentType.EQUAL_PAYMENT),    // 주택담보
    OTHER(new BigDecimal("5.0"), RepaymentType.EQUAL_PAYMENT);       // 기타

    private final BigDecimal defaultRate;
    private final RepaymentType defaultRepaymentType;
}
