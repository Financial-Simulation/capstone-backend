package com.example.capstoneserver.example;

import com.example.capstoneserver.example.dto.ExamplesResponse;
import com.example.capstoneserver.example.dto.ExamplesResponse.Example;
import com.example.capstoneserver.global.response.ApiResponse;
import com.example.capstoneserver.input.dto.SimulationInput;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * 보고서 2.1의 시나리오 S1~S3 예시 입력.
 * 입력값은 resources/examples/S1.json ~ S3.json 에 있다. 값을 바꾸려면 그 파일만 고치면 된다.
 */
@RestController
@RequestMapping("/api/examples")
public class ExampleController {

    private final ExamplesResponse examples;

    public ExampleController(JsonMapper jsonMapper) {
        // 서버가 켜질 때 한 번만 읽는다. 파일이 깨져 있으면 서버가 아예 안 켜져서 바로 알 수 있다.
        this.examples = new ExamplesResponse(List.of(
                new Example("S1", "27세 개발자", read(jsonMapper, "S1")),
                new Example("S2", "24세 첫 직장", read(jsonMapper, "S2")),
                new Example("S3", "36세 계약직, 이사 예정", read(jsonMapper, "S3"))
        ));
    }

    @GetMapping
    public ApiResponse<ExamplesResponse> examples() {
        return ApiResponse.onSuccess(examples);
    }

    private static SimulationInput read(JsonMapper jsonMapper, String key) {
        try (InputStream in = new ClassPathResource("examples/" + key + ".json").getInputStream()) {
            return jsonMapper.readValue(in, SimulationInput.class);
        } catch (IOException e) {
            throw new UncheckedIOException("예시 입력 파일을 읽지 못했어요: " + key, e);
        }
    }
}
