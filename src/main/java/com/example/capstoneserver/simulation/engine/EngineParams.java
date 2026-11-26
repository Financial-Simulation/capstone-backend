package com.example.capstoneserver.simulation.engine;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 엔진이 쓰는 값 (구현 명세서 3장, 세금 모듈 명세 6장). params.json의 키 이름을 그대로 읽는다.
 * 금액은 만 원, 비율은 연 소수 (4대보험 상·하한만 원). 불변이라 경로끼리 나눠 써도 안전하다.
 */
public record EngineParams(Stats stats, Policy policy, General general, Housing housing, Pension pension, Insurance insurance) {

    public EngineParams withStats(Stats newStats) {
        return new EngineParams(newStats, policy, general, housing, pension, insurance);
    }

    /** 첫 계좌 계산(F36·F48)은 재가입을 끈다 */
    public EngineParams withoutRejoin() {
        Policy p = policy;
        return new EngineParams(stats, new Policy(p.cap(), p.rate(), p.term(), p.match(), p.matchCap(), p.window(),
                p.tax(), p.ageMax(), p.grossMaxY(), p.matchGrossMaxY(), p.rejoinWait(), false, p.earlyTable(), p.earlyFloor(),
                p.earlyUnder3()), general, housing, pension, insurance);
    }

    public record Stats(Inflation inflation, Wage wage, Unemp unemp, Ui ui, Spend spend, Shock shock,
                        Rate overdraft, Rate liquid) {}

    public record Inflation(
            @JsonProperty("mu_y") double muY,
            @JsonProperty("sigma_y") double sigmaY,
            double phi,
            double last) {}

    public record Wage(
            @JsonProperty("macro_mu") double macroMu,
            @JsonProperty("macro_sd") double macroSd,
            @JsonProperty("idio_sd") double idioSd,
            @JsonProperty("rho_macro") double rhoMacro,
            @JsonProperty("exp_edges") double[] expEdges,
            @JsonProperty("exp_prem") double[] expPrem) {}

    public record Unemp(
            @JsonProperty("age_edges") double[] ageEdges,
            @JsonProperty("u_by_age") double[] uByAge,
            @JsonProperty("exit_m") double exitM,
            @JsonProperty("invol_share") double involShare,
            @JsonProperty("contract_ratio") double contractRatio,
            @JsonProperty("contract_share_pop") double contractSharePop,
            @JsonProperty("scar_per_year") double scarPerYear) {}

    public record Ui(
            double floor,
            double cap,
            double replace,
            @JsonProperty("waiting_frac") double waitingFrac,
            @JsonProperty("gross_over_net") double grossOverNet,
            @JsonProperty("days_edges") double[] daysEdges,
            double[] days,
            @JsonProperty("min_insured_m") int minInsuredM,
            @JsonProperty("window_m") int windowM) {}

    public record Spend(
            @JsonProperty("raise_share") double raiseShare,
            @JsonProperty("unemp_cut_ui") double unempCutUi,
            @JsonProperty("unemp_cut_noui") double unempCutNoui) {}

    /**
     * 급한 지출. categories가 없던 기존 설정은 amounts의 이산 분포를 사용한다.
     * category별 확률은 월 확률이고 min·max는 만 원 단위이다.
     */
    public record Shock(@JsonProperty("prob_m") double probM, double[][] amounts, List<Category> categories) {

        public Shock(double probM, double[][] amounts) {
            this(probM, amounts, List.of());
        }

        public Shock scaled(double probability, double amountScale) {
            double[][] scaledAmounts = new double[amounts.length][2];
            for (int i = 0; i < amounts.length; i++) {
                scaledAmounts[i] = new double[] {amounts[i][0] * amountScale, amounts[i][1]};
            }
            if (categories == null || categories.isEmpty()) {
                return new Shock(probability, scaledAmounts);
            }
            double total = categories.stream().mapToDouble(Category::probM).sum();
            double scale = total > 0 ? probability / total : 0;
            List<Category> scaledCategories = categories.stream()
                    .map(c -> new Category(c.type(), c.label(), c.probM() * scale,
                            c.min() * amountScale, c.max() * amountScale))
                    .toList();
            return new Shock(probability, scaledAmounts, scaledCategories);
        }

        public record Category(String type, String label, @JsonProperty("prob_m") double probM,
                               double min, double max) {}
    }

    public record Rate(@JsonProperty("annual_rate") double annualRate) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Policy(
            double cap,
            double rate,
            int term,
            double match,
            @JsonProperty("match_cap") double matchCap,
            int window,
            double tax,
            @JsonProperty("age_max") int ageMax,
            @JsonProperty("gross_max_y") double grossMaxY,
            @JsonProperty("match_gross_max_y") double matchGrossMaxY,   // 정부 지원금은 총급여 6,000만 이하만 (가입은 7,500만까지)
            @JsonProperty("rejoin_wait") int rejoinWait,
            boolean rejoin,
            @JsonProperty("early_table") double[][] earlyTable,
            @JsonProperty("early_floor") double earlyFloor,
            @JsonProperty("early_under3") double earlyUnder3) {}

    public record General(
            double rate,
            @JsonProperty("early_ratio") double earlyRatio,
            double tax) {}

    /**
     * 주택청약 (하나·농협 상품설명서 2026.3, 신한 청년 주택드림 2025.12). 입금건별 단리이고,
     * 해지 시점 가입기간에 해당하는 이율을 모든 입금에 적용한다. 5년 안에 깨면 소득공제 추징.
     */
    public record Housing(
            double[] edges,
            double[] rates,
            @JsonProperty("dream_rates") double[] dreamRates,
            @JsonProperty("dream_age") double dreamAge,
            @JsonProperty("dream_gross_y") double dreamGrossY,
            @JsonProperty("dream_principal_cap") double dreamPrincipalCap,
            @JsonProperty("taxfree_gross_y") double taxfreeGrossY,
            @JsonProperty("taxfree_months") int taxfreeMonths,
            @JsonProperty("taxfree_interest") double taxfreeInterest,
            double tax,
            @JsonProperty("claw_rate") double clawRate,
            @JsonProperty("claw_cap_y") double clawCapY,
            @JsonProperty("claw_months") int clawMonths) {}

    /** 연금저축: 세액공제 납입 한도(연), 55세 전 인출 시 기타소득세 */
    public record Pension(
            @JsonProperty("credit_cap_y") double creditCapY,
            @JsonProperty("early_tax") double earlyTax) {}

    /** 4대보험 근로자 부담 요율 (2026년). npsCap·npsFloor는 국민연금 기준소득월액 상·하한 (원) */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Insurance(double nps, double health, double ltc, double employ, double npsCap, double npsFloor) {}
}
