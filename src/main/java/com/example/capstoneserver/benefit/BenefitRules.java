package com.example.capstoneserver.benefit;

import com.example.capstoneserver.benefit.dto.BenefitsResponse.Category;
import com.example.capstoneserver.benefit.dto.BenefitsResponse.Source;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * resources/benefits.json (혜택 규칙표: 기준 금액 · 문장 · 출처 · 기준일)을 서버가 켜질 때 한 번 읽는다.
 * 정책이 바뀌면 이 파일만 고치고 asOf를 바꾼다. 판정 순서는 BenefitService.
 */
@Getter
@Component
public class BenefitRules {

    private final String asOf;
    private final long medianOnePerson;
    private final double netToGrossRatio;
    private final Map<String, Policy> policies;

    public BenefitRules(JsonMapper jsonMapper) {
        try (InputStream in = new ClassPathResource("benefits.json").getInputStream()) {
            RulesFile file = jsonMapper.readValue(in, RulesFile.class);
            this.asOf = file.asOf();
            this.medianOnePerson = file.medianIncome2026().onePerson();
            this.netToGrossRatio = file.netToGrossRatio();
            this.policies = file.policies();
        } catch (IOException e) {
            throw new UncheckedIOException("benefits.json을 읽지 못했어요", e);
        }
    }

    public Policy policy(String key) {
        Policy p = policies.get(key);
        if (p == null) throw new IllegalStateException("benefits.json에 " + key + "가 없어요");
        return p;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RulesFile(String asOf, Median medianIncome2026, double netToGrossRatio, Map<String, Policy> policies) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Median(long onePerson) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Policy(String name, Category category, String how, String notice, Map<String, Double> params,
                         String assumedStart, String hireDeadline, Map<String, String> text, Map<String, String> brief,
                         List<Source> sources) {

        public double num(String key) {
            Double v = params == null ? null : params.get(key);
            if (v == null) throw new IllegalStateException(name + " params에 " + key + "가 없어요");
            return v;
        }

        /** 카드에 바로 보이는 짧은 한 줄 (text는 '자세히'에) */
        public String brief(String key) {
            String v = brief == null ? null : brief.get(key);
            if (v == null) throw new IllegalStateException(name + " brief에 " + key + "가 없어요");
            return v;
        }

        public String text(String key) {
            String v = text == null ? null : text.get(key);
            if (v == null) throw new IllegalStateException(name + " text에 " + key + "가 없어요");
            return v;
        }
    }
}
