package com.example.capstoneserver.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 정해진 때 도는 작업(@Scheduled)을 켠다. 지금은 금리 자동 갱신(market/MarketRateService)뿐이다.
 * ECOS 키가 없으면 일정이 돌아도 아무것도 하지 않는다.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
