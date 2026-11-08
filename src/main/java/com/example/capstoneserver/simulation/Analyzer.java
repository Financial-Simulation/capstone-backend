package com.example.capstoneserver.simulation;

import static com.example.capstoneserver.simulation.Rounding.decimal;
import static com.example.capstoneserver.simulation.Rounding.koreanMan;
import static com.example.capstoneserver.simulation.Rounding.percent;
import static com.example.capstoneserver.simulation.Rounding.wholePercent;
import static com.example.capstoneserver.simulation.Rounding.won;

import com.example.capstoneserver.global.response.Warning;
import com.example.capstoneserver.input.dto.SimulationInput;
import com.example.capstoneserver.loan.LoanCalculator;
import com.example.capstoneserver.plan.dto.Change;
import com.example.capstoneserver.simulation.dto.Allocation;
import com.example.capstoneserver.simulation.dto.CompareResponse;
import com.example.capstoneserver.simulation.dto.PreviewResponse;
import com.example.capstoneserver.simulation.dto.RecommendResponse;
import com.example.capstoneserver.simulation.dto.ReverseResponse;
import com.example.capstoneserver.simulation.dto.ReverseResponse.EmergencyStrategy;
import com.example.capstoneserver.simulation.dto.ReverseResponse.GridPoint;
import com.example.capstoneserver.simulation.dto.ReverseResponse.Lever;
import com.example.capstoneserver.simulation.dto.ReverseResponse.MonthlySaving;
import com.example.capstoneserver.simulation.dto.ReverseResponse.Period;
import com.example.capstoneserver.simulation.dto.ReverseResponse.VariableExpenseCut;
import com.example.capstoneserver.simulation.dto.SimulateResponse;
import com.example.capstoneserver.simulation.engine.EngineParams;
import com.example.capstoneserver.simulation.engine.EngineProfile;
import com.example.capstoneserver.simulation.engine.EngineResult;
import com.example.capstoneserver.simulation.engine.EngineResult.Flag;
import com.example.capstoneserver.simulation.engine.EngineResult.TaxItem;
import com.example.capstoneserver.simulation.engine.NoiseSource;
import com.example.capstoneserver.simulation.engine.SimulationEngine;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 엔진 결과 → API 응답 (구현 명세서 7장, 세금 모듈 7.2·7.4). 정의는 기준 저장소 make_analysis_golden.js와 같다.
 * 모든 계획은 같은 seed로 계산하므로 차이는 바꾼 내용에서만 나온다.
 */
@Component
@RequiredArgsConstructor
class Analyzer {

    static final int PATHS = 2000;
    static final int HORIZON = 120;
    static final double DEFAULT_TAU = 0.70;

    private static final int[] CHECKPOINTS = {12, 36, 60, 120};
    private static final long[] EMERGENCY_OPTIONS = {0, 100, 200, 300, 500};
    private static final String SIGNIFICANCE_RULE = "|z| ≥ 1.96 그리고 |차이| ≥ 2%p";

    private final SimulationEngine engine;
    private final LoanCalculator loanCalculator;
    private final ParamsRepository paramsRepository;

    // ===== 엔진 호출 (명세서 1장) =====

    /** R만: 확률만 필요한 탐색용 */
    PlanRun runMain(EnginePlan plan, int seed) {
        return new PlanRun(plan, run(plan, paramsRepository.getEngineParams(), seed, HORIZON, true), null, null, null);
    }

    /** R + F36 + F48 + TG(목표 시점까지: 세금·청약·연금 해지) */
    PlanRun runFull(EnginePlan plan, int seed) {
        EngineParams params = paramsRepository.getEngineParams();
        EngineParams firstAccount = params.withoutRejoin();
        return new PlanRun(plan, run(plan, params, seed, HORIZON, true),
                run(plan, firstAccount, seed, 36, false), run(plan, firstAccount, seed, 48, false),
                run(plan, params, seed, Math.max(plan.goalMonths(), 1), false));
    }

    private EngineResult run(EnginePlan plan, EngineParams params, int seed, int t, boolean collect) {
        return engine.simulate(plan.profile(), params, NoiseSource.sfc32(seed, PATHS), PATHS, t, plan.bufferFirst(), collect);
    }

    // ===== /simulate =====

