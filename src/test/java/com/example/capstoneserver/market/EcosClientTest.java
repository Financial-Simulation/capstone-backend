package com.example.capstoneserver.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class EcosClientTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    /** ECOS StatisticSearch 실제 응답 모양 (121Y006 주택담보대출, 2026-11-27에 받은 값) */
    private static final String ROWS = """
            {"StatisticSearch":{"list_total_count":2,"row":[
            {"STAT_CODE":"121Y006","STAT_NAME":"1.3.3.2.1. 예금은행 대출금리(신규취급액 기준)","ITEM_CODE1":"BECBLA0302",
             "ITEM_NAME1":"주택담보대출","ITEM_CODE2":null,"ITEM_NAME2":null,"ITEM_CODE3":null,"ITEM_NAME3":null,
             "ITEM_CODE4":null,"ITEM_NAME4":null,"UNIT_NAME":"연리% ","WGT":null,"TIME":"202609","DATA_VALUE":"4.88"},
            {"STAT_CODE":"121Y006","STAT_NAME":"1.3.3.2.1. 예금은행 대출금리(신규취급액 기준)","ITEM_CODE1":"BECBLA0302",
             "ITEM_NAME1":"주택담보대출","ITEM_CODE2":null,"ITEM_NAME2":null,"ITEM_CODE3":null,"ITEM_NAME3":null,
             "ITEM_CODE4":null,"ITEM_NAME4":null,"UNIT_NAME":"연리% ","WGT":null,"TIME":"202610","DATA_VALUE":"4.98"}]}}
            """;

    @Test
    void 응답에서_달별_값을_읽는다() {
        List<MarketRates.Rate> rates = EcosClient.parse(mapper, ROWS);

        assertThat(rates).containsExactly(
                new MarketRates.Rate(new BigDecimal("4.88"), "2026-09"),
                new MarketRates.Rate(new BigDecimal("4.98"), "2026-10"));
    }

    @Test
    void 자료가_없으면_빈_목록() {
        String none = "{\"RESULT\":{\"CODE\":\"INFO-200\",\"MESSAGE\":\"해당하는 데이터가 없습니다.\"}}";

        assertThat(EcosClient.parse(mapper, none)).isEmpty();
    }

    @Test
    void 다른_오류_코드는_예외() {
        String badKey = "{\"RESULT\":{\"CODE\":\"INFO-100\",\"MESSAGE\":\"인증키가 유효하지 않습니다.\"}}";

        assertThatThrownBy(() -> EcosClient.parse(mapper, badKey))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INFO-100");
    }

    @Test
    void 값이_빈_줄은_건너뛴다() {
        String body = """
                {"StatisticSearch":{"list_total_count":2,"row":[
                {"TIME":"202609","DATA_VALUE":""},{"TIME":"202610","DATA_VALUE":null},{"TIME":"202611","DATA_VALUE":"3.1"}]}}
                """;

        assertThat(EcosClient.parse(mapper, body)).containsExactly(new MarketRates.Rate(new BigDecimal("3.1"), "2026-11"));
    }

    @Test
    void 요청_주소는_통계표_주기_기간_항목_순서() {
        String url = EcosClient.url("TESTKEY", EcosSeries.MORTGAGE, YearMonth.of(2026, 5), YearMonth.of(2026, 11));

        assertThat(url).isEqualTo(
                "https://ecos.bok.or.kr/api/StatisticSearch/TESTKEY/json/kr/1/10/121Y006/M/202605/202611/BECBLA0302");
    }

    @Test
    void 키가_없으면_꺼져_있다() {
        assertThat(new EcosClient(mapper, "").enabled()).isFalse();
        assertThat(new EcosClient(mapper, "   ").enabled()).isFalse();
        assertThat(new EcosClient(mapper, null).enabled()).isFalse();
        assertThat(new EcosClient(mapper, "TESTKEY").enabled()).isTrue();
    }
}
