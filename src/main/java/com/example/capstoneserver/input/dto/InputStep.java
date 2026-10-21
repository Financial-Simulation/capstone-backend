package com.example.capstoneserver.input.dto;

import jakarta.validation.groups.Default;
import java.util.ArrayList;
import java.util.List;

/**
 * 입력 화면 단계. 와이어플로 01-1 → 01-2 → 02 → 03 순서.
 * 각 단계는 자기 화면의 '필수 칸' 꼬리표(group)를 가진다.
 */
public enum InputStep {

    BASIC(Basic.class),     // 01-1 나이·소득·지출
    ASSETS(Assets.class),   // 01-2 자산·저축·대출
    GOAL(Goal.class),       // 02   목표
    EVENTS(Events.class),   // 03   예정된 일
    ALL(null);              // 전부

    // 필수 칸 꼬리표. @NotNull(groups = InputStep.Basic.class) 처럼 쓴다.
    public interface Basic {}
    public interface Assets {}
    public interface Goal {}
    public interface Events {}

    private final Class<?> group;

    InputStep(Class<?> group) {
        this.group = group;
    }

    /**
     * 이 단계까지 검사할 꼬리표 목록 (누적).
     * 예) ASSETS → [Default, Basic, Assets]
     * Default는 범위 검사(@Min, @Max 등)라서 항상 포함한다. 빈 칸은 범위 검사를 통과한다.
     */
    public Class<?>[] groupsUpToHere() {
        List<Class<?>> groups = new ArrayList<>();
        groups.add(Default.class);
        for (InputStep step : values()) {
            if (step.group != null) {
                groups.add(step.group);
            }
            if (step == this) {
                break;
            }
        }
        return groups.toArray(new Class<?>[0]);
    }
}
