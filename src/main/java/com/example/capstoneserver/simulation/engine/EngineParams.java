package com.example.capstoneserver.simulation.engine;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 엔진이 쓰는 값 3묶음 (구현 명세서 3장). params.json의 키 이름을 그대로 읽는다.
 * 금액은 만 원, 비율은 연 소수. 불변이라 경로끼리 나눠 써도 안전하다.
 */
public record EngineParams(Stats stats, Policy policy, General general) {

    public EngineParams withStats(Stats newStats) {
        return new EngineParams(newStats, policy, general);
    }

    /** 첫 계좌 계산(F36·F48)은 재가입을 끈다 */
    public EngineParams withoutRejoin() {
        Policy p = policy;
        return new EngineParams(stats, new Policy(p.cap(), p.rate(), p.term(), p.match(), p.matchCap(), p.window(),
                p.tax(), p.ageMax(), p.grossMaxY(), p.rejoinWait(), false, p.earlyTable(), p.earlyFloor(),
                p.earlyUnder3()), general);
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

    /** amounts: [금액, 비율] 목록 */
    public record Shock(@JsonProperty("prob_m") double probM, double[][] amounts) {}

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
            @JsonProperty("rejoin_wait") int rejoinWait,
            boolean rejoin,
            @JsonProperty("early_table") double[][] earlyTable,
            @JsonProperty("early_floor") double earlyFloor,
            @JsonProperty("early_under3") double earlyUnder3) {}

    public record General(
            double rate,
            @JsonProperty("early_ratio") double earlyRatio,
            double tax) {}
}
