package com.example.capstoneserver.simulation;

import com.example.capstoneserver.simulation.engine.EngineResult;
import com.example.capstoneserver.simulation.engine.EngineResult.Flag;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 계획 하나를 돌린 결과와 공통 집계 (구현 명세서 7.1~7.2).
 *
 * @param main   본 계산 R (T = 120, 재가입 있음)
 * @param first36 첫 계좌 계산 F36. 확률만 필요한 탐색에서는 null
 * @param first48 첫 계좌 계산 F48. 확률만 필요한 탐색에서는 null
 */
record PlanRun(EnginePlan plan, EngineResult main, EngineResult first36, EngineResult first48) {

    private static final int NEVER = Integer.MAX_VALUE;

    int paths() {
        return main.n();
    }

    boolean hit(int p) {
        return main.at(p, plan.goalMonths()) >= plan.goal();
    }

    int hits() {
        int count = 0;
        for (int p = 0; p < paths(); p++) {
            if (hit(p)) count++;
        }
        return count;
    }

    /** 그 달의 분위수: 정렬한 뒤 min(N − 1, ⌊q × N⌋)번째 값. 보간하지 않는다 */
    double quantile(int month, double q) {
        return sortedAt(month)[Math.min(paths() - 1, (int) Math.floor(q * paths()))];
    }

    double[] sortedAt(int month) {
        double[] values = new double[paths()];
        for (int p = 0; p < paths(); p++) values[p] = main.at(p, month);
        Arrays.sort(values);
        return values;
    }

    /** 달성 비율이 처음 tau 이상이 되는 달 (1개월부터). 없으면 null */
    Integer reach(double tau) {
        for (int m = 1; m <= main.t(); m++) {
            int count = 0;
            for (int p = 0; p < paths(); p++) {
                if (main.at(p, m) >= plan.goal()) count++;
            }
            if ((double) count / paths() >= tau) return m;
        }
        return null;
    }

    /** 경로별로 처음 목표에 닿는 달의 중앙값 (0개월부터, 못 닿는 경로 포함). 중앙값이 '못 닿음'이면 null */
    Integer medianReach() {
        List<Integer> firsts = new ArrayList<>(paths());
        for (int p = 0; p < paths(); p++) {
            int first = NEVER;
            for (int m = 0; m <= main.t(); m++) {
                if (main.at(p, m) >= plan.goal()) {
                    first = m;
                    break;
                }
            }
            firsts.add(first);
        }
        int median = lowMedian(firsts);
        return median == NEVER ? null : median;
    }

    /** 목표 시점까지 처음 0 아래로 내려간 달 (그런 경로만) */
    List<Integer> firstNegatives() {
        List<Integer> firsts = new ArrayList<>();
        for (int p = 0; p < paths(); p++) {
            for (int m = 1; m <= plan.goalMonths(); m++) {
                if (main.at(p, m) < 0) {
                    firsts.add(m);
                    break;
                }
            }
        }
        return firsts;
    }

    int earlyTerminations() {
        return first36.count(Flag.BRK1_GEN);
    }

    double expectedSubsidy() {
        double sum = 0;
        for (double paid : first48.matchPaid()) sum += paid;
        return sum / first48.n();
    }

    /** 오름차순 정렬 후 ⌊(n − 1) / 2⌋번째 (낮은 쪽 중앙값) */
    static int lowMedian(List<Integer> values) {
        List<Integer> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return sorted.get((sorted.size() - 1) / 2);
    }
}
