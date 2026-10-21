package com.example.capstoneserver.input;

import com.example.capstoneserver.global.response.ApiResponse;
import com.example.capstoneserver.input.dto.ValidateRequest;
import com.example.capstoneserver.input.dto.ValidateResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/inputs")
@RequiredArgsConstructor
public class InputController {

    private final InputValidationService inputValidationService;

    @PostMapping("/validate")
    public ApiResponse<ValidateResponse> validate(@Valid @RequestBody ValidateRequest request) {
        return ApiResponse.onSuccess(inputValidationService.validate(request.step(), request.input()));
    }
}
