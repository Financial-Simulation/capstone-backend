package com.example.capstoneserver.simulation;

import com.example.capstoneserver.simulation.engine.EngineProfile;

/**
 * 엔진 단위의 계획 하나.
 *
 * @param goal        목표 금액 (만 원)
 * @param goalMonths  시작 달부터 목표 시점까지 개월 수
 * @param bufferFirst 비상금 먼저 모으기 (만 원, 0이면 끔)
 */
public record EnginePlan(EngineProfile profile, double goal, int goalMonths, double bufferFirst) {

    public EnginePlan withProfile(EngineProfile newProfile) {
        return new EnginePlan(newProfile, goal, goalMonths, bufferFirst);
    }

    public EnginePlan withGoal(double newGoal) {
        return new EnginePlan(profile, newGoal, goalMonths, bufferFirst);
    }

    public EnginePlan withGoalMonths(int newGoalMonths) {
        return new EnginePlan(profile, goal, newGoalMonths, bufferFirst);
    }

    public EnginePlan withBufferFirst(double newBufferFirst) {
        return new EnginePlan(profile, goal, goalMonths, newBufferFirst);
    }
}