    SimulateResponse simulate(SimulationInput input, EnginePlan plan, long seed, List<Warning> warnings) {
        PlanRun run = runFull(plan, (int) seed);
        EngineResult det = engine.simulate(plan.profile(), paramsRepository.getEngineParams(), NoiseSource.zero(),
                1, HORIZON, plan.bufferFirst(), true);

        YearMonth start = YearMonth.parse(input.startMonth());
        int gm = plan.goalMonths();
        int hits = run.hits();
        double p = (double) hits / PATHS;
        Integer medianReach = run.medianReach();
        Integer reach70 = run.reach(DEFAULT_TAU);
        BigDecimal probability = percent(hits, PATHS);

        String headline = "지금 계획이면 " + period(gm) + " 뒤 " + koreanMan(plan.goal()) + "을 모을 가능성은 약 "
                + wholePercent(probability) + "%예요. 계산기로는 " + koreanMan(det.at(0, gm))
                + "이 나오지만, 실직이나 급한 지출까지 넣으면 그래요.";

        SimulateResponse.Goal goal = new SimulateResponse.Goal(
                input.goal().targetAmount(), input.goal().targetDate(), gm, hits, probability,
                decimal(Math.sqrt(p * (1 - p) / PATHS) * 100, 1),
                medianReach, date(start, medianReach), reach70, date(start, reach70));

        List<SimulateResponse.Checkpoint> checkpoints = new ArrayList<>();
        for (int m : CHECKPOINTS) {
            double[] s = run.sortedAt(m);
            checkpoints.add(new SimulateResponse.Checkpoint(m, won(s[q(.05)]), won(s[q(.10)]), won(s[q(.50)]), won(s[q(.90)])));
        }
        List<SimulateResponse.Band> bands = new ArrayList<>();
        for (int m = 0; m <= HORIZON; m++) {
            double[] s = run.sortedAt(m);
            bands.add(new SimulateResponse.Band(start.plusMonths(m).toString(),
                    won(s[q(.05)]), won(s[q(.10)]), won(s[q(.50)]), won(s[q(.90)])));
        }

        List<Integer> firstNegatives = run.firstNegatives();
        Integer medianFirst = firstNegatives.isEmpty() ? null : PlanRun.lowMedian(firstNegatives);
        SimulateResponse.Debt debt = new SimulateResponse.Debt(firstNegatives.size(), percent(firstNegatives.size(), PATHS),
                medianFirst, date(start, medianFirst), won(run.quantile(gm, .05)));

        boolean eligible = run.main().eligible();
        SimulateResponse.PolicySaving policySaving = eligible
                ? new SimulateResponse.PolicySaving(true, run.earlyTerminations(),
                        percent(run.earlyTerminations(), PATHS), won(run.expectedSubsidy()))
                : new SimulateResponse.PolicySaving(false, null, null, null);

        List<SimulateResponse.EventMarker> markers = input.events().stream()
                .map(e -> new SimulateResponse.EventMarker(e.name(), e.occurDate()))
                .toList();

        return new SimulateResponse(seed, PATHS, paramsRepository.getModelVersion(), input.startMonth(), headline, goal,
                new SimulateResponse.Deterministic(won(det.at(0, gm)), won(det.at(0, HORIZON))),
                checkpoints, bands, new SimulateResponse.Risks(debt, policySaving), causes(run), tax(run), markers, warnings);
    }

    /** 세금 블록 (목표 시점까지 평균). 해지 확률은 그 상품이 있을 때만 */
    private SimulateResponse.Tax tax(PlanRun run) {
        double years = Math.max(run.plan().goalMonths(), 1) / 12.0;
        double refund = run.meanTax(TaxItem.REFUND);
        EngineResult.Allocation a = run.main().alloc();
        return new SimulateResponse.Tax(won(refund), won(refund / years), won(run.meanTax(TaxItem.INTEREST_TAX)),
                won(run.meanTax(TaxItem.CLAWBACK)), won(run.meanTax(TaxItem.PENSION_TAX)), won(run.taxNetBenefit()),
                a.housing() > 0 ? percent(run.toGoal().count(Flag.BRK_H), PATHS) : null,
                a.pension() > 0 ? percent(run.toGoal().count(Flag.BRK_PEN), PATHS) : null,
                allocation(run.main(), run.plan()));
    }

    /** 실제 배분 (원). 일반 적금 = 저축액 − 상품 납입 */
    private static Allocation allocation(EngineResult r, EnginePlan plan) {
        EngineResult.Allocation a = r.alloc();
        return new Allocation(won(a.policy()), won(a.housing()), won(a.pension()),
                won(Math.max(plan.profile().save() - a.policy() - a.housing() - a.pension(), 0)));
    }

