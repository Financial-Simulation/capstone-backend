package com.example.capstoneserver.report;

import com.example.capstoneserver.input.InputValidationService;
import com.example.capstoneserver.input.dto.InputStep;
import com.example.capstoneserver.input.dto.SimulationInput;
import com.example.capstoneserver.report.dto.ReportResponse;
import com.example.capstoneserver.simulation.ParamsRepository;
import com.example.capstoneserver.simulation.SimulationService;
import com.example.capstoneserver.simulation.dto.CompareResponse;
import com.example.capstoneserver.simulation.dto.PlanSpec;
import com.example.capstoneserver.simulation.dto.ReverseResponse;
import com.example.capstoneserver.simulation.dto.SimulateResponse;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 리포트 데이터 조립 (FR-11). 새 계산은 없고 /simulate·/reverse·/compare 결과를 한 번에 묶는다. */
@Service
@RequiredArgsConstructor
public class ReportService {

    static final String DISCLAIMER = "이 결과는 입력값과 공공통계에 기반한 시뮬레이션이며 금융상품 추천이나 투자 권유가 아니에요.";
    private static final int TOP_LEVERS = 3;

    private final SimulationService simulationService;
    private final InputValidationService inputValidationService;
    private final ParamsRepository paramsRepository;

    public ReportResponse report(SimulationInput input, List<PlanSpec> plans, Long seed) {
        SimulateResponse sim = simulationService.simulate(input, seed);
        ReverseResponse rev = simulationService.reverse(input, null, seed);
        List<CompareResponse.Plan> comparison = plans == null || plans.isEmpty()
                ? List.of()
                : simulationService.compare(input, plans, seed).plans();
        List<ReverseResponse.Lever> levers = rev.levers().subList(0, Math.min(TOP_LEVERS, rev.levers().size()));
        String now = LocalDateTime.now(ZoneId.of("Asia/Seoul")).truncatedTo(ChronoUnit.SECONDS)
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

        return new ReportResponse(now, sim.seed(), sim.modelVersion(), sim.startMonth(),
                inputValidationService.validate(InputStep.ALL, input).summary(),
                sim.headline(), sim.goal(),
                new ReportResponse.Forecast(sim.checkpoints(), sim.deterministic().atGoal()),
                sim.risks(), sim.causes(), sim.tax(), levers, comparison,
                paramsRepository.getMeta().get("parameters"), sim.warnings(), DISCLAIMER);
    }
}
