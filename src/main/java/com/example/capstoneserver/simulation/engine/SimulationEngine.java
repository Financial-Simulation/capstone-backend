package com.example.capstoneserver.simulation.engine;

import com.example.capstoneserver.input.dto.SimulationInput.EmploymentType;
import com.example.capstoneserver.loan.LoanCalculator;
import com.example.capstoneserver.simulation.engine.EngineParams.General;
import com.example.capstoneserver.simulation.engine.EngineParams.Housing;
import com.example.capstoneserver.simulation.engine.EngineParams.Pension;
import com.example.capstoneserver.simulation.engine.EngineParams.Policy;
import com.example.capstoneserver.simulation.engine.EngineParams.Stats;
import com.example.capstoneserver.simulation.engine.EngineResult.Allocation;
import com.example.capstoneserver.simulation.engine.EngineResult.Flag;
import com.example.capstoneserver.simulation.engine.EngineResult.TaxItem;
import com.example.capstoneserver.simulation.engine.NoiseSource.PathNoise;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 월별 계산 (구현 명세서 5장 + 세금 모듈). 파이썬 기준 구현 kernel.py v1.7, engine.js와 같은 순서·같은 수식이다.
 * 순서를 바꾸면 결과가 달라지고 정답 파일(golden.json)과 어긋난다.
 *
 * 세금 모듈: 매년 2월 연말정산 환급, 주택청약·연금저축 계좌, 해지할 때의 이자소득세·추징·기타소득세.
 * 세전 연봉·월세·청약·연금저축이 모두 없으면 세금 계산을 건너뛰고 v1.6과 같은 결과를 낸다.
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
        Housing hou = params.housing();
        var ui = params.stats().ui();
        double grossY = pr.grossY() != null ? pr.grossY() : pr.netIncome() * 12 * ui.grossOverNet();
        boolean incomeOk = grossY <= pol.grossMaxY() + 1e-6;
        boolean eligible = pr.age() <= pol.ageMax() && incomeOk;
        double a1 = eligible ? Math.min(pr.policyM() != null ? pr.policyM() : pr.save(), pol.cap()) : 0;
        // 배분 합이 저축액을 넘으면 뒤 상품(청약 → 연금저축)을 줄인다
        double hm = Math.min(pr.housingM(), Math.max(pr.save() - a1, 0));
        double pm = Math.min(pr.pensionM(), Math.max(pr.save() - a1 - hm, 0));
        boolean dream = pr.age() <= hou.dreamAge() && grossY <= hou.dreamGrossY() + 1e-6;
        Setup setup = new Setup(grossY, incomeOk, grossY <= pol.matchGrossMaxY() + 1e-6 ? 1 : 0, a1, hm, pm,
                hm > 0 || pm > 0, hm > 0 || pm > 0 || pr.rent() > 0, dream,
                dream && pr.homeless() && grossY <= hou.taxfreeGrossY() + 1e-6);
        double[] loanPay = loanCalculator.monthlyPayments(pr.loans(), t);
        List<EngineEvent> events = pr.events().stream()
                .sorted(Comparator.comparingInt(EngineEvent::t))   // 안정 정렬: 같은 달은 입력 순서대로
                .toList();

        double[] saved = collect ? new double[n * (t + 1)] : null;
        byte[][] flags = new byte[Flag.values().length][n];
        int[] firstBrk = new int[n];
        double[] matchPaid = new double[n];
        double[][] tax = new double[TaxItem.values().length][n];

        IntStream.range(0, n).parallel().forEach(p -> runPath(pr, params, noise.noise(p, t), p, t, bufferFirst, setup,
                loanPay, events, saved, flags, firstBrk, matchPaid, tax));

        return new EngineResult(saved, flags, firstBrk, matchPaid, tax, new Allocation(a1, hm, pm), eligible, n, t);
    }

    /**
     * 프로필마다 한 번 정하는 값.
     *
     * @param matchOk  정부 지원금을 받는지 (1 또는 0, 총급여 6,000만 이하)
     * @param a1       청년미래 월 납입, hm·pm은 청약·연금저축 월 납입 (저축액 안으로 줄인 값)
     * @param prodOn   청약이나 연금저축이 있음, taxOn은 거기에 월세까지 (연말정산 계산 여부)
     * @param dream    청년 주택드림 청약 (만 34세 이하, 총급여 5,000만 이하)
     * @param taxfree  주택드림 이자 비과세 대상 (무주택 세대주, 총급여 3,600만 이하)
     */
    private record Setup(double grossY, boolean incomeOk, double matchOk, double a1, double hm, double pm,
                         boolean prodOn, boolean taxOn, boolean dream, boolean taxfree) {}

    /** 청약 계좌 상태 (경로 하나). cH 원금, dM 원금×개월 누계, held 가입 개월, saved 공제로 줄어든 세금 누계, clawBase 추징 기준 납입 누계 */
    private static final class HousingAccount {
        double cH, dM, saved, clawBase;
        int held;
        boolean clawOn;
    }

    private void runPath(EngineProfile pr, EngineParams params, PathNoise nz, int p, int bigT, double bufferFirst,
                         Setup su, double[] loanPay, List<EngineEvent> events,
                         double[] saved, byte[][] flags, int[] firstBrk, double[] matchPaid, double[][] tax) {
        Stats st = params.stats();
        Policy pol = params.policy();
        General gen = params.general();
        Housing hou = params.housing();
        Pension pen = params.pension();
        boolean incomeOk = su.incomeOk();
        double a1 = su.a1();
        double hm = su.hm();
        double pm = su.pm();
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

        // 청약·연금저축·연말정산 누적값
        boolean hact = hm > 0;
        boolean pact = pm > 0;
        double pb = 0;          // 연금저축 잔액
        double pcT = 0;         // 연금저축 납입 원금
        double pcC = 0;         // 그중 세액공제 받은 원금
        double rPen = gen.rate() / 12;
        HousingAccount hs = new HousingAccount();
        double[] acc = new double[5];           // 그해 누적: 총급여, 일한 달, 월세, 청약 납입, 연금 납입
        double[] prevAcc = null;
        boolean accHousingCancelled = false;
        boolean prevHousingCancelled = false;
        int itRefund = TaxItem.REFUND.ordinal();
        int itInterest = TaxItem.INTEREST_TAX.ordinal();
        int itClaw = TaxItem.CLAWBACK.ordinal();
        int itPension = TaxItem.PENSION_TAX.ordinal();

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

            // 2-b. 연말정산: 1월에 직전 해 누적값을 묶고, 2월에 '상품·월세가 없을 때 세금 − 있을 때 세금'을 돌려받는다
            double refund = Double.NaN;
            if (su.taxOn()) {
                int calendar = (pr.startMonth() - 1 + t) % 12;
                if (calendar == 0) {
                    prevAcc = acc;
                    prevHousingCancelled = accHousingCancelled;
                    acc = new double[5];
                    accHousingCancelled = false;
                } else if (calendar == 1 && prevAcc != null) {
                    double[] a = prevAcc;
                    prevAcc = null;
                    double gross = a[0] * 1e4;
                    double[] ins = YearEndTax.insurance(params.insurance(), gross, a[1]);
                    double housingPaid = (prevHousingCancelled ? 0 : a[3]) * 1e4;   // 그해 청약을 깼으면 공제 없음
                    double pensionPaid = a[4] * 1e4;
                    double rentPaid = a[2] * 1e4;
                    boolean homeless = pr.homeless();
                    double d0 = YearEndTax.payable(YearEndTax.decided(gross, ins[0], ins[1], 0, 0, 0, homeless));
                    double d1 = YearEndTax.payable(YearEndTax.decided(gross, ins[0], ins[1], housingPaid, pensionPaid, rentPaid, homeless));
                    double noHousing = YearEndTax.payable(YearEndTax.decided(gross, ins[0], ins[1], 0, pensionPaid, rentPaid, homeless));
                    double noPension = YearEndTax.payable(YearEndTax.decided(gross, ins[0], ins[1], housingPaid, 0, rentPaid, homeless));
                    refund = Math.max(d0 - d1, 0) / 1e4;
                    tax[itRefund][p] += refund;
                    double housingGain = hact ? Math.max(noHousing - d1, 0) / 1e4 : 0;      // 청약 공제로 줄어든 세금 (추징 한도)
                    hs.saved += housingGain;
                    hs.clawOn = hs.clawOn || housingGain > 0;
                    hs.clawBase += (hs.clawOn && hact) ? Math.min(a[3], hou.clawCapY()) : 0;
                    // 연금: 명목 공제액 중 실제로 줄어든 세금 비율만큼만 '공제받은 원금'으로 본다 (인출 시 과세 대상)
                    double nominal = Math.min(a[4], pen.creditCapY()) * (gross <= 5500e4 ? 0.15 : 0.12) * 1.1 * 1e4;
                    double frac = nominal > 0 ? Math.min(Math.max((noPension - d1) / nominal, 0), 1) : 0;
                    if (pact) pcC = Math.min(pcC + Math.min(a[4], pen.creditCapY()) * frac, pcT);
                }
            }

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
            if (su.taxOn()) {   // 그해 실제 총급여 (일한 달만, 실업급여는 비과세)
                acc[0] += employed ? su.grossY() / 12 * (net0 > 0 ? (base * incomeMult) / net0 : 0) : 0;
                acc[1] += employed ? 1 : 0;
                acc[2] += pr.rent() * cum;
            }
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
            if (!Double.isNaN(refund)) cash += refund;

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
            double mNew = Math.min(pol.match() * put1, pol.matchCap()) * matchMult * su.matchOk();
            m1 += mNew;
            mp += mNew;

            double rest = act1 ? save - a1 : save;
            if (su.prodOn()) {   // 청약 → 연금저축 → 일반 적금 순서로 채운다
                double putH = (can && hact) ? Math.min(hm, Math.max(cash, 0)) : 0;
                cash -= putH;
                acc[3] += putH;
                hs.cH = hs.cH + putH;
                double putP = (can && pact) ? Math.min(pm, Math.max(cash, 0)) : 0;
                cash -= putP;
                pb += putP;
                pcT += putP;
                acc[4] += putP;
                rest = Math.max(rest - (hact ? hm : 0) - (pact ? pm : 0), 0);
            }
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
                    tax[itInterest][p] += (g - cg) * gen.tax();
                    cg += (g - cg) * (1 - gen.tax());
                    g = cg;
                    gHeld = 0;
                }
            } else {
                gHeld = 0;
            }
            if (su.prodOn()) {   // 청약: 원금×개월 누계 (입금건별 단리, 이율은 해지 때 정함), 연금저축: 월 복리
                if (hact) {
                    hs.dM = hs.dM + hs.cH;
                    hs.held += 1;
                }
                if (pact) pb = pb * (1 + rPen);
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
                tax[itInterest][p] += (g - cg) * gen.earlyRatio() * gen.tax();
                double r2 = Math.min(prG, od);
                od -= r2;
                cash += prG - r2;
                g = 0;
                cg = 0;
                gHeld = 0;
                flags[Flag.BRK_G.ordinal()][p] = 1;
            }
            if (su.prodOn() && hact && od > thr && hs.cH > 0) {   // 청약 해지 (일반 적금 다음)
                double[] exit = housingExit(hou, hs, su, since <= pol.window(), acc[3]);
                double prH = hs.cH + exit[0] - exit[1] - exit[2];
                tax[itInterest][p] += exit[1];
                tax[itClaw][p] += exit[2];
                double r4 = Math.min(prH, od);
                od -= r4;
                cash += prH - r4;
                hs.cH = 0;
                hs.dM = 0;
                hact = false;
                flags[Flag.BRK_H.ordinal()][p] = 1;
                accHousingCancelled = true;   // 그해 납입분은 공제에서 빠진다
                acc[3] = 0;
            }
            if (act1 && od > thr) {
                boolean special = since <= pol.window();
                double pr1;
                if (special) {
                    pr1 = p1 + m1;
                    paid += m1;
                } else {
                    double er = earlyRatio(pol, held);
                    pr1 = c1 + (p1 - c1) * er * (1 - pol.tax());
                    tax[itInterest][p] += (p1 - c1) * er * pol.tax();
                }
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
            if (su.prodOn() && pact && od > thr && pb > 0) {   // 연금저축 해지 (마지막)
                double pTax = pensionExitTax(pen, pb, pcT, pcC);
                double prP = pb - pTax;
                tax[itPension][p] += pTax;
                double r5 = Math.min(prP, od);
                od -= r5;
                cash += prP - r5;
                pb = 0;
                pcT = 0;
                pcC = 0;
                pact = false;
                flags[Flag.BRK_PEN.ordinal()][p] = 1;
                acc[4] = 0;
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

            // 14. 기록 (대출 잔액은 빼지 않는다). 청약·연금은 지금 해지하면 받는 세후 금액으로 센다
            if (saved != null) {
                double v = cash + p1 + g + inv + dep - od;
                if (su.prodOn()) {
                    double[] exit = housingExit(hou, hs, su, since <= pol.window(), acc[3]);
                    v += (hs.cH + exit[0] - exit[1] - exit[2]) + (pb - pensionExitTax(pen, pb, pcT, pcC));
                }
                saved[row + t + 1] = v;
            }
        }

        firstBrk[p] = firstBreak;
        matchPaid[p] = paid;
    }

    // ===== 도우미 (명세서 5.4) =====

    /** 해지 시점 가입기간에 해당하는 약정이율 (1개월 이내 0, 1년 미만, 2년 미만, 2년 이상) */
    private static double housingRate(Housing hou, int held, boolean dream) {
        double[] e = hou.edges();
        int i = held <= e[0] ? 0 : held < e[1] ? 1 : held < e[2] ? 2 : 3;
        return dream ? hou.dreamRates()[i] : hou.rates()[i];
    }

    /**
     * 청약을 지금 해지하면 {이자, 이자소득세, 추징} (만 원).
     * 추징 = min(공제받은 해 이후 납입 누계(연 300만 한도) × 6.6%, 실제로 줄어든 세금). 퇴직 6개월 안(exempt)이면 면제.
     */
    private static double[] housingExit(Housing hou, HousingAccount hs, Setup su, boolean exempt, double thisYearPaid) {
        double r = housingRate(hou, hs.held, su.dream());
        double share = (su.dream() && hs.held >= 24 && hs.cH > hou.dreamPrincipalCap())
                ? hou.dreamPrincipalCap() / Math.max(hs.cH, 1e-9) : 1.0;   // 4.5%는 원금 5,000만까지
        double interest = hs.dM / 12 * (r * share + hou.rates()[3] * (1 - share) * (hs.held >= 24 ? 1 : 0));
        double free = (su.taxfree() && (hs.held >= hou.taxfreeMonths() || exempt)) ? hou.taxfreeInterest() : 0;
        double itax = Math.max(interest - free, 0) * hou.tax();
        double clawBase = hs.clawBase + (hs.clawOn ? Math.min(thisYearPaid, hou.clawCapY()) : 0);
        double claw = (hs.held < hou.clawMonths() && !exempt) ? Math.min(hou.clawRate() * clawBase, hs.saved) : 0;
        return new double[] {interest, itax, claw};
    }

    /** 연금저축을 55세 전에 꺼낼 때 기타소득세: (공제받은 원금 + 운용수익) × 16.5% */
    private static double pensionExitTax(Pension pen, double balance, double paidIn, double credited) {
        return (credited + Math.max(balance - paidIn, 0)) * pen.earlyTax();
    }

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
        double[] products = {pr.rent(), pr.housingM(), pr.pensionM(), pr.policyM() == null ? 0 : pr.policyM(),
                pr.grossY() == null ? 0 : pr.grossY()};
        for (double v : products) {
            if (!Double.isFinite(v) || v < 0) {
                throw new IllegalArgumentException("엔진 세금·상품 값이 잘못됐어요");
            }
        }
        if (pr.startMonth() < 1 || pr.startMonth() > 12) {
            throw new IllegalArgumentException("엔진 시작 달이 잘못됐어요");
        }
        for (EngineLoan loan : pr.loans()) {
            if (loan.months() < 1 || !Double.isFinite(loan.balance()) || loan.balance() < 0) {
                throw new IllegalArgumentException("엔진 대출 값이 잘못됐어요");
            }
        }
    }
}
