package com.example.capstoneserver.simulation;

import static com.example.capstoneserver.simulation.Rounding.decimal;
import static com.example.capstoneserver.simulation.Rounding.wholePercent;

import com.example.capstoneserver.simulation.ParamsRepository.Robust;
import com.example.capstoneserver.simulation.dto.RobustResponse;
import com.example.capstoneserver.simulation.engine.EngineParams;
import com.example.capstoneserver.simulation.engine.EngineParams.Inflation;
import com.example.capstoneserver.simulation.engine.EngineParams.Shock;
import com.example.capstoneserver.simulation.engine.EngineParams.Spend;
import com.example.capstoneserver.simulation.engine.EngineParams.Stats;
import com.example.capstoneserver.simulation.engine.EngineParams.Unemp;
import com.example.capstoneserver.simulation.engine.EngineParams.Wage;
import com.example.capstoneserver.simulation.engine.EngineResult;
import com.example.capstoneserver.simulation.engine.NoiseSource;
import com.example.capstoneserver.simulation.engine.SimulationEngine;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntConsumer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 가능성 범위 (구현 명세서 7.5). 가정을 60번 바꿔 다시 계산한다.
 * 파라미터를 뽑는 순서가 결과를 정하므로 U(), pick() 호출 순서를 바꾸면 안 된다.
 */
@Component
@RequiredArgsConstructor
class RobustAnalyzer {

    private static final String RANGE = "60번 중 95% 범위 (2.5%·97.5% 분위)";

    private final SimulationEngine engine;
    private final ParamsRepository paramsRepository;

    /** 비교 계획 하나 */
    record ComparedPlan(String name, EnginePlan plan, int mainHitDiff) {}

    /**
     * @param base     지금 계획
     * @param plans    비교 계획 (mainHitDiff = 기본 파라미터로 잰 적중 수 차이, 결론 유지 판정 기준)
     * @param progress 시도가 하나 끝날 때마다 끝난 시도 수를 받는다
     */
    RobustResponse.Result analyze(EnginePlan base, List<ComparedPlan> plans, IntConsumer progress) {
        Robust config = paramsRepository.getRobust();
        int trials = config.trials();
        int paths = config.pathsPerTrial();
        Mulberry32 rnd = new Mulberry32(config.paramSeed());

        double[] probabilities = new double[trials];
        double[][] diffs = new double[plans.size()][trials];

        for (int j = 0; j < trials; j++) {
            EngineParams params = paramsRepository.getEngineParams().withStats(drawStats(config, rnd));
            int seed = config.noiseSeedBase() + j;
            int baseHits = hitsAtGoal(base, params, seed, paths);
            probabilities[j] = (double) baseHits / paths;
            for (int k = 0; k < plans.size(); k++) {
                diffs[k][j] = (double) (hitsAtGoal(plans.get(k).plan(), params, seed, paths) - baseHits) / paths;
            }
            progress.accept(j + 1);
        }

        BigDecimal low = percentile(probabilities, .025);
        BigDecimal high = percentile(probabilities, .975);
        List<RobustResponse.Plan> planResults = new ArrayList<>();
        for (int k = 0; k < plans.size(); k++) {
            int kept = 0;
            double direction = Math.signum((double) plans.get(k).mainHitDiff());
            for (double d : diffs[k]) {
                if (Math.signum(d) == direction) kept++;
            }
            planResults.add(new RobustResponse.Plan(plans.get(k).name(), percentile(diffs[k], .5),
                    percentile(diffs[k], .025), percentile(diffs[k], .975), kept, trials));
        }

        String message = "가정을 60가지로 바꿔 다시 계산하면 대부분 " + wholePercent(low) + "%~" + wholePercent(high)
                + "% 사이예요. 정확한 한 숫자보다 이 범위를 봐 주세요.";
        return new RobustResponse.Result(trials, paths,
                new RobustResponse.Probability(percentile(probabilities, .5), low, high, RANGE), planResults, message);
    }

