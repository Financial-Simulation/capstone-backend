package com.example.capstoneserver.global.response;

/**
 * 계산을 막지 않는 경고·안내. result.warnings[] 에 담긴다.
 * { "code": "WARN_MONTHLY_SHORTFALL", "message": "사용자에게 보여 줄 문장" }
 */
public record Warning(String code, String message) {}
