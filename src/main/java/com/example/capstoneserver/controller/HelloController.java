package com.example.capstoneserver.controller;

import com.example.capstoneserver.global.exception.GeneralException;
import com.example.capstoneserver.global.response.ApiResponse;
import com.example.capstoneserver.global.response.ErrorStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HelloController {

    @GetMapping("/hello")
    public ApiResponse<String> hello() {
        return ApiResponse.onSuccess("hello");
    }

    // 오류 응답 모양 확인용. 실제 API가 생기면 지운다.
    @GetMapping("/hello/error")
    public ApiResponse<String> helloError() {
        throw new GeneralException(ErrorStatus.CAREER_EXCEEDS_AGE);
    }
}
