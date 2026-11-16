package com.example.capstoneserver.simulation.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * 미래 하나를 달마다 기록한 것 (데모 '미래 다시 보기'). SimulationEngine.trace가 simulate와 같은 runPath로 채운다.
 * 계산에는 쓰지 않고 읽기만 하므로 기록을 켜도 결과(saved)는 같다. 금액 단위는 만 원.
 *
 * @param months 0~T−1달 (months.get(t)는 t+1번째 달 말의 상태)
 */
public record PathTrace(int path, List<Month> months) {

    /** 그달 일어난 일 (비트) */
    public static final int BREAK_GENERAL = 1;
    public static final int BREAK_HOUSING = 1 << 1;
    public static final int BREAK_POLICY = 1 << 2;
    /** 청년미래 특별해지 (실직 등 사유가 있어 지원금을 받고 깸) */
    public static final int BREAK_POLICY_SPECIAL = 1 << 3;
    public static final int BREAK_PENSION = 1 << 4;
    public static final int MATURE_POLICY = 1 << 5;
    public static final int REJOIN_POLICY = 1 << 6;
    /** 그달 현금이 모자라 마이너스(빌린 돈)가 생기거나 늘었다 */
    public static final int OVERDRAFT = 1 << 7;
    /** 그달 새로 실직했다 */
    public static final int JOB_LOSS = 1 << 8;
    /** 재가입한 청년미래 계좌가 비어 있는 채로 해지됐다 (받은 돈 없음, 화면에 사건으로 보이지 않는다) */
    public static final int POLICY_RESET = 1 << 9;

    /**
     * 한 달 (만 원).
     *
     * @param salary    실직이 아닐 때 받는 월급 (연봉 인상 · 재취업 손실 반영)
     * @param income    실제로 들어온 돈 (실직이면 실업급여 또는 0)
     * @param living    생활비 (고정 + 변동, 물가 반영, 실직 중 감소)
     * @param shock     급한 지출
     * @param planned   예정된 일 비용 (입력의 예정 이벤트: 일시금 · 이사 비용 · 보증금)
     * @param loan      대출 상환
     * @param refund    연말정산 환급 (2월)
     * @param subsidy   그달 쌓인 청년미래 정부 지원금
     * @param received  적금 · 청약 · 연금을 깨서 받은 돈
     * @param taxPaid   그달 낸 세금 (이자소득세 · 청약 추징 · 연금 기타소득세)
     * @param inflation 그해 물가 상승률 (연, 소수)
     * @param total     모은 돈 (화면 그래프와 같은 값)
     */
    public record Month(int t, boolean employed, boolean unemploymentBenefit, int events,
                        double salary, double income, double living, double shock, double planned, double loan,
                        double refund, double subsidy, double received, double taxPaid,
                        double putPolicy, double putHousing, double putPension, double putGeneral, double putInvest,
                        double cash, double overdraft, double policy, double housing, double pension,
                        double general, double invest, double deposit, double total, double inflation) {

        public boolean has(int event) {
            return (events & event) != 0;
        }
    }

    /** runPath가 달마다 한 줄씩 넣는다 */
    static final class Recorder {
        final List<Month> months = new ArrayList<>();

        void add(Month month) {
            months.add(month);
        }
    }
}
