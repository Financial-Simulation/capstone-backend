package com.example.capstoneserver.report;

import com.example.capstoneserver.global.response.ApiResponse;
import com.example.capstoneserver.report.dto.ReportRequest;
import com.example.capstoneserver.report.dto.ReportResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    @PostMapping("/reports")
    public ApiResponse<ReportResponse> report(@Valid @RequestBody ReportRequest request) {
        return ApiResponse.onSuccess(reportService.report(request.input(), request.plans(), request.seed()));
    }
}
