package com.example.capstoneserver.market;

import java.util.Optional;

/** 금리 항목 하나의 가장 최근 값. 실제로는 EcosClient, 테스트에서는 가짜를 넣는다 */
public interface RateSource {

    /** 받아 올 수 있는지 (ECOS 키가 있는지) */
    boolean enabled();

    /** 가장 최근 달의 값. 최근 몇 달 안에 발표된 값이 없으면 빈 값, 요청이 실패하면 예외 */
    Optional<MarketRates.Rate> latest(EcosSeries series);
}
