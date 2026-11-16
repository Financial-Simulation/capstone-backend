package com.example.capstoneserver.simulation;

import static com.example.capstoneserver.simulation.Rounding.decimal;
import static com.example.capstoneserver.simulation.Rounding.won;

import com.example.capstoneserver.simulation.dto.StoryResponse;
import com.example.capstoneserver.simulation.engine.EngineParams;
import com.example.capstoneserver.simulation.engine.EngineResult;
import com.example.capstoneserver.simulation.engine.NoiseSource;
import com.example.capstoneserver.simulation.engine.PathTrace;
import com.example.capstoneserver.simulation.engine.SimulationEngine;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 미래 다시 보기 (데모). /simulate와 같은 seed · 같은 미래 2,000개를 계산하고, 목표 시점에 모은 돈 순서로
 * 상위 5% · 가운데 · 하위 5%에 해당하는 미래를 골라 달마다 다시 돌려 기록한다. 새 계산 규칙은 없다.
 */
@Component
@RequiredArgsConstructor
class StoryAnalyzer {

    private static final String[] KINDS = {"BEST", "MIDDLE", "WORST"};
    private static final double[] QUANTILES = {0.95, 0.50, 0.05};

    private final SimulationEngine engine;
    private final ParamsRepository paramsRepository;

    StoryResponse story(EnginePlan plan, String startMonth, long seedValue) {
        int seed = (int) seedValue;
        int n = Analyzer.PATHS;
        int horizon = Analyzer.HORIZON;
        int gm = Math.min(plan.goalMonths(), horizon);
        EngineParams params = paramsRepository.getEngineParams();
        NoiseSource noise = NoiseSource.sfc32(seed, n);
        EngineResult main = engine.simulate(plan.profile(), params, noise, n, horizon, plan.bufferFirst(), true);

        // 목표 시점에 모은 돈 순서 (같으면 미래 번호 순서). 분위수 정의는 PlanRun.quantile과 같다
        int[] order = IntStream.range(0, n).boxed()
                .sorted(Comparator.<Integer>comparingDouble(p -> main.at(p, gm)).thenComparingInt(p -> p))
                .mapToInt(Integer::intValue).toArray();

        YearMonth start = YearMonth.parse(startMonth);
        List<StoryResponse.Story> stories = new ArrayList<>();
        for (int i = 0; i < KINDS.length; i++) {
            int path = order[Math.min(n - 1, (int) Math.floor(QUANTILES[i] * n))];
            PathTrace trace = engine.trace(plan.profile(), params, noise, path, horizon, plan.bufferFirst());
            stories.add(new StoryResponse.Story(KINDS[i], BigDecimal.valueOf(QUANTILES[i]), path, won(main.at(path, gm)),
                    main.at(path, gm) >= plan.goal(), months(trace, start, plan.goal())));
        }
        return new StoryResponse(seedValue, n, startMonth, gm, won(plan.goal()), stories);
    }

    private static List<StoryResponse.Month> months(PathTrace trace, YearMonth start, double goal) {
        List<StoryResponse.Month> months = new ArrayList<>();
        boolean wasEmployed = true;
        boolean reached = false;
        double owed = 0;
        for (PathTrace.Month m : trace.months()) {
            List<String> events = new ArrayList<>();
            if (m.has(PathTrace.JOB_LOSS)) events.add("JOB_LOSS");
            if (m.employed() && !wasEmployed) events.add("REHIRE");
            if (m.shock() > 0) events.add("SHOCK");
            if (m.planned() > 0) events.add("PLANNED");
            if (m.refund() > 0) events.add("REFUND");
            if (m.has(PathTrace.BREAK_GENERAL)) events.add("BREAK_GENERAL");
            if (m.has(PathTrace.BREAK_HOUSING)) events.add("BREAK_HOUSING");
            if (m.has(PathTrace.BREAK_POLICY)) events.add(m.has(PathTrace.BREAK_POLICY_SPECIAL) ? "BREAK_POLICY_SPECIAL" : "BREAK_POLICY");
            if (m.has(PathTrace.BREAK_PENSION)) events.add("BREAK_PENSION");
            if (m.has(PathTrace.MATURE_POLICY)) events.add("MATURE_POLICY");
            // 재가입하자마자 빈 계좌로 해지된 달은 둘 다 보여 주지 않는다 (받은 돈 · 바뀐 잔액이 없다)
            if (m.has(PathTrace.REJOIN_POLICY) && !m.has(PathTrace.POLICY_RESET)) events.add("REJOIN_POLICY");
            if (owed <= 0 && m.overdraft() > 0) events.add("OVERDRAFT");      // 빌리기 시작한 달
            if (owed > 0 && m.overdraft() <= 0) events.add("DEBT_CLEARED");   // 빌린 돈을 다 갚은 달
            owed = m.overdraft();
            if (!reached && m.total() >= goal) {
                reached = true;
                events.add("GOAL_REACHED");
            }
            wasEmployed = m.employed();

            months.add(new StoryResponse.Month(m.t() + 1, start.plusMonths(m.t()).toString(), m.employed(),
                    m.unemploymentBenefit(), List.copyOf(events),
                    won(m.salary()), won(m.income()), won(m.living()), won(m.shock()), won(m.planned()), won(m.loan()),
                    won(m.refund()), won(m.subsidy()), won(m.received()), won(m.taxPaid()),
                    new StoryResponse.Deposits(won(m.putPolicy()), won(m.putHousing()), won(m.putPension()), won(m.putGeneral()),
                            won(m.putInvest())),
                    new StoryResponse.Balances(won(m.cash()), won(m.overdraft()), won(m.policy()), won(m.housing()),
                            won(m.pension()), won(m.general()), won(m.invest()), won(m.deposit())),
                    won(m.total()), decimal(m.inflation() * 100, 2)));
        }
        return months;
    }
}
