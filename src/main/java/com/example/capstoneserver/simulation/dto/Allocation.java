package com.example.capstoneserver.simulation.dto;

/**
 * 월 저축액의 실제 배분 (원). 청년미래 → 청약 → 연금저축 순서로 떼고 나머지가 일반 적금이다.
 * 요청한 배분의 합이 월 저축액보다 크면 뒤 상품을 줄인 값이 들어간다.
 */
public record Allocation(long policyMonthly, long housingMonthly, long pensionMonthly, long generalMonthly) {}
