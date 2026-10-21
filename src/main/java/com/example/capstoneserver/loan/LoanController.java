package com.example.capstoneserver.loan;

import com.example.capstoneserver.global.response.ApiResponse;
import com.example.capstoneserver.loan.dto.RepaymentPreviewRequest;
import com.example.capstoneserver.loan.dto.RepaymentPreviewResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/loans")
@RequiredArgsConstructor
public class LoanController {

    private final LoanCalculator loanCalculator;

    @PostMapping("/repayment-preview")
    public ApiResponse<RepaymentPreviewResponse> repaymentPreview(@Valid @RequestBody RepaymentPreviewRequest request) {
        return ApiResponse.onSuccess(loanCalculator.calculate(
                request.principal(),
                request.resolvedRate(),
                request.remainingMonths(),
                request.resolvedRepaymentType()));
    }
}
