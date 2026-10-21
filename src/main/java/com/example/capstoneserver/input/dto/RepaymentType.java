package com.example.capstoneserver.input.dto;

/**
 * 대출 상환 방식.
 */
public enum RepaymentType {
    EQUAL_PAYMENT,      // 원리금균등
    EQUAL_PRINCIPAL,    // 원금균등
    BULLET,             // 만기일시
    INTEREST_ONLY       // 이자만 (전세자금)
}
