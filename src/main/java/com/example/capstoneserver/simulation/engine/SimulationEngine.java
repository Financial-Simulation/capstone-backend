package com.example.capstoneserver.simulation.engine;

import com.example.capstoneserver.input.dto.SimulationInput.EmploymentType;
import com.example.capstoneserver.loan.LoanCalculator;
import com.example.capstoneserver.simulation.engine.EngineParams.General;
import com.example.capstoneserver.simulation.engine.EngineParams.Policy;
import com.example.capstoneserver.simulation.engine.EngineParams.Stats;
import com.example.capstoneserver.simulation.engine.EngineResult.Flag;
import com.example.capstoneserver.simulation.engine.NoiseSource.PathNoise;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 월별 14단계 계산 (구현 명세서 5장). 파이썬 기준 구현 kernel.py v1.6, engine.js와 같은 순서·같은 수식이다.
 * 순서를 바꾸면 결과가 달라지고 정답 파일(golden.json)과 어긋난다.
 *
 * 상태가 없는 순수 함수이고 경로끼리 영향이 없어 경로 단위로 병렬 처리한다.
 * 각 경로는 결과 배열의 자기 칸에만 쓴다.
 */
@Component
@RequiredArgsConstructor
public class SimulationEngine {

    private final LoanCalculator loanCalculator;

    public EngineResult simulate(EngineProfile pr, EngineParams params, NoiseSource noise,
                                 int n, int t, double bufferFirst, boolean collect) {
        checkProfile(pr);

        Policy pol = params.policy();
        var ui = params.stats().ui();
        boolean incomeOk = pr.netIncome() * 12 * ui.grossOverNet() <= pol.grossMaxY() + 1e-6;
        boolean eligible = pr.age() <= pol.ageMax() && incomeOk;
        double a1 = eligible ? Math.min(pr.save(), pol.cap()) : 0;
        double[] loanPay = loanCalculator.monthlyPayments(pr.loans(), t);
        List<EngineEvent> events = pr.events().stream()
                .sorted(Comparator.comparingInt(EngineEvent::t))   // 안정 정렬: 같은 달은 입력 순서대로
                .toList();

        double[] saved = collect ? new double[n * (t + 1)] : null;
        byte[][] flags = new byte[Flag.values().length][n];
        int[] firstBrk = new int[n];
        double[] matchPaid = new double[n];

        IntStream.range(0, n).parallel().forEach(p -> runPath(pr, params, noise.noise(p, t), p, t, bufferFirst, incomeOk,
                a1, loanPay, events, saved, flags, firstBrk, matchPaid));

        return new EngineResult(saved, flags, firstBrk, matchPaid, eligible, n, t);
    }

