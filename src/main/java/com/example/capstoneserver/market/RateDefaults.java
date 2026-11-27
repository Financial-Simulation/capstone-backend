package com.example.capstoneserver.market;

import com.example.capstoneserver.input.dto.LoanType;
import com.example.capstoneserver.simulation.dto.MetaResponse;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;

/**
 * 사용자가 비운 칸에 서버가 넣는 금리 (연 %). 처음에는 코드 기본값(LoanType, 일반 적금 3.03%)이고,
 * MarketRateService가 한국은행 최신 값을 받으면 그 값으로 바뀐다. 요청마다 읽으므로 받은 뒤 들어온 요청부터 적용된다.
 * 입력 record(SimulationInput, RepaymentPreviewRequest)는 Spring bean이 아니어서 일부러 정적 값으로 둔다.
 */
public final class RateDefaults {

    /** 일반 적금 금리 코드 기본값: 2025년 은행 정기적금 평균 (params.json general.rate와 같은 값) */
    public static final BigDecimal BUILT_IN_GENERAL_SAVING = new BigDecimal("3.03");

    private static volatile MarketRates current = MarketRates.empty();

    private RateDefaults() {
    }

    /** 일반 적금 금리를 비웠을 때 */
    public static BigDecimal generalSaving() {
        return generalSaving(current);
    }

    /** 대출 금리를 비웠을 때. 학자금·기타 대출은 한국은행 통계가 없어 늘 LoanType 값 */
    public static BigDecimal loan(LoanType type) {
        return loan(current, type);
    }

    /** GET /api/meta의 defaultRates: 지금 기본값과, 한국은행 값이면 그 기준 달·출처 */
    public static MetaResponse.DefaultRates forMeta() {
        MarketRates m = current;
        Map<LoanType, BigDecimal> loans = new EnumMap<>(LoanType.class);
        for (LoanType type : LoanType.values()) loans.put(type, loan(m, type));
        String asOf = MarketRates.latestMonth(m.generalSaving(), m.mortgage(), m.credit(), m.jeonse());
        String source = asOf == null
                ? "코드 기본값 (일반 적금은 2025년 은행 정기적금 평균, 대출은 명세서 '대출 종류별 기본값' 표)"
                : "한국은행 ECOS 예금은행 가중평균금리 (신규취급액 기준). 학자금·기타 대출은 코드 기본값";
        return new MetaResponse.DefaultRates(generalSaving(m), loans, asOf, source);
    }

    static void apply(MarketRates rates) {
        current = rates == null ? MarketRates.empty() : rates;
    }

    /** 테스트에서 바꾼 값을 되돌린다 */
    static void reset() {
        current = MarketRates.empty();
    }

    private static BigDecimal generalSaving(MarketRates m) {
        return m.generalSaving() != null ? m.generalSaving().value() : BUILT_IN_GENERAL_SAVING;
    }

    private static BigDecimal loan(MarketRates m, LoanType type) {
        MarketRates.Rate r = switch (type) {
            case MORTGAGE -> m.mortgage();
            case CREDIT -> m.credit();
            case JEONSE -> m.jeonse();
            case STUDENT, OTHER -> null;
        };
        return r != null ? r.value() : type.getDefaultRate();
    }
}
