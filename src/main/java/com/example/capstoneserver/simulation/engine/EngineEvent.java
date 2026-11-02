package com.example.capstoneserver.simulation.engine;

/** 엔진 단위의 예정된 일 (구현 명세서 2.2). t는 시작 달부터 센 개월, 금액은 만 원. */
public record EngineEvent(int t, Kind kind, double amount, double delta, double pct, int gap, double deposit, double cost) {

    public enum Kind { LUMP, SPEND, INCOME, MOVE }

    public static EngineEvent lump(int t, double amount) {
        return new EngineEvent(t, Kind.LUMP, amount, 0, 0, 0, 0, 0);
    }

    public static EngineEvent spend(int t, double delta) {
        return new EngineEvent(t, Kind.SPEND, 0, delta, 0, 0, 0, 0);
    }

    public static EngineEvent income(int t, double pct, int gap) {
        return new EngineEvent(t, Kind.INCOME, 0, 0, pct, gap, 0, 0);
    }

    public static EngineEvent move(int t, double deposit, double delta, double cost) {
        return new EngineEvent(t, Kind.MOVE, 0, delta, 0, 0, deposit, cost);
    }
}
