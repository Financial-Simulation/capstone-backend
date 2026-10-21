package com.example.capstoneserver.global.response;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 오류 코드 목록. 노션 「선빈 api 명세서」의 '오류·경고 코드' 표와 같게 유지한다.
 */
@Getter
@RequiredArgsConstructor
public enum ErrorStatus {

    // 공통
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "COMMON400", "잘못된 요청입니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "COMMON404", "요청한 주소가 없어요."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON500", "서버 내부 오류입니다."),

    // 입력
    CAREER_EXCEEDS_AGE(HttpStatus.BAD_REQUEST, "INPUT4001", "경력은 나이 − 15년을 넘을 수 없어요."),

    // 대출
    LOAN_ITEMS_MISMATCH(HttpStatus.BAD_REQUEST, "LOAN4001", "'대출 없음'과 대출 목록이 서로 맞지 않아요."),

    // 목표
    GOAL_TOO_SOON(HttpStatus.BAD_REQUEST, "GOAL4001", "목표 시점은 시작 달부터 12개월 이후여야 해요."),
    GOAL_TOO_LATE(HttpStatus.BAD_REQUEST, "GOAL4002", "목표 시점은 시작 달부터 120개월 이내여야 해요."),

    // 예정된 일
    EVENT_FIELD_MISMATCH(HttpStatus.BAD_REQUEST, "EVENT4001", "예정된 일의 종류와 금액 항목이 맞지 않아요."),
    EVENT_DATE_OUT_OF_RANGE(HttpStatus.BAD_REQUEST, "EVENT4002", "예정된 일의 시점은 시작 달부터 119개월 이내여야 해요."),
    EVENT_JOB_CHANGE_OUT_OF_RANGE(HttpStatus.BAD_REQUEST, "EVENT4004", "이직 연봉 변화율은 −90~+200%, 공백은 0~24개월이어야 해요."),

    // 시뮬레이션
    PLAN_COUNT_INVALID(HttpStatus.BAD_REQUEST, "SIM4003", "비교 계획은 1~3개여야 해요."),

    // 가능성 범위 작업
    JOB_NOT_FOUND(HttpStatus.NOT_FOUND, "JOB404", "작업이 없거나 10분이 지나 삭제되었어요."),
    JOB_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "JOB4001", "진행 중인 작업이 많아요. 잠시 후 다시 시도해 주세요.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
