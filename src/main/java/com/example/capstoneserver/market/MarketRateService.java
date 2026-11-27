package com.example.capstoneserver.market;

import com.example.capstoneserver.simulation.ParamsRepository;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 금리 자동 갱신: 한국은행 ECOS에서 통장·일반 적금·마이너스·대출 기본 금리를 받아
 * 엔진 값(ParamsRepository)과 비운 칸 기본값(RateDefaults)에 덮어쓴다. params.json 파일은 건드리지 않는다.
 * - ECOS 키(app.market.ecos-api-key)가 없으면 아무것도 하지 않는다 → params.json·코드 기본값 그대로 (테스트·CI도 이 상태)
 * - 서버가 켜지고 3초 뒤 한 번, 그 뒤 매일 app.market.refresh-cron에 받는다
 * - 항목 하나가 실패하면 그 항목만 마지막 값을 그대로 둔다. 받은 값은 메모리에만 두므로 서버를 다시 켜면 처음 받을 때까지 params.json 값이다
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketRateService {

    private final RateSource source;
    private final ParamsRepository paramsRepository;

    private MarketRates current = MarketRates.empty();

    @Scheduled(initialDelay = 3, timeUnit = TimeUnit.SECONDS)
    public void refreshOnStartup() {
        if (!source.enabled()) {
            log.info("금리 자동 갱신 꺼짐: ECOS 키가 없어 params.json·코드 기본값을 씀");
            return;
        }
        refresh();
    }

    @Scheduled(cron = "${app.market.refresh-cron:0 0 7 * * *}", zone = "Asia/Seoul")
    public void refreshDaily() {
        refresh();
    }

    /** 받아서 덮어쓴다. 키가 없으면 아무것도 하지 않는다 */
    public synchronized MarketRates refresh() {
        if (!source.enabled()) return current;

        MarketRates next = current;
        int received = 0;
        for (EcosSeries series : EcosSeries.values()) {
            try {
                Optional<MarketRates.Rate> rate = source.latest(series);
                if (rate.isPresent()) {
                    next = next.with(series, rate.get());
                    received++;
                } else {
                    log.warn("금리 갱신: {} 최근 6개월 값이 없어 마지막 값을 씀", series.getLabel());
                }
            } catch (RuntimeException e) {
                log.warn("금리 갱신 실패: {} ({}). 마지막 값을 씀", series.getLabel(), e.getMessage());
            }
        }
        apply(next);
        log.info("금리 갱신: {}/{}개 받음. 통장 {}·적금 {}·마이너스 {}·주담대 {}·신용 {}·전세 {}",
                received, EcosSeries.values().length, show(next.liquid()), show(next.generalSaving()),
                show(next.overdraft()), show(next.mortgage()), show(next.credit()), show(next.jeonse()));
        return next;
    }

    /** 지금 쓰는 값 */
    public synchronized MarketRates current() {
        return current;
    }

    private void apply(MarketRates rates) {
        current = rates;
        paramsRepository.applyMarketRates(rates);
        RateDefaults.apply(rates);
    }

    private static String show(MarketRates.Rate r) {
        return r == null ? "-" : r.value().toPlainString() + "%(" + r.month() + ")";
    }
}