    private SimulateResponse.Causes causes(PlanRun run) {
        EngineResult f = run.first36();
        int sp = f.count(Flag.C1_SP);
        int un = f.count(Flag.C1_UNEMP);
        int sh = f.count(Flag.C1_SHOCK);
        int de = f.count(Flag.C1_DEFICIT);
        int total = sp + un + sh + de;
        return new SimulateResponse.Causes(new SimulateResponse.CauseCounts(sp, un, sh, de),
                share(sp, total), share(un, total), share(sh, total), share(de, total));
    }

    private static BigDecimal share(int count, int total) {
        return total == 0 ? BigDecimal.ZERO : percent(count, total);
    }

    // ===== /simulate/preview =====

    PreviewResponse preview(SimulationInput input, EnginePlan current, EnginePlan adjusted, long seed, List<Change> changes) {
        PlanRun a = runFull(current, (int) seed);
        PlanRun b = runFull(adjusted, (int) seed);
        YearMonth start = YearMonth.parse(input.startMonth());
        Paired paired = paired(b, a);

        int hitsA = a.hits();
        int hitsB = b.hits();
        int debtA = a.firstNegatives().size();
        int debtB = b.firstNegatives().size();
        long worstA = won(a.quantile(current.goalMonths(), .05));
        long worstB = won(b.quantile(adjusted.goalMonths(), .05));

        List<PreviewResponse.Row> table = new ArrayList<>();
        table.add(new PreviewResponse.Row("PROBABILITY", percent(hitsA, PATHS), percent(hitsB, PATHS), percent(hitsB - hitsA, PATHS)));
        table.add(new PreviewResponse.Row("DEBT_PROBABILITY", percent(debtA, PATHS), percent(debtB, PATHS), percent(debtB - debtA, PATHS)));
        table.add(new PreviewResponse.Row("WORST_CASE_AT_GOAL", worstA, worstB, worstB - worstA));
        if (a.main().eligible() && b.main().eligible()) {
            int brkA = a.earlyTerminations();
            int brkB = b.earlyTerminations();
            long subA = won(a.expectedSubsidy());
            long subB = won(b.expectedSubsidy());
            table.add(new PreviewResponse.Row("EARLY_TERMINATION", percent(brkA, PATHS), percent(brkB, PATHS), percent(brkB - brkA, PATHS)));
            table.add(new PreviewResponse.Row("EXPECTED_SUBSIDY", subA, subB, subB - subA));
        } else {
            table.add(policyRow("EARLY_TERMINATION", a, b, true));
            table.add(policyRow("EXPECTED_SUBSIDY", a, b, false));
        }

        return new PreviewResponse(seed,
                new PreviewResponse.Summary(percent(hitsA, PATHS), date(start, a.reach(DEFAULT_TAU)), yearlyP50(a)),
                new PreviewResponse.Summary(percent(hitsB, PATHS), date(start, b.reach(DEFAULT_TAU)), yearlyP50(b)),
                table,
                new PreviewResponse.Significance(paired.z(), paired.meaningful(), SIGNIFICANCE_RULE),
                changes);
    }

    /** 한쪽이라도 비대상이면 차이를 낼 수 없다. 대상인 쪽 값만 채운다 */
    private PreviewResponse.Row policyRow(String metric, PlanRun a, PlanRun b, boolean probability) {
        return new PreviewResponse.Row(metric, policyValue(a, probability), policyValue(b, probability), null);
    }

    private Number policyValue(PlanRun run, boolean probability) {
        if (!run.main().eligible()) return null;
        return probability ? percent(run.earlyTerminations(), PATHS) : (Number) won(run.expectedSubsidy());
    }

    // ===== /compare =====

    CompareResponse.Plan basePlan(YearMonth start, PlanRun run) {
        return plan("지금 계획", true, start, run, BigDecimal.ZERO.setScale(1), null, null);
    }

    CompareResponse.Plan comparedPlan(String name, YearMonth start, PlanRun run, PlanRun base) {
        Paired paired = paired(run, base);
        return plan(name, false, start, run, percent(run.hits() - base.hits(), PATHS), paired.z(), paired.meaningful());
    }

