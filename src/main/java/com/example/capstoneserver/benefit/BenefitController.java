package com.example.capstoneserver.benefit;

import com.example.capstoneserver.benefit.dto.BenefitsRequest;
import com.example.capstoneserver.benefit.dto.BenefitsResponse;
import com.example.capstoneserver.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class BenefitController {

    private final BenefitService benefitService;

    /** 받을 수 있는 혜택: 판정 + 받으면 목표 달성 가능성이 얼마나 바뀌는지 */
    @PostMapping("/benefits")
    public ApiResponse<BenefitsResponse> benefits(@Valid @RequestBody BenefitsRequest request) {
        return ApiResponse.onSuccess(benefitService.benefits(request.input(), request.adjustments(),
                request.eligibility(), request.seed()));
    }
}