    private void runPath(EngineProfile pr, EngineParams params, PathNoise nz, int p, int bigT, double bufferFirst,
                         boolean incomeOk, double a1, double[] loanPay, List<EngineEvent> events,
                         double[] saved, byte[][] flags, int[] firstBrk, double[] matchPaid) {
        Stats st = params.stats();
        Policy pol = params.policy();
        General gen = params.general();
        var pi = st.inflation();
        var wg = st.wage();
        var un = st.unemp();
        var ui = st.ui();
        var spend = st.spend();

        double age0 = pr.age();
        double exp0 = pr.expYears();
        double net0 = pr.netIncome();
        double save = pr.save();
        double thr = pr.thr();
        double invM = pr.investMonthly();
        double e = un.exitM();

        double epsSd = pi.sigmaY() * Math.sqrt(1 - pi.phi() * pi.phi());
        double sig = StrictMath.hypot(wg.macroSd(), wg.idioSd());
        double rho = sig > 0 ? wg.rhoMacro() * wg.macroSd() / sig : 0;
        double gRate = pr.generalRate() != null ? pr.generalRate() : gen.rate();
        double rOd = st.overdraft().annualRate() / 12;
        double rL = st.liquid().annualRate() / 12;
        double rP = pol.rate() / 12;
        double rG = gRate / 12;
        double uiAmt = Math.min(Math.max(ui.replace() * net0 * ui.grossOverNet(), ui.floor()), ui.cap());

        // 상태 변수 (명세서 5.2)
        double cash = pr.cash0();
        double od = 0;
        double inv = pr.invest0();
        double dep = 0;
        double p1 = 0;
        double c1 = 0;
        double m1 = 0;
        double mp = 0;
        int held = 0;
        int cumHeld = 0;
        boolean act1 = a1 > 0;
        double matchMult = 1;
        int wait = -1;
        double g = 0;
        double cg = 0;
        int gHeld = 0;
        boolean started = cash >= bufferFirst || bufferFirst <= 0;
        double base = net0;
        double incomeMult = 1;
        double cum = 1;
        double piPrev = pi.last();
        double mInf = 0;
        int unemp = 0;
        int uiLeft = 0;
        boolean uiFirst = false;
        int since = 1_000_000;
        int gapLen = 0;
        int volGap = 0;
        int lastShock = -99;
        double extraFixed = 0;
        double insured = Math.min(exp0 * 12, ui.windowM());
        double paid = 0;
        int firstBreak = -1;

        int row = p * (bigT + 1);
        if (saved != null) saved[row] = cash + p1 + g + inv + dep - od;

        for (int t = 0; t < bigT; t++) {
            double ageNow = age0 + t / 12.0;
            double expNow = exp0 + t / 12.0;

            // 1. 연초: 물가 AR(1), 연봉 인상
            if (t % 12 == 0) {
                int months = Math.min(12, bigT - t);
                double sum = 0;
                for (int j = t; j < t + months; j++) sum += nz.z1()[j];
                double inflation = pi.muY() + pi.phi() * (piPrev - pi.muY()) + epsSd * (sum / Math.sqrt(months));
                mInf = StrictMath.pow(1 + inflation, 1.0 / 12) - 1;
                piPrev = inflation;
                if (t > 0) {
                    double sum2 = 0;
                    for (int j = t - 12; j < t; j++) sum2 += nz.z1()[j];
                    double growth = wg.macroMu() + wg.expPrem()[bracket(wg.expEdges(), expNow - 1)]
                            + sig * (rho * (sum2 / Math.sqrt(12)) + Math.sqrt(1 - rho * rho) * nz.z2()[t / 12 - 1]);
                    base *= 1 + growth;
                }
            }

            // 2. 물가 누적
            cum *= 1 + mInf;

            // 3. 예정 이벤트
            double lump = 0;
            double depAdd = 0;
            for (EngineEvent x : events) {
                if (x.t() != t) continue;
                switch (x.kind()) {
                    case LUMP -> lump += x.amount();
                    case SPEND -> extraFixed += x.delta();
                    case MOVE -> {
                        depAdd += x.deposit();
                        extraFixed += x.delta();
                        lump += x.cost();
                    }
                    case INCOME -> {
                        incomeMult *= 1 + x.pct();
                        if (x.gap() > 0 && unemp == 0) volGap = x.gap();
                    }
                }
            }

            // 4. 고용
            boolean start = unemp == 0 && volGap == 0 && nz.uOnset()[t] < hazard(st, pr.employment(), ageNow);
            if (start) {
                int d = geom(nz.uDur()[t], e, bigT);
                unemp = d;
                gapLen = d;
                since = 0;
                if (nz.uInvol()[t] < un.involShare() && insured >= ui.minInsuredM()) {
                    uiLeft = uiMonths(st, expNow);
                    uiFirst = true;
                } else {
                    uiLeft = 0;
                }
            }
            if (volGap > 0) {
                unemp = Math.max(unemp, volGap);
                gapLen = volGap;
                since = 0;
            }
            volGap = 0;
            boolean employed = unemp == 0;
            if (!employed) flags[Flag.UNEMP.ordinal()][p] = 1;
            double income = employed
                    ? base * incomeMult
                    : (uiLeft > 0 ? uiAmt * (uiFirst ? 1 - ui.waitingFrac() : 1) : 0);

            // 5. 월초 이자 (마이너스·파킹통장). 적금 이자는 납입 뒤
            od *= 1 + rOd;
            cash *= 1 + rL;

            // 6. 급전 (금액은 물가 반영)
            double shock = 0;
            if (nz.uShock()[t] < st.shock().probM()) {
                shock = pick(nz.uAmt()[t], st.shock().amounts()) * cum;
                lastShock = t;
            }

            // 7. 현금 흐름 (실직 중 소비 감소)
            double lifestyle = spend.raiseShare() * Math.max(base * incomeMult - net0 * cum, 0);
            double cut = employed ? 0 : (uiLeft > 0 ? spend.unempCutUi() : spend.unempCutNoui());
            double living = ((pr.fixed() + pr.variable() + extraFixed) * cum + lifestyle) * (1 - cut);
            cash = cash + income - living - shock - lump - loanPay[t] - depAdd;
            dep += depAdd;

            // 8. 마이너스 우선 상환
            double rep = Math.min(Math.max(cash, 0), od);
            cash -= rep;
            od -= rep;

            // 9. 재가입 → 납입 → 적금 이자
            if (pol.rejoin()) {
                if (wait > 0) wait -= 1;
                if (wait == 0 && ageNow <= pol.ageMax() && incomeOk && cumHeld < pol.term()) {
                    act1 = true;
                    held = 0;
                    flags[Flag.REJOIN.ordinal()][p] = 1;
                    matchMult = (double) (pol.term() - cumHeld) / pol.term();
                    wait = -1;
                }
            }
            started = started || cash >= bufferFirst;
            boolean can = employed && started;

            double put1 = (can && act1) ? Math.min(a1, Math.max(cash, 0)) : 0;
            cash -= put1;
            p1 += put1;
            c1 += put1;
            double mNew = Math.min(pol.match() * put1, pol.matchCap()) * matchMult;
            m1 += mNew;
            mp += mNew;

            double rest = act1 ? save - a1 : save;
            double putG = can ? Math.min(rest, Math.max(cash, 0)) : 0;
            cash -= putG;
            g += putG;
            cg += putG;

            double putI = employed ? Math.min(invM, Math.max(cash, 0)) : 0;
            cash -= putI;
            inv += putI;

            if (act1) {
                p1 += c1 * rP;
                m1 += mp * rP;
            }
            if (cg > 0) {
                g += cg * rG;
                gHeld += 1;
                if (gHeld >= 12) {
                    cg += (g - cg) * (1 - gen.tax());
                    g = cg;
                    gHeld = 0;
                }
            } else {
                gHeld = 0;
            }

            // 10. 만기
            if (act1) {
                held += 1;
                cumHeld += 1;
            }
            if (act1 && held >= pol.term()) {
                paid += m1;
                cash += p1 + m1;
                p1 = 0;
                c1 = 0;
                m1 = 0;
                mp = 0;
                flags[Flag.MATURE1.ordinal()][p] = 1;
                act1 = false;
            }

            // 11. 부족분 → 마이너스
            double deficit = Math.max(-cash, 0);
            od += deficit;
            cash = Math.max(cash, 0);
            if (deficit > 0) flags[Flag.OVERDRAFT.ordinal()][p] = 1;

            // 12. 해지: 일반 적금 먼저, 그다음 청년미래적금
            if (od > thr && g > 0) {
                double prG = cg + (g - cg) * gen.earlyRatio() * (1 - gen.tax());
                double r2 = Math.min(prG, od);
                od -= r2;
                cash += prG - r2;
                g = 0;
                cg = 0;
                gHeld = 0;
                flags[Flag.BRK_G.ordinal()][p] = 1;
            }
            if (act1 && od > thr) {
                boolean special = since <= pol.window();
                double pr1 = special ? p1 + m1 : c1 + (p1 - c1) * earlyRatio(pol, held) * (1 - pol.tax());
                if (special) paid += m1;
                double r3 = Math.min(pr1, od);
                od -= r3;
                cash += pr1 - r3;
                p1 = 0;
                c1 = 0;
                m1 = 0;
                mp = 0;
                act1 = false;
                wait = pol.rejoinWait();
                if (firstBreak < 0) {
                    firstBreak = t;
                    Flag cause = special ? Flag.C1_SP
                            : !employed ? Flag.C1_UNEMP
                            : t - lastShock <= 2 ? Flag.C1_SHOCK
                            : Flag.C1_DEFICIT;
                    flags[cause.ordinal()][p] = 1;
                }
                flags[(special ? Flag.BRK1_SP : Flag.BRK1_GEN).ordinal()][p] = 1;
            }
            if (od > thr) flags[Flag.LIMIT.ordinal()][p] = 1;

            // 13. 카운터 + 재취업 임금 손실
            if (!employed && unemp == 1) base *= Math.max(1 - un.scarPerYear() * gapLen / 12, 0.5);
            insured = employed ? Math.min(insured + 1, ui.windowM()) : Math.max(insured - 1, 0);
            unemp = Math.max(unemp - 1, 0);
            if (!employed) {
                uiLeft = Math.max(uiLeft - 1, 0);
                uiFirst = false;
            }
            since += 1;

            // 14. 기록 (대출 잔액은 빼지 않는다)
            if (saved != null) saved[row + t + 1] = cash + p1 + g + inv + dep - od;
        }

        firstBrk[p] = firstBreak;
        matchPaid[p] = paid;
    }

