package com.example.capstoneserver.global.exception;

import com.example.capstoneserver.global.response.ErrorStatus;
import lombok.Getter;

/**
 * 비즈니스 오류를 던질 때 쓴다.
 * 예) throw new GeneralException(ErrorStatus.CAREER_EXCEEDS_AGE);
 */
@Getter
public class GeneralException extends RuntimeException {

    private final ErrorStatus status;

    public GeneralException(ErrorStatus status) {
        super(status.getMessage());
        this.status = status;
    }
}
