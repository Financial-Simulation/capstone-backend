package com.example.capstoneserver.global.exception;

import java.util.Map;
import lombok.Getter;

/**
 * 칸별 검사 실패. COMMON400 + { "person.age": "19~39 사이로 입력해 주세요" } 로 응답한다.
 * @Valid 로 못 하는 검사(입력 검사 API처럼 단계에 따라 검사 범위가 바뀌는 경우)에서 쓴다.
 */
@Getter
public class FieldValidationException extends RuntimeException {

    private final Map<String, String> errors;

    public FieldValidationException(Map<String, String> errors) {
        super("입력값 검사 실패");
        this.errors = errors;
    }
}
