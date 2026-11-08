package com.example.capstoneserver.simulation;

import com.example.capstoneserver.global.exception.GeneralException;
import com.example.capstoneserver.global.response.ErrorStatus;
import com.example.capstoneserver.input.InputValidationService;
import com.example.capstoneserver.input.dto.InputStep;
import com.example.capstoneserver.input.dto.SimulationInput;
import com.example.capstoneserver.plan.PlanAdjuster;
import com.example.capstoneserver.plan.dto.Adjustments;
import com.example.capstoneserver.simulation.dto.CompareResponse;
import com.example.capstoneserver.simulation.dto.PlanSpec;
import com.example.capstoneserver.simulation.dto.PreviewResponse;
import com.example.capstoneserver.simulation.dto.RecommendResponse;
import com.example.capstoneserver.simulation.dto.ReverseResponse;
import com.example.capstoneserver.simulation.dto.RobustResponse;
import com.example.capstoneserver.simulation.dto.SimulateResponse;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 계산 API의 흐름: 검사 → adjustments 적용 → 변환 → 엔진·분석.
 * 입력은 보관하지 않고 요청이 끝나면 버린다 (NFR-06).
 */
@Service
@RequiredArgsConstructor
public class SimulationService {

    private static final long DEFAULT_SEED = 20261007;
    private static final BigDecimal DEFAULT_TARGET = new BigDecimal("70.0");
    private static final int MAX_PLANS = 3;

    private final InputValidationService inputValidationService;
    private final PlanAdjuster planAdjuster;
    private final ProfileMapper profileMapper;
    private final Analyzer analyzer;
    private final RobustAnalyzer robustAnalyzer;

    public SimulateResponse simulate(SimulationInput input, Long seed) {
        inputValidationService.checkRequired(input);
        var warnings = inputValidationService.validate(InputStep.ALL, input).warnings();
        return analyzer.simulate(input, profileMapper.toPlan(input), seedOf(seed), warnings);
    }

    public PreviewResponse preview(SimulationInput input, Adjustments adjustments, Long seed) {
        inputValidationService.checkRequired(input);
        SimulationInput adjusted = adjust(input, adjustments);
        return analyzer.preview(input, profileMapper.toPlan(input), profileMapper.toPlan(adjusted), seedOf(seed),
                planAdjuster.changes(input, adjusted));
    }

    public CompareResponse compare(SimulationInput input, List<PlanSpec> plans, Long seed) {
        if (plans == null || plans.isEmpty() || plans.size() > MAX_PLANS) {
            throw new GeneralException(ErrorStatus.PLAN_COUNT_INVALID);
        }
        inputValidationService.checkRequired(input);

        // 잘못된 계획이 있으면 계산을 시작하기 전에 돌려보낸다
        List<EnginePlan> adjusted = plans.stream()
                .map(p -> profileMapper.toPlan(adjust(input, p.adjustments())))
                .toList();

        long seedValue = seedOf(seed);
        YearMonth start = YearMonth.parse(input.startMonth());
        EnginePlan basePlan = profileMapper.toPlan(input);
        PlanRun base = analyzer.runFull(basePlan, (int) seedValue);

        List<CompareResponse.Plan> results = new ArrayList<>();
        results.add(analyzer.basePlan(start, base));
        for (int i = 0; i < plans.size(); i++) {
            PlanRun run = analyzer.runFull(adjusted.get(i), (int) seedValue);
            results.add(analyzer.comparedPlan(plans.get(i).name(), start, run, base));
        }
        return new CompareResponse(seedValue, basePlan.goalMonths(), results);
    }

    /** 자산 쪼개기 자동 추천 (청년미래·청약·연금저축 배분) */
    public RecommendResponse recommend(SimulationInput input, Long seed) {
        inputValidationService.checkRequired(input);
        return analyzer.recommend(profileMapper.toPlan(input), seedOf(seed));
    }

    public ReverseResponse reverse(SimulationInput input, BigDecimal targetProbability, Long seed) {
        inputValidationService.checkRequired(input);
        return analyzer.reverse(input, profileMapper.toPlan(input), seedOf(seed),
                targetProbability == null ? DEFAULT_TARGET : targetProbability);
    }

    /**
     * 검사와 변환만 먼저 해 둔다. 오류는 작업을 등록하기 전에 400으로 돌려보내야 하고,
     * 작업에는 원본 입력 대신 계산에 필요한 값만 넘긴다.
     */
    public RobustTask prepareRobust(SimulationInput input, List<PlanSpec> plans) {
        List<PlanSpec> specs = plans == null ? List.of() : plans;
        if (specs.size() > MAX_PLANS) {
            throw new GeneralException(ErrorStatus.PLAN_COUNT_INVALID);
        }
        inputValidationService.checkRequired(input);

        EnginePlan base = profileMapper.toPlan(input);
        List<NamedPlan> named = specs.stream()
                .map(p -> new NamedPlan(p.name(), profileMapper.toPlan(adjust(input, p.adjustments()))))
                .toList();
        return new RobustTask(base, named);
    }

    public RobustResponse.Result runRobust(RobustTask task, IntConsumer progress) {
        int seed = (int) DEFAULT_SEED;
        int baseHits = analyzer.runMain(task.base(), seed).hits();
        List<RobustAnalyzer.ComparedPlan> compared = task.plans().stream()
                .map(p -> new RobustAnalyzer.ComparedPlan(p.name(), p.plan(), analyzer.runMain(p.plan(), seed).hits() - baseHits))
                .toList();
        return robustAnalyzer.analyze(task.base(), compared, progress);
    }

    public record RobustTask(EnginePlan base, List<NamedPlan> plans) {}

    public record NamedPlan(String name, EnginePlan plan) {}

    private SimulationInput adjust(SimulationInput input, Adjustments adjustments) {
        SimulationInput adjusted = planAdjuster.apply(input, adjustments);
        inputValidationService.checkRequired(adjusted);
        return adjusted;
    }

    private static long seedOf(Long seed) {
        return seed == null ? DEFAULT_SEED : seed;
    }
}