    private CompareResponse.Plan plan(String name, boolean isBase, YearMonth start, PlanRun run,
                                      BigDecimal diff, BigDecimal z, Boolean meaningful) {
        boolean eligible = run.main().eligible();
        return new CompareResponse.Plan(name, isBase, percent(run.hits(), PATHS), diff, z, meaningful,
                date(start, run.reach(DEFAULT_TAU)),
                percent(run.firstNegatives().size(), PATHS),
                won(run.quantile(run.plan().goalMonths(), .05)),
                eligible ? percent(run.earlyTerminations(), PATHS) : null,
                eligible ? won(run.expectedSubsidy()) : null,
                yearlyP50(run),
                won(run.medianAtGoal()),
                won(run.taxNetBenefit()),
                allocation(run.main(), run.plan()));
    }

    // ===== /allocations/recommend (자산 쪼개기) =====

    /** 청약 25만 = 소득공제 한도(연 300만). 만 원 */
    private static final double[] REC_HOUSING = {0, 10, 25};
    private static final double[] REC_PENSION = {0, 10, 20, 30, 50};
    private static final String REC_RULE = "청년미래 0~50만 원(10만 원 간격), 청약 0·10·25만 원, 연금저축 0·10·20·30·50만 원 중 월 저축액 안에 드는 조합. "
            + "세금·지원금 최대는 지금보다 가능성이 2%p보다 많이 낮아지지 않는 배분 중에서 고름";

    private record Scored(EngineResult.Allocation alloc, int hits, long taxNet, long subsidy, BigDecimal policyBreak) {
        long benefit() {
            return taxNet + subsidy;
        }
    }

    /**
     * 격자 안의 배분을 같은 미래 2,000개로 모두 계산해 목표 달성 최대 배분과 세금·지원금 최대 배분을 고른다.
     * 배분 하나 = R(T = 120)의 목표 시점 달성 수 + TG(T = 목표 개월)의 세금 순이득·지원금·청년미래 해지.
     */
    RecommendResponse recommend(EnginePlan plan, long seedValue) {
        int seed = (int) seedValue;
        EngineProfile pr = plan.profile();
        double save = pr.save();
        EngineResult probe = engine.simulate(pr, paramsRepository.getEngineParams(), NoiseSource.zero(), 1, 1, plan.bufferFirst(), false);
        List<Double> policies = new ArrayList<>();
        if (probe.eligible()) {
            double cap = Math.min(save, paramsRepository.getEngineParams().policy().cap());
            for (double v = 0; v <= cap + 1e-9; v += 10) policies.add(v);
            if (cap % 10 != 0) policies.add(cap);
        } else {
            policies.add(0.0);
        }

        EngineResult.Allocation now = probe.alloc();
        Scored current = scoreAllocation(plan, now.policy(), now.housing(), now.pension(), seed);
        Scored bestP = null;
        Scored bestB = null;
        int count = 0;
        for (double pol : policies) {
            for (double hou : REC_HOUSING) {
                for (double pen : REC_PENSION) {
                    if (pol + hou + pen > save + 1e-9) continue;
                    Scored s = scoreAllocation(plan, pol, hou, pen, seed);
                    count++;
                    if (bestP == null || s.hits() > bestP.hits() || (s.hits() == bestP.hits() && s.benefit() > bestP.benefit())) bestP = s;
                    if (s.hits() >= current.hits() - 0.02 * PATHS
                            && (bestB == null || s.benefit() > bestB.benefit() || (s.benefit() == bestB.benefit() && s.hits() > bestB.hits()))) {
                        bestB = s;
                    }
                }
            }
        }
        return new RecommendResponse(won(save), count, option(current, current, plan), option(bestP, current, plan),
                option(bestB, current, plan), REC_RULE);
    }

    private Scored scoreAllocation(EnginePlan base, double pol, double hou, double pen, int seed) {
        EnginePlan plan = base.withProfile(base.profile().withAllocation(pol, hou, pen));
        EngineParams params = paramsRepository.getEngineParams();
        EngineResult main = run(plan, params, seed, HORIZON, true);
        EngineResult toGoal = run(plan, params, seed, Math.max(plan.goalMonths(), 1), false);
        int hits = 0;
        for (int p = 0; p < PATHS; p++) {
            if (main.at(p, plan.goalMonths()) >= plan.goal()) hits++;
        }
        // 합계끼리 뺀 뒤 나눈다 (정답 파일과 같은 순서. 평균끼리 빼면 마지막 자리가 달라져 원 단위 반올림이 어긋날 수 있다)
        double[] sum = new double[TaxItem.values().length];
        double subsidy = 0;
        for (int p = 0; p < PATHS; p++) {
            for (int k = 0; k < sum.length; k++) sum[k] += toGoal.tax()[k][p];
            subsidy += toGoal.matchPaid()[p];
        }
        double net = (sum[0] - sum[1] - sum[2] - sum[3]) / PATHS;
        return new Scored(main.alloc(), hits, won(net), won(subsidy / PATHS),
                main.eligible() ? percent(toGoal.count(Flag.BRK1_GEN), PATHS) : null);
    }

