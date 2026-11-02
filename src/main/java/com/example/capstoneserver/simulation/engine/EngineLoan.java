package com.example.capstoneserver.simulation.engine;

import com.example.capstoneserver.input.dto.RepaymentType;

/**
 * 엔진 단위의 대출 한 건.
 *
 * @param balance 남은 원금 (만 원)
 * @param rate    연 이자율 (소수)
 * @param months  남은 기간 (개월)
 */
public record EngineLoan(double balance, double rate, int months, RepaymentType method) {}
