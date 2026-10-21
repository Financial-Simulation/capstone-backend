package com.example.capstoneserver.global.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 모든 API가 돌려주는 공통 응답 모양.
 * { "isSuccess": true, "code": "COMMON200", "message": "성공입니다.", "result": { ... } }
 */
@JsonPropertyOrder({"isSuccess", "code", "message", "result"})
public record ApiResponse<T>(
        @JsonProperty("isSuccess") boolean isSuccess,
        String code,
        String message,
        T result
) {

    // 성공 (200)
    public static <T> ApiResponse<T> onSuccess(T result) {
        return new ApiResponse<>(true, "COMMON200", "성공입니다.", result);
    }

    // 비동기 작업 접수 (202) - /robust 에서 사용
    public static <T> ApiResponse<T> onAccepted(T result) {
        return new ApiResponse<>(true, "COMMON202", "작업이 접수되었습니다.", result);
    }

    // 실패
    public static <T> ApiResponse<T> onFailure(ErrorStatus status, T result) {
        return new ApiResponse<>(false, status.getCode(), status.getMessage(), result);
    }
}
