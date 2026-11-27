package com.example.capstoneserver.market;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 한국은행 ECOS Open API(StatisticSearch) 클라이언트. 키는 application.properties의 app.market.ecos-api-key (신청: https://ecos.bok.or.kr/api).
 * 요청: /StatisticSearch/{키}/json/kr/{시작}/{끝}/{통계표}/M/{YYYYMM}/{YYYYMM}/{항목}
 * 응답: {"StatisticSearch":{"list_total_count":2,"row":[{"TIME":"202610","DATA_VALUE":"4.98", …}]}}
 *       자료가 없으면 {"RESULT":{"CODE":"INFO-200","MESSAGE":"해당하는 데이터가 없습니다."}}
 * 키가 주소에 들어가므로 주소를 로그·예외 메시지에 넣지 않는다.
 */
@Component
public class EcosClient implements RateSource {

    static final String BASE_URL = "https://ecos.bok.or.kr/api/StatisticSearch";
    /** 발표가 늦거나 빠진 달이 있어도 찾도록 최근 6개월을 본다 (한국은행은 보통 다음 달 말에 발표) */
    static final int LOOKBACK_MONTHS = 6;

    private static final DateTimeFormatter YYYYMM = DateTimeFormatter.ofPattern("yyyyMM");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final JsonMapper jsonMapper;
    private final String apiKey;
    private final HttpClient http = httpClient();

    public EcosClient(JsonMapper jsonMapper, @Value("${app.market.ecos-api-key:}") String apiKey) {
        this.jsonMapper = jsonMapper;
        this.apiKey = apiKey == null ? "" : apiKey.strip();
    }

    @Override
    public boolean enabled() {
        return !apiKey.isEmpty();
    }

    @Override
    public Optional<MarketRates.Rate> latest(EcosSeries series) {
        YearMonth to = YearMonth.now(SEOUL);
        String body = get(url(apiKey, series, to.minusMonths(LOOKBACK_MONTHS), to));
        return parse(jsonMapper, body).stream().max(Comparator.comparing(MarketRates.Rate::month));
    }

    /** JVM 프록시 설정(https.proxyHost 등)이 있으면 따른다 */
    private static HttpClient httpClient() {
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5));
        ProxySelector proxy = ProxySelector.getDefault();
        if (proxy != null) builder.proxy(proxy);
        return builder.build();
    }

    static String url(String key, EcosSeries series, YearMonth from, YearMonth to) {
        // 6개월 앞 ~ 이번 달이라 많아야 7줄. 10줄까지만 달라고 해서 ECOS 예시 키(sample)로도 받아지게 한다
        return BASE_URL + "/" + URLEncoder.encode(key, StandardCharsets.UTF_8) + "/json/kr/1/10/"
                + series.getStatCode() + "/M/" + from.format(YYYYMM) + "/" + to.format(YYYYMM) + "/" + series.getItemCode();
    }

    /** 응답 본문 → 달별 값 (기준 달은 YYYY-MM). 자료 없음(INFO-200)은 빈 목록, 다른 오류 코드는 예외 */
    static List<MarketRates.Rate> parse(JsonMapper jsonMapper, String body) {
        JsonNode root = jsonMapper.readTree(body);
        if (root.has("RESULT")) {
            JsonNode result = root.get("RESULT");
            String code = text(result, "CODE");
            if ("INFO-200".equals(code)) return List.of();
            throw new IllegalStateException("ECOS가 오류를 돌려줬어요: " + code + " " + text(result, "MESSAGE"));
        }
        JsonNode search = root.get("StatisticSearch");
        if (search == null || !search.has("row")) throw new IllegalStateException("ECOS 응답에 row가 없어요");

        List<MarketRates.Rate> out = new ArrayList<>();
        for (JsonNode row : search.get("row")) {
            String time = text(row, "TIME");
            String value = text(row, "DATA_VALUE");
            if (time.length() != 6 || value.isEmpty()) continue;
            out.add(new MarketRates.Rate(new BigDecimal(value), time.substring(0, 4) + "-" + time.substring(4)));
        }
        return out;
    }

    /** 없는 칸·null은 빈 문자열 */
    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? "" : v.asString().strip();
    }

    private String get(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new IllegalStateException("ECOS가 HTTP " + response.statusCode() + "을 돌려줬어요");
            }
            return response.body();
        } catch (IOException e) {
            throw new IllegalStateException("ECOS에 연결하지 못했어요 (" + e.getClass().getSimpleName() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ECOS 요청이 중단됐어요");
        }
    }
}
