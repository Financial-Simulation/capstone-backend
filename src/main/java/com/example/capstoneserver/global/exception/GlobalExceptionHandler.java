package com.example.capstoneserver.global.exception;

import com.example.capstoneserver.global.response.ApiResponse;
import com.example.capstoneserver.global.response.ErrorStatus;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 어디서 오류가 나든 여기로 모여서 ApiResponse 모양으로 바뀐다.
 * 요청 본문(금융 입력값)은 로그에 남기지 않는다 (NFR-06).
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // 우리가 직접 던진 비즈니스 오류 (INPUT4001 등)
    @ExceptionHandler(GeneralException.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneral(GeneralException e) {
        ErrorStatus status = e.getStatus();
        return ResponseEntity.status(status.getHttpStatus())
                .body(ApiResponse.onFailure(status, null));
    }

    // @Valid 검사 실패 → { "person.age": "19~39 사이로 입력해 주세요" }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> errors = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(err -> errors.putIfAbsent(err.getField(), err.getDefaultMessage()));
        return ResponseEntity.badRequest()
                .body(ApiResponse.onFailure(ErrorStatus.BAD_REQUEST, errors));
    }

    // JSON 모양이 깨졌거나 Enum 값이 틀림
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.onFailure(ErrorStatus.BAD_REQUEST, null));
    }

    // 없는 주소로 요청
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(NoResourceFoundException e) {
        return ResponseEntity.status(ErrorStatus.NOT_FOUND.getHttpStatus())
                .body(ApiResponse.onFailure(ErrorStatus.NOT_FOUND, null));
    }

    // 예상 못 한 오류
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnknown(Exception e) {
        log.error("처리되지 않은 오류", e);
        return ResponseEntity.internalServerError()
                .body(ApiResponse.onFailure(ErrorStatus.INTERNAL_SERVER_ERROR, null));
    }
}