    private static RecommendResponse.Option option(Scored s, Scored current, EnginePlan plan) {
        if (s == null) return null;
        EngineResult.Allocation a = s.alloc();
        Allocation alloc = new Allocation(won(a.policy()), won(a.housing()), won(a.pension()),
                won(Math.max(plan.profile().save() - a.policy() - a.housing() - a.pension(), 0)));
        return new RecommendResponse.Option(alloc, s.hits(), percent(s.hits(), PATHS), s.taxNet(), s.subsidy(), s.benefit(),
                s.policyBreak(), percent(s.hits() - current.hits(), PATHS));
    }

    // ===== /reverse =====

    ReverseResponse reverse(SimulationInput input, EnginePlan plan, long seedValue, BigDecimal targetPercent) {
        int seed = (int) seedValue;
        double tau = targetPercent.doubleValue() / 100;
        EngineProfile pr = plan.profile();
        YearMonth start = YearMonth.parse(input.startMonth());
        PlanRun base = runFull(plan, seed);
        int baseHits = base.hits();

        double surplus = pr.netIncome() - pr.fixed() - pr.variable() - loanCalculator.monthlyPayments(pr.loans(), 1)[0];

        Integer period = base.reach(tau);
        return new ReverseResponse(seedValue, percent(baseHits, PATHS), targetPercent, won(surplus),
                requiredCut(plan, baseHits, tau, seed),
                requiredSaving(plan, surplus, tau, seed, targetPercent),
                new Period(period, date(start, period)),
                emergencyStrategies(plan, seed),
                levers(plan, base, baseHits, seed));
    }

    private VariableExpenseCut requiredCut(EnginePlan plan, int baseHits, double tau, int seed) {
        EngineProfile pr = plan.profile();
        double limit = Math.min(pr.variable(), 60);
        String searched = "50,000원~" + comma(won(limit)) + "원, 50,000원 간격";
        if ((double) baseHits / PATHS >= tau) {
            return new VariableExpenseCut(0L, percent(baseHits, PATHS), searched);
        }
        for (int k = 5; k <= limit; k += 5) {
            int hits = runMain(plan.withProfile(pr.withVariable(Math.max(0, pr.variable() - k))), seed).hits();
            if ((double) hits / PATHS >= tau) {
                return new VariableExpenseCut(won(k), percent(hits, PATHS), searched);
            }
        }
        return new VariableExpenseCut(null, null, searched);
    }

    /** 납입을 늘리면 해지 위험 때문에 확률이 떨어질 수 있어 이분 탐색 대신 격자로 찾는다 */
    private MonthlySaving requiredSaving(EnginePlan plan, double surplus, double tau, int seed, BigDecimal targetPercent) {
        EngineProfile pr = plan.profile();
        Long amount = null;
        Long atAmount = null;
        BigDecimal maxProbability = null;
        int best = -1;
        List<GridPoint> grid = new ArrayList<>();

        for (int s = 0; s <= surplus + 1e-9; s += 5) {
            int hits = runMain(plan.withProfile(pr.withSave(s)), seed).hits();
            grid.add(new GridPoint(won(s), percent(hits, PATHS)));
            if (hits > best) {
                best = hits;
                maxProbability = percent(hits, PATHS);
                atAmount = won(s);
            }
            if (amount == null && (double) hits / PATHS >= tau) amount = won(s);
        }

        String searched = "0원~월 여유자금(" + comma(won(surplus)) + "원), 50,000원 간격";
        String reason = amount == null
                ? "저축하지 않은 돈도 통장에 남아 모은 돈에 들어가므로, 저축액만 바꿔서는 "
                        + wholePercent(targetPercent) + "%에 닿지 않아요."
                : null;
        String note = pr.save() > surplus
                ? "지금 저축액 " + comma(won(pr.save())) + "원은 월 여유자금보다 커서 탐색 범위 밖이에요."
                : null;
        return new MonthlySaving(amount, maxProbability, atAmount, grid, searched, reason, note);
    }

