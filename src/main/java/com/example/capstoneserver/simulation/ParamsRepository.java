package com.example.capstoneserver.simulation;

import com.example.capstoneserver.simulation.engine.EngineParams;
import com.example.capstoneserver.simulation.engine.EngineParams.General;
import com.example.capstoneserver.simulation.engine.EngineParams.Housing;
import com.example.capstoneserver.simulation.engine.EngineParams.Insurance;
import com.example.capstoneserver.simulation.engine.EngineParams.Pension;
import com.example.capstoneserver.simulation.engine.EngineParams.Policy;
import com.example.capstoneserver.simulation.engine.EngineParams.Stats;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import lombok.Getter;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * resources/params.json을 서버가 켜질 때 한 번 읽는다 (읽기 전용).
 * 파일이 깨져 있으면 서버가 아예 안 켜져서 바로 알 수 있다.
 * 값을 바꾸는 순서는 기준 저장소 README 참고 (kernel.py 수정 → build_params.py → check_params.js → 이 파일 교체).
 */
@Getter
@Component
public class ParamsRepository {

    private final String modelVersion;
    private final EngineParams engineParams;
    private final Robust robust;
    private final JsonNode meta;

    public ParamsRepository(JsonMapper jsonMapper) {
        try (InputStream in = new ClassPathResource("params.json").getInputStream()) {
            ParamsFile file = jsonMapper.readValue(in, ParamsFile.class);
            this.modelVersion = file.modelVersion();
            this.engineParams = new EngineParams(file.params(), file.policy(), file.general(), file.housing(), file.pension(),
                    file.taxRules().insurance());
            this.robust = file.robust();
            this.meta = file.meta();
        } catch (IOException e) {
            throw new UncheckedIOException("params.json을 읽지 못했어요", e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ParamsFile(String modelVersion, Stats params, Policy policy, General general, Housing housing,
                              Pension pension, TaxRules taxRules, Robust robust, JsonNode meta) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TaxRules(Insurance insurance) {}

    /** 가능성 범위 계산에서 바꾸는 범위와 급전 확률표 (구현 명세서 7.5) */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Robust(
            int trials,
            int pathsPerTrial,
            int paramSeed,
            int noiseSeedBase,
            Map<String, double[]> uniform,
            Map<String, Double> exitByStructure,
            double[] shockAmountMultipliers,
            String[] cohortVariants,
            @JsonProperty("shockProbByStructureAmountCohort") Map<String, Double> shockProb
    ) {}
}
