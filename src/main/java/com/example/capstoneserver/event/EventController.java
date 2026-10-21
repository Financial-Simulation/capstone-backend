package com.example.capstoneserver.event;

import static com.example.capstoneserver.event.dto.PresetsResponse.EventTemplate.jobChange;
import static com.example.capstoneserver.event.dto.PresetsResponse.EventTemplate.move;
import static com.example.capstoneserver.event.dto.PresetsResponse.EventTemplate.oneTime;
import static com.example.capstoneserver.event.dto.PresetsResponse.EventTemplate.spendChange;

import com.example.capstoneserver.event.dto.PresetsResponse;
import com.example.capstoneserver.event.dto.PresetsResponse.Preset;
import com.example.capstoneserver.global.response.ApiResponse;
import com.example.capstoneserver.input.dto.SimulationInput.PresetKey;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/events")
public class EventController {

    private static final String ASSUMED = "가정값, 사용자가 고침";

    /** 보고서 FR-08의 5종. 금액은 데모와 같은 가정값 (노션 「예정된 일 프리셋 조회」). */
    private static final PresetsResponse PRESETS = new PresetsResponse(List.of(
            new Preset(PresetKey.INDEPENDENCE, "독립", ASSUMED,
                    List.of(move(5_000_000, 450_000, 800_000))),
            new Preset(PresetKey.JEONSE_MOVE, "전세 이사", ASSUMED,
                    List.of(move(10_000_000, -300_000, 1_500_000))),
            new Preset(PresetKey.WEDDING, "결혼 비용", ASSUMED,
                    List.of(oneTime(8_000_000))),
            new Preset(PresetKey.JOB_CHANGE, "이직", ASSUMED,
                    List.of(jobChange("10.0", 2))),
            new Preset(PresetKey.CAR, "자동차", ASSUMED,
                    List.of(oneTime(5_000_000), spendChange(300_000)))
    ));

    @GetMapping("/presets")
    public ApiResponse<PresetsResponse> presets() {
        return ApiResponse.onSuccess(PRESETS);
    }
}