    private List<EmergencyStrategy> emergencyStrategies(EnginePlan plan, int seed) {
        List<EmergencyStrategy> strategies = new ArrayList<>();
        for (long buffer : EMERGENCY_OPTIONS) {
            PlanRun run = runFull(plan.withBufferFirst(buffer), seed);
            boolean eligible = run.main().eligible();
            strategies.add(new EmergencyStrategy(won(buffer), percent(run.hits(), PATHS),
                    eligible ? percent(run.earlyTerminations(), PATHS) : null,
                    eligible ? won(run.expectedSubsidy()) : null));
        }
        return strategies;
    }

    private List<Lever> levers(EnginePlan plan, PlanRun base, int baseHits, int seed) {
        EngineProfile pr = plan.profile();
        record Candidate(String type, String label, EnginePlan plan) {}
        record Scored(Lever lever, int diff) {}

        List<Candidate> candidates = new ArrayList<>();
        if (pr.variable() >= 10) {
            candidates.add(new Candidate("REDUCE_VARIABLE_EXPENSE", "변동지출을 월 10만 원 줄이기",
                    plan.withProfile(pr.withVariable(pr.variable() - 10))));
        }
        if (plan.goalMonths() + 12 <= HORIZON) {
            candidates.add(new Candidate("DELAY_GOAL", "목표 시점을 1년 늦추기", plan.withGoalMonths(plan.goalMonths() + 12)));
        }
        candidates.add(new Candidate("LOWER_GOAL", "목표 금액을 10% 낮추기", plan.withGoal(Math.round(plan.goal() * 0.9))));
        if (base.main().eligible() && pr.cash0() < 200) {
            candidates.add(new Candidate("EMERGENCY_FIRST", "비상금 200만 원을 먼저 모은 뒤 적금 시작", plan.withBufferFirst(200)));
        }
        if (pr.save() >= 20) {
            candidates.add(new Candidate("REDUCE_SAVING", "월 저축액을 10만 원 줄이기",
                    plan.withProfile(pr.withSave(pr.save() - 10))));
        }

        return candidates.stream()
                .map(c -> {
                    int hits = runMain(c.plan(), seed).hits();
                    int diff = hits - baseHits;
                    return new Scored(new Lever(c.type(), c.label(), percent(hits, PATHS), percent(diff, PATHS),
                            Math.abs(diff) < 0.02 * PATHS), diff);
                })
                .sorted(Comparator.comparingInt(Scored::diff).reversed())
                .map(Scored::lever)
                .toList();
    }

    // ===== 공통 =====

    private record Paired(BigDecimal z, boolean meaningful) {}

    /** 짝지은 검정. changed = 바꾼 계획, base = 지금 계획 (명세서 7.3) */
    private Paired paired(PlanRun changed, PlanRun base) {
        double s = 0;
        double s2 = 0;
        for (int p = 0; p < PATHS; p++) {
            int d = (changed.hit(p) ? 1 : 0) - (base.hit(p) ? 1 : 0);
            s += d;
            s2 += d * d;
        }
        double md = s / PATHS;
        double sd = Math.sqrt(Math.max(s2 / PATHS - md * md, 0) * PATHS / (PATHS - 1));
        double z = sd > 0 ? md / (sd / Math.sqrt(PATHS)) : 0;
        return new Paired(decimal(z, 2), Math.abs(z) >= 1.96 && Math.abs(md) >= 0.02);
    }

    private List<Long> yearlyP50(PlanRun run) {
        List<Long> values = new ArrayList<>();
        for (int m = 12; m <= run.plan().goalMonths(); m += 12) {
            values.add(won(run.quantile(m, .50)));
        }
        return values;
    }

    private static int q(double x) {
        return Math.min(PATHS - 1, (int) Math.floor(x * PATHS));
    }

    private static String date(YearMonth start, Integer months) {
        return months == null ? null : start.plusMonths(months).toString();
    }

    private static String period(int gm) {
        return gm % 12 == 0 ? (gm / 12) + "년" : (gm / 12) + "년 " + (gm % 12) + "개월";
    }

    private static String comma(long value) {
        return NumberFormat.getNumberInstance(Locale.KOREA).format(value);
    }
}