    /** T를 그 계획의 목표 달로 두고 계산한다 (R의 난수를 잘라 쓰지 않는다) */
    private int hitsAtGoal(EnginePlan plan, EngineParams params, int seed, int paths) {
        int gm = plan.goalMonths();
        EngineResult r = engine.simulate(plan.profile(), params, NoiseSource.sfc32(seed, paths), paths, gm, plan.bufferFirst(), true);
        int hits = 0;
        for (int p = 0; p < paths; p++) {
            if (r.at(p, gm) >= plan.goal()) hits++;
        }
        return hits;
    }

    private Stats drawStats(Robust c, Mulberry32 rnd) {
        Stats s = paramsRepository.getEngineParams().stats();

        Inflation inflation = new Inflation(
                rnd.uniform(c.uniform().get("params.inflation.mu_y")),
                rnd.uniform(c.uniform().get("params.inflation.sigma_y")),
                rnd.uniform(c.uniform().get("params.inflation.phi")),
                s.inflation().last());

        double macroMu = rnd.uniform(c.uniform().get("params.wage.macro_mu"));
        double idioSd = rnd.uniform(c.uniform().get("params.wage.idio_sd"));
        double rhoMacro = rnd.uniform(c.uniform().get("params.wage.rho_macro"));
        double[] expPrem = s.wage().expPrem().clone();
        expPrem[3] = rnd.uniform(c.uniform().get("params.wage.exp_prem[3]"));
        Wage wage = new Wage(macroMu, s.wage().macroSd(), idioSd, rhoMacro, s.wage().expEdges(), expPrem);

        double scale = rnd.uniform(c.uniform().get("params.unemp.u_by_age (배수)"));
        double[] uByAge = Arrays.stream(s.unemp().uByAge()).map(u -> u * scale).toArray();

        String structure = rnd.pick(new String[] {"long", "short"});
        int amountIndex = rnd.pickIndex(c.shockAmountMultipliers().length);
        double amount = c.shockAmountMultipliers()[amountIndex];
        String cohort = rnd.pick(c.cohortVariants());
        Double shockProb = c.shockProb().get(structure + "|" + amountKey(amount) + "|" + cohort);

        double involShare = rnd.uniform(c.uniform().get("params.unemp.invol_share"));
        double scar = rnd.uniform(c.uniform().get("params.unemp.scar_per_year"));
        double raiseShare = rnd.uniform(c.uniform().get("params.spend.raise_share"));

        Unemp unemp = new Unemp(s.unemp().ageEdges(), uByAge, c.exitByStructure().get(structure), involShare,
                s.unemp().contractRatio(), s.unemp().contractSharePop(), scar);
        double[][] base = s.shock().amounts();
        double[][] amounts = {{base[0][0] * amount, base[0][1]}, {base[1][0] * amount, base[1][1]}};
        Spend spend = new Spend(raiseShare, s.spend().unempCutUi(), s.spend().unempCutNoui());

        return new Stats(inflation, wage, unemp, s.ui(), spend, new Shock(shockProb, amounts), s.overdraft(), s.liquid());
    }

    /** 확률표 키는 0.67, 1.0, 1.5 표기를 쓴다 */
    private static String amountKey(double amount) {
        return Double.toString(amount);
    }

    /** 여기서만 분위수가 선형 보간이다. 값은 %, 소수 1자리 */
    private static BigDecimal percentile(double[] values, double x) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        double h = (sorted.length - 1) * x;
        int l = (int) Math.floor(h);
        double q = sorted[l] + (sorted[Math.min(l + 1, sorted.length - 1)] - sorted[l]) * (h - l);
        return decimal(q * 100, 1);
    }

    /** 파라미터를 뽑는 난수. 요청마다 같은 값에서 시작해 60번의 시도 동안 이어 쓴다 */
    private static final class Mulberry32 {
        private int state;

        Mulberry32(int seed) {
            this.state = seed;
        }

        double next() {
            state = state + 0x6D2B79F5;
            int t = (state ^ (state >>> 15)) * (1 | state);
            t = (t + ((t ^ (t >>> 7)) * (61 | t))) ^ t;
            return ((t ^ (t >>> 14)) & 0xFFFFFFFFL) / 4294967296.0;
        }

        double uniform(double[] range) {
            return range[0] + (range[1] - range[0]) * next();
        }

        int pickIndex(int length) {
            return (int) Math.floor(next() * length) % length;
        }

        String pick(String[] values) {
            return values[pickIndex(values.length)];
        }
    }
}