    // ===== 도우미 (명세서 5.4) =====

    /** edges 중 x 이하인 경계의 개수 (numpy searchsorted side="right") */
    private static int bracket(double[] edges, double x) {
        int i = 0;
        while (i < edges.length && x >= edges[i]) i++;
        return i;
    }

    private static double hazard(Stats st, EmploymentType employment, double age) {
        var un = st.unemp();
        double u = un.uByAge()[bracket(un.ageEdges(), age)];
        double h = u * un.exitM() / (1 - u);
        double c = un.contractSharePop();
        double r = un.contractRatio();
        double base = 1 / ((1 - c) + c * r);
        return h * (employment == EmploymentType.CONTRACT ? base * r : base);
    }

    private static int uiMonths(Stats st, double tenure) {
        return (int) Math.round(st.ui().days()[bracket(st.ui().daysEdges(), tenure)] / 30);
    }

    private static int geom(double u, double e, int cap) {
        double logged = StrictMath.log(Math.min(Math.max(1 - u, 1e-12), 1)) / StrictMath.log(1 - e);
        return (int) Math.min(1 + Math.floor(logged), cap);
    }

    private static double pick(double u, double[][] pmf) {
        double[] cumulative = new double[pmf.length];
        double c = 0;
        for (int i = 0; i < pmf.length; i++) {
            c += pmf[i][1];
            cumulative[i] = c;
        }
        return pmf[Math.min(bracket(cumulative, u), pmf.length - 1)][0];
    }

    private static double earlyRatio(Policy pol, int held) {
        double ratio = 0;
        for (double[] row : pol.earlyTable()) {
            if (held >= row[0]) ratio = row[1];
        }
        double eff = held < 3
                ? pol.earlyUnder3()
                : Math.max(pol.rate() * ratio * held / pol.term(), pol.earlyFloor());
        return eff / pol.rate();
    }

    /** 잘못된 값 하나가 2,000개 경로 전체를 오염시키지 않도록 입구에서 막는다 (명세서 8.5) */
    private static void checkProfile(EngineProfile pr) {
        double[] amounts = {pr.age(), pr.expYears(), pr.netIncome(), pr.fixed(), pr.variable(), pr.cash0(), pr.invest0(),
                pr.save(), pr.investMonthly(), pr.thr()};
        for (double v : amounts) {
            if (!Double.isFinite(v) || v < 0) {
                throw new IllegalArgumentException("엔진 입력 값이 잘못됐어요");
            }
        }
        for (EngineLoan loan : pr.loans()) {
            if (loan.months() < 1 || !Double.isFinite(loan.balance()) || loan.balance() < 0) {
                throw new IllegalArgumentException("엔진 대출 값이 잘못됐어요");
            }
        }
    }
}
