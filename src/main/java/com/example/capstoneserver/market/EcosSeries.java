package com.example.capstoneserver.market;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 한국은행 ECOS에서 받는 금리 6개 (월 통계, 단위 연리%). 통계표·항목 코드는 ECOS StatisticItemList로 확인했다 (2026-11-27).
 * 통장·일반 적금·마이너스는 params.json에 넣은 2025년 연평균과 같은 통계다 (kernel.py 주석, 2025년 값 0.68·3.03·5.31 일치).
 */
@Getter
@RequiredArgsConstructor
public enum EcosSeries {

    /** 통장 이자 (엔진 params.liquid) */
    LIQUID("121Y013", "BEABAB22", "수시입출식 저축성예금 (잔액 기준)"),
    /** 일반 적금 금리를 비웠을 때 (엔진 general.rate, SimulationInput 기본값) */
    GENERAL_SAVING("121Y002", "BEABAA212", "정기적금 (신규취급액 기준)"),
    /** 마이너스 이자 (엔진 params.overdraft) */
    OVERDRAFT("121Y015", "BECBLB020206", "일반신용대출 (잔액 기준)"),
    /** 대출 종류별 기본 금리 (LoanType) */
    MORTGAGE("121Y006", "BECBLA0302", "주택담보대출 (신규취급액 기준)"),
    CREDIT("121Y006", "BECBLA03051", "일반신용대출 (신규취급액 기준)"),
    JEONSE("121Y006", "BECBLA03041", "전세자금대출 (신규취급액 기준)");

    private final String statCode;
    private final String itemCode;
    private final String label;
}
