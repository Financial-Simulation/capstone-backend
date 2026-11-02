package com.example.capstoneserver.simulation;

import com.example.capstoneserver.global.response.ApiResponse;
import com.example.capstoneserver.simulation.dto.CompareResponse;
import com.example.capstoneserver.simulation.dto.MetaResponse;
import com.example.capstoneserver.simulation.dto.PreviewResponse;
import com.example.capstoneserver.simulation.dto.ReverseResponse;
import com.example.capstoneserver.simulation.dto.RobustResponse;
import com.example.capstoneserver.simulation.dto.SimulateResponse;
import com.example.capstoneserver.simulation.dto.SimulationRequests;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class SimulationController {

    private final SimulationService simulationService;
    private final RobustJobService robustJobService;
    private final ParamsRepository paramsRepository;

    @PostMapping("/simulate")
    public ApiResponse<SimulateResponse> simulate(@Valid @RequestBody SimulationRequests.Simulate request) {
        return ApiResponse.onSuccess(simulationService.simulate(request.input(), request.seed()));
    }

    @PostMapping("/simulate/preview")
    public ApiResponse<PreviewResponse> preview(@Valid @RequestBody SimulationRequests.Preview request) {
        return ApiResponse.onSuccess(simulationService.preview(request.input(), request.adjustments(), request.seed()));
    }

    @PostMapping("/compare")
    public ApiResponse<CompareResponse> compare(@Valid @RequestBody SimulationRequests.Compare request) {
        return ApiResponse.onSuccess(simulationService.compare(request.input(), request.plans(), request.seed()));
    }

    @PostMapping("/reverse")
    public ApiResponse<ReverseResponse> reverse(@Valid @RequestBody SimulationRequests.Reverse request) {
        return ApiResponse.onSuccess(simulationService.reverse(request.input(), request.targetProbability(), request.seed()));
    }

    @PostMapping("/robust")
    public ResponseEntity<ApiResponse<RobustResponse>> robust(@Valid @RequestBody SimulationRequests.Robust request) {
        return ResponseEntity.accepted()
                .body(ApiResponse.onAccepted(robustJobService.start(request.input(), request.plans())));
    }

    @GetMapping("/robust/{jobId}")
    public ApiResponse<RobustResponse> robustResult(@PathVariable String jobId) {
        return ApiResponse.onSuccess(robustJobService.get(jobId));
    }

    @GetMapping("/meta")
    public ApiResponse<MetaResponse> meta() {
        JsonNode meta = paramsRepository.getMeta();
        return ApiResponse.onSuccess(new MetaResponse(paramsRepository.getModelVersion(), Analyzer.PATHS, 20261007,
                Analyzer.HORIZON, meta.get("gradeLegend"), meta.get("editableInputs"), meta.get("parameters"),
                meta.get("otherGrades"), meta.get("validation")));
    }
}
