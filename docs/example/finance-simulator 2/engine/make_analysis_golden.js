/* 분석 정답 파일 생성: 구현 명세서 2·7장의 정의 그대로, API 응답 단위(원, %)로 계산해 analysis_golden.json에 저장
   사용: node make_analysis_golden.js   (엔진 = engine.js, 난수 = sfc32, N = 2,000, seed = 20261007) */
const fs = require("fs");
const E = require("./engine.js");
const N = 2000, SEED = 20261007, T = 120, TAU = 0.70;
const FIRST = { ...E.POLICY, rejoin: false };
const noise = E.randomNoise(SEED, N);

// ── 입력 예시 (API SimulationInput, 원 단위)
const base = (o) => ({ startMonth: "2026-11", person: { age: 27, careerYears: 2, employmentType: "REGULAR" },
  income: { monthlyNetIncome: 2500000 }, expenses: { fixed: { inputMode: "TOTAL", items: [], total: 950000 }, variable: { inputMode: "TOTAL", items: [], total: 950000 } },
  assets: { cash: 2000000, deposits: 0, investments: 0 }, savingPlan: { monthlySaving: 500000, generalSavingRate: 3.03, monthlyInvestment: 0 },
  emergency: { breakThreshold: 500000 }, loans: { hasNoLoan: true, items: [] }, goal: { name: "목표", targetAmount: 30000000, targetDate: "2031-11" }, events: [], ...o });
const exp = (f, v) => ({ fixed: { inputMode: "TOTAL", items: [], total: f }, variable: { inputMode: "TOTAL", items: [], total: v } });
const CASES = [
  { key: "S1", note: "API 명세 예시 사용자 S1 (값이 명세 예시와 같아야 함)", input: base({
      loans: { hasNoLoan: false, items: [{ name: "학자금대출", loanType: "STUDENT", principal: 12000000, annualRate: 1.9, remainingMonths: 96, repaymentType: "EQUAL_PAYMENT" }] },
      goal: { name: "5년 뒤 3,000만 원", targetAmount: 30000000, targetDate: "2031-11" } }) },
  { key: "S2", note: "24세 첫 직장, 모은 돈 0원, 3년 목표", input: base({ person: { age: 24, careerYears: 0, employmentType: "REGULAR" },
      income: { monthlyNetIncome: 2300000 }, expenses: exp(850000, 800000), assets: { cash: 0, deposits: 0, investments: 0 },
      loans: { hasNoLoan: false, items: [{ name: "학자금대출", loanType: "STUDENT", principal: 8000000, annualRate: 1.7, remainingMonths: 120, repaymentType: "EQUAL_PAYMENT" }] },
      goal: { name: "3년 뒤 1,500만 원", targetAmount: 15000000, targetDate: "2029-11" } }) },
  { key: "S3", note: "36세 계약직, 1년 뒤 전세 이사 (청년미래적금 비대상)", input: base({ person: { age: 36, careerYears: 8, employmentType: "CONTRACT" },
      income: { monthlyNetIncome: 3200000 }, expenses: exp(1100000, 1000000), assets: { cash: 15000000, deposits: 0, investments: 0 },
      savingPlan: { monthlySaving: 600000, generalSavingRate: 3.03, monthlyInvestment: 0 }, emergency: { breakThreshold: 1000000 },
      goal: { name: "5년 뒤 5,000만 원", targetAmount: 50000000, targetDate: "2031-11" },
      events: [{ presetKey: "JEONSE_MOVE", name: "전세 이사", eventType: "MOVE", occurDate: "2027-11", deposit: 10000000, monthlyChange: -300000, amount: 1500000 }] }) },
  { key: "E1", note: "시작부터 목표 이상 (medianReach 0개월, reach70은 1개월부터 셈), 예금·투자 합산", input: base({
      assets: { cash: 20000000, deposits: 15000000, investments: 3000000 }, goal: { name: "2년 뒤 3,000만 원", targetAmount: 30000000, targetDate: "2028-11" } }) },
  { key: "E2", note: "고소득 비대상 (정책적금 null), 일반 적금 금리 직접 입력 4.2%", input: base({ person: { age: 28, careerYears: 3, employmentType: "REGULAR" },
      income: { monthlyNetIncome: 5600000 }, expenses: exp(1500000, 1500000), assets: { cash: 5000000, deposits: 0, investments: 0 },
      savingPlan: { monthlySaving: 1000000, generalSavingRate: 4.2, monthlyInvestment: 0 }, emergency: { breakThreshold: 2000000 },
      goal: { name: "5년 뒤 1억 원", targetAmount: 100000000, targetDate: "2031-11" } }) },
  { key: "E3", note: "120개월 목표 (목표 늦추기 후보 없음), 이직·자동차 이벤트, 전세대출 이자만, 투자", input: base({ person: { age: 25, careerYears: 1, employmentType: "CONTRACT" },
      income: { monthlyNetIncome: 2600000 }, expenses: exp(1000000, 900000), assets: { cash: 3000000, deposits: 2000000, investments: 5000000 },
      savingPlan: { monthlySaving: 450000, generalSavingRate: 3.03, monthlyInvestment: 100000 }, emergency: { breakThreshold: 800000 },
      loans: { hasNoLoan: false, items: [{ name: "전세대출", loanType: "JEONSE", principal: 50000000, annualRate: null, remainingMonths: 24, repaymentType: null }] },
      goal: { name: "10년 뒤 1억 원", targetAmount: 100000000, targetDate: "2036-11" },
      events: [{ presetKey: "JOB_CHANGE", name: "이직", eventType: "JOB_CHANGE", occurDate: "2028-05", incomeChangeRate: 10.0, gapMonths: 2 },
               { presetKey: "CAR", name: "자동차", eventType: "ONE_TIME", occurDate: "2029-11", amount: 5000000 },
               { presetKey: "CAR", name: "자동차", eventType: "SPEND_CHANGE", occurDate: "2029-11", monthlyChange: 300000 }] }) },
  { key: "E4", note: "지출이 소득보다 큼, 저축 0원 (저축액 격자 없음, 저축 줄이기 후보 없음), 신용대출 기본값(6.3%, 원리금균등)", input: base({ person: { age: 30, careerYears: 4, employmentType: "REGULAR" },
      loans: { hasNoLoan: false, items: [{ name: "신용대출", loanType: "CREDIT", principal: 3000000, annualRate: null, remainingMonths: 24, repaymentType: null }] },
      income: { monthlyNetIncome: 2300000 }, expenses: exp(1300000, 1100000), assets: { cash: 8000000, deposits: 0, investments: 0 },
      savingPlan: { monthlySaving: 0, generalSavingRate: 3.03, monthlyInvestment: 0 }, goal: { name: "3년 뒤 1,000만 원", targetAmount: 10000000, targetDate: "2029-11" } }) },
  { key: "E5", note: "매달 적자로 확정 계산도 음수 (첫 문장의 음수 금액 표기), 지출 항목 DETAIL 입력", input: base({ person: { age: 29, careerYears: 3, employmentType: "REGULAR" },
      income: { monthlyNetIncome: 2000000 },
      expenses: { fixed: { inputMode: "DETAIL", items: [{ name: "월세/관리비", amount: 700000 }, { name: "통신비", amount: 100000 }, { name: "보험료", amount: 500000 }], total: null },
                  variable: { inputMode: "TOTAL", items: [], total: 1100000 } },
      assets: { cash: 3000000, deposits: 0, investments: 0 }, savingPlan: { monthlySaving: 0, generalSavingRate: 3.03, monthlyInvestment: 0 },
      goal: { name: "2년 뒤 500만 원", targetAmount: 5000000, targetDate: "2028-11" } }) },
];

// ── 2장: 변환
const DEF_LOAN = { STUDENT: [1.7, "annuity"], JEONSE: [4.3, "interest_only"], CREDIT: [6.3, "annuity"], MORTGAGE: [4.7, "annuity"], OTHER: [5.0, "annuity"] };
const METHOD = { EQUAL_PAYMENT: "annuity", EQUAL_PRINCIPAL: "equal_principal", BULLET: "bullet", INTEREST_ONLY: "interest_only" };
const ym = s => { const [y, m] = s.split("-").map(Number); return y * 12 + (m - 1); };
const monthsBetween = (a, b) => ym(b) - ym(a);
const addMonths = (s, k) => { const v = ym(s) + k, y = Math.floor(v / 12), m = v % 12 + 1; return `${y}-${String(m).padStart(2, "0")}`; };
const man = w => w / 10000;
const expTotal = e => e.inputMode === "DETAIL" ? e.items.reduce((s, x) => s + x.amount, 0) : e.total;
function toEngine(inp, adj = {}) {
  const variableW = adj.monthlyVariableExpense ?? expTotal(inp.expenses.variable);
  const events = (adj.events ?? inp.events).map(ev => {
    const t = monthsBetween(inp.startMonth, ev.occurDate);
    if (ev.eventType === "ONE_TIME") return { t, kind: "lump", amount: man(ev.amount) };
    if (ev.eventType === "SPEND_CHANGE") return { t, kind: "spend", delta: man(ev.monthlyChange) };
    if (ev.eventType === "JOB_CHANGE") return { t, kind: "income", pct: ev.incomeChangeRate / 100, gap: ev.gapMonths };
    if (ev.eventType === "MOVE") return { t, kind: "move", deposit: man(ev.deposit), delta: man(ev.monthlyChange), cost: man(ev.amount) };
    throw new Error("eventType");
  });
  const pr = {
    age: inp.person.age, exp_years: inp.person.careerYears, employment: inp.person.employmentType === "CONTRACT" ? "계약" : "정규",
    net_income: man(inp.income.monthlyNetIncome), fixed: man(expTotal(inp.expenses.fixed)), variable: man(variableW),
    cash0: man(inp.assets.cash + inp.assets.deposits), invest0: man(inp.assets.investments || 0),
    save: man(adj.monthlySaving ?? inp.savingPlan.monthlySaving), invest_monthly: man(adj.monthlyInvestment ?? (inp.savingPlan.monthlyInvestment || 0)),
    general_rate: inp.savingPlan.generalSavingRate == null ? null : inp.savingPlan.generalSavingRate / 100,
    thr: inp.emergency.breakThreshold == null ? 50 : man(inp.emergency.breakThreshold),
    loans: inp.loans.items.map(l => ({ balance: man(l.principal), rate: (l.annualRate ?? DEF_LOAN[l.loanType][0]) / 100, months: l.remainingMonths,
      method: l.repaymentType ? METHOD[l.repaymentType] : DEF_LOAN[l.loanType][1] })),
    events,
  };
  const gm = monthsBetween(inp.startMonth, adj.goalTargetDate ?? inp.goal.targetDate);
  return { pr, goal: man(inp.goal.targetAmount), gm, buf: man(adj.emergencyFirstAmount ?? 0) };
}

// ── 2.4: 반올림 (BigDecimal HALF_UP과 같은 결과)
const halfUpInt = v => { const a = Math.abs(v), f = Math.floor(a); const r = (a - f) >= 0.5 ? f + 1 : f; return v < 0 ? -r : r; };
const won = x => x == null ? null : halfUpInt(x * 10000);
function pctCount(count, n) {   // BigDecimal(count × 100) ÷ n, 소수 1자리 HALF_UP (정수 연산)
  const neg = count < 0, c = Math.abs(count) * 1000, q = Math.floor(c / n), rem = c - q * n;
  const r = (2 * rem >= n ? q + 1 : q) / 10; return neg ? -r : r;
}
// double 값의 소수 d자리 HALF_UP = Java BigDecimal.valueOf(x).setScale(d, HALF_UP): 가장 짧은 10진 표기(Double.toString)를 반올림
function dec(x, d) {
  let str = String(Math.abs(x)); if (/e/i.test(str)) str = Math.abs(x).toFixed(20);
  const [ip, fp = ""] = str.split("."), digits = (ip + fp.padEnd(d + 1, "0")).split("").map(Number);
  const cut = ip.length + d; let up = digits[cut] >= 5; const kept = digits.slice(0, cut);
  for (let i = kept.length - 1; up && i >= 0; i--) { if (kept[i] === 9) kept[i] = 0; else { kept[i]++; up = false; } }
  let v = Number((up ? "1" : "") + kept.join("")) / 10 ** d; return x < 0 ? -v : v;
}

// ── 7.1~7.2
const qIdx = x => Math.min(N - 1, Math.floor(x * N));
const lowMed = a => { if (!a.length) return null; const s = [...a].sort((x, y) => x - y); return s[Math.floor((s.length - 1) / 2)]; };
function runAll(c) {
  const { pr, goal, gm, buf } = c;
  const R = E.simulate(pr, E.PARAMS, noise, N, T, { collect: true, bufferFirst: buf });
  const F36 = E.simulate(pr, E.PARAMS, noise, N, 36, { policy: FIRST, bufferFirst: buf });
  const F48 = E.simulate(pr, E.PARAMS, noise, N, 48, { policy: FIRST, bufferFirst: buf });
  const D = E.simulate(pr, E.PARAMS, E.zeroNoise(), 1, T, { collect: true, bufferFirst: buf }).saved;
  const s = R.saved, W = T + 1, at = (p, m) => s[p * W + m];
  const col = m => { const a = new Float64Array(N); for (let p = 0; p < N; p++) a[p] = at(p, m); return a.sort(); };
  const bands = []; for (let m = 0; m <= T; m++) { const a = col(m); bands.push([a[qIdx(.05)], a[qIdx(.10)], a[qIdx(.50)], a[qIdx(.90)]]); }
  const hit = new Uint8Array(N); let hits = 0, neg = 0; const firstNeg = [], firstHit = [];
  for (let p = 0; p < N; p++) {
    if (at(p, gm) >= goal) { hit[p] = 1; hits++; }
    for (let m = 1; m <= gm; m++) if (at(p, m) < 0) { neg++; firstNeg.push(m); break; }
    let f = Infinity; for (let m = 0; m <= T; m++) if (at(p, m) >= goal) { f = m; break; } firstHit.push(f);
  }
  const reach = tau => { for (let m = 1; m <= T; m++) { let k = 0; for (let p = 0; p < N; p++) if (at(p, m) >= goal) k++; if (k / N >= tau) return m; } return null; };
  const cnt = (r, k) => r.flags[k].reduce((a, b) => a + b, 0);
  const causes = { sp: cnt(F36, "c1_sp"), un: cnt(F36, "c1_unemp"), sh: cnt(F36, "c1_shock"), de: cnt(F36, "c1_deficit") };
  const med = lowMed(firstHit);
  return { pr, goal, gm, buf, R, bands, hit, hits, neg, firstNeg, medReach: med === Infinity ? null : med, reach70: reach(TAU), reach,
    brk: cnt(F36, "brk1_gen"), causes, subsidy: F48.matchPaid.reduce((a, b) => a + b, 0) / N, D, eligible: R.eligible };
}
const fmtMan = v => { const neg = v < 0, a = halfUpInt(Math.abs(v)); const eok = Math.floor(a / 10000), m = a % 10000;
  const s = eok ? `${eok}억${m ? " " + m.toLocaleString("ko-KR") + "만" : ""} 원` : `${m.toLocaleString("ko-KR")}만 원`; return (neg ? "−" : "") + s; };
const period = gm => gm % 12 === 0 ? `${gm / 12}년` : `${Math.floor(gm / 12)}년 ${gm % 12}개월`;
function simulateResult(inp, r) {
  const ck = m => ({ afterMonths: m, p5: won(r.bands[m][0]), p10: won(r.bands[m][1]), p50: won(r.bands[m][2]), p90: won(r.bands[m][3]) });
  const p = r.hits / N, c = r.causes, ct = c.sp + c.un + c.sh + c.de;
  return {
    seed: SEED, paths: N, startMonth: inp.startMonth,
    headline: `지금 계획이면 ${period(r.gm)} 뒤 ${fmtMan(r.goal)}을 모을 가능성은 약 ${halfUpInt(pctCount(r.hits, N))}%예요. 계산기로는 ${fmtMan(r.D[r.gm])}이 나오지만, 실직이나 급한 지출까지 넣으면 그래요.`,
    goal: { targetAmount: inp.goal.targetAmount, goalMonths: r.gm, hits: r.hits, probability: pctCount(r.hits, N), probabilityError: dec(Math.sqrt(p * (1 - p) / N) * 100, 1),
      medianReachMonths: r.medReach, medianReachDate: r.medReach == null ? null : addMonths(inp.startMonth, r.medReach),
      reach70Months: r.reach70, reach70Date: r.reach70 == null ? null : addMonths(inp.startMonth, r.reach70) },
    deterministic: { atGoal: won(r.D[r.gm]), at120: won(r.D[T]) },
    checkpoints: [12, 36, 60, 120].map(ck),
    bands: r.bands.map((b, m) => ({ month: addMonths(inp.startMonth, m), p5: won(b[0]), p10: won(b[1]), p50: won(b[2]), p90: won(b[3]) })),
    risks: { debt: { count: r.neg, probability: pctCount(r.neg, N), medianFirstMonths: lowMed(r.firstNeg),
                     medianFirstDate: r.firstNeg.length ? addMonths(inp.startMonth, lowMed(r.firstNeg)) : null, worstCaseAtGoal: won(r.bands[r.gm][0]) },
             policySaving: { eligible: r.eligible, earlyTerminationCount: r.eligible ? r.brk : null, earlyTerminationProbability: r.eligible ? pctCount(r.brk, N) : null,
                             expectedSubsidy: r.eligible ? won(r.subsidy) : null } },
    causes: { counts: { unemployment: c.sp, prolongedUnemployment: c.un, emergencyExpense: c.sh, chronicDeficit: c.de },
              unemployment: ct ? pctCount(c.sp, ct) : 0, prolongedUnemployment: ct ? pctCount(c.un, ct) : 0, emergencyExpense: ct ? pctCount(c.sh, ct) : 0, chronicDeficit: ct ? pctCount(c.de, ct) : 0 },
  };
}

// ── 7.3
function paired(a, b) {   // a = 바꾼 계획, b = 지금 계획
  let s = 0, s2 = 0; for (let p = 0; p < N; p++) { const d = a.hit[p] - b.hit[p]; s += d; s2 += d * d; }
  const md = s / N, sd = Math.sqrt(Math.max(s2 / N - md * md, 0) * N / (N - 1)), z = sd > 0 ? md / (sd / Math.sqrt(N)) : 0;
  return { z: dec(z, 2), meaningful: Math.abs(z) >= 1.96 && Math.abs(md) >= 0.02, hitDiff: s };
}
const yearlyP50 = r => { const o = []; for (let m = 12; m <= r.gm; m += 12) o.push(won(r.bands[m][2])); return o; };
function planView(inp, r) {
  return { probability: pctCount(r.hits, N), reach70Date: r.reach70 == null ? null : addMonths(inp.startMonth, r.reach70), debtProbability: pctCount(r.neg, N),
    worstCaseAtGoal: won(r.bands[r.gm][0]), earlyTerminationProbability: r.eligible ? pctCount(r.brk, N) : null, expectedSubsidy: r.eligible ? won(r.subsidy) : null, yearlyP50: yearlyP50(r) };
}

// ── 7.4
const firstPay = pr => { const T1 = 1; let s = 0; for (const l of pr.loans) { const i = l.rate / 12, n = l.months, B = l.balance;
  if (l.method === "annuity") s += i > 0 ? B * i * (1 + i) ** n / ((1 + i) ** n - 1) : B / n; else if (l.method === "equal_principal") s += Math.min(B, B / n) + B * i; else if (l.method === "bullet") s += B * i + (n === 1 ? B : 0); else s += B * i; } return s; };
function reverse(inp, c0, b) {
  const pr = c0.pr, surplus = pr.net_income - pr.fixed - pr.variable - firstPay(pr);
  const run = c => runAll(c);
  let cut;
  if (b.hits / N >= TAU) cut = { amount: 0, probability: pctCount(b.hits, N) };
  else { cut = { amount: null, probability: null };
    for (let k = 5; k <= Math.min(pr.variable, 60); k += 5) { const r = run({ ...c0, pr: { ...pr, variable: Math.max(0, pr.variable - k) } }); if (r.hits / N >= TAU) { cut = { amount: won(k), probability: pctCount(r.hits, N) }; break; } } }
  let save = { amount: null, maxProbability: null, atAmount: null, grid: [] }, best = -1;
  for (let s = 0; s <= surplus + 1e-9; s += 5) { const r = run({ ...c0, pr: { ...pr, save: s } }); save.grid.push({ amount: won(s), probability: pctCount(r.hits, N) });
    if (r.hits > best) { best = r.hits; save.maxProbability = pctCount(r.hits, N); save.atAmount = won(s); }
    if (save.amount == null && r.hits / N >= TAU) save.amount = won(s); }
  const per = b.reach70;
  const strategies = [0, 100, 200, 300, 500].map(B => { const r = run({ ...c0, buf: B });
    return { amount: won(B), probability: pctCount(r.hits, N), earlyTerminationProbability: r.eligible ? pctCount(r.brk, N) : null, expectedSubsidy: r.eligible ? won(r.subsidy) : null }; });
  const cand = [];
  if (pr.variable >= 10) cand.push({ type: "REDUCE_VARIABLE_EXPENSE", label: "변동지출을 월 10만 원 줄이기", c: { ...c0, pr: { ...pr, variable: pr.variable - 10 } } });
  if (c0.gm + 12 <= 120) cand.push({ type: "DELAY_GOAL", label: "목표 시점을 1년 늦추기", c: { ...c0, gm: c0.gm + 12 } });
  cand.push({ type: "LOWER_GOAL", label: "목표 금액을 10% 낮추기", c: { ...c0, goal: Math.round(c0.goal * 0.9) } });
  if (b.eligible && pr.cash0 < 200) cand.push({ type: "EMERGENCY_FIRST", label: "비상금 200만 원을 먼저 모은 뒤 적금 시작", c: { ...c0, buf: 200 } });
  if (pr.save >= 20) cand.push({ type: "REDUCE_SAVING", label: "월 저축액을 10만 원 줄이기", c: { ...c0, pr: { ...pr, save: pr.save - 10 } } });
  const levers = cand.map(x => { const r = run(x.c), d = r.hits - b.hits;
    return { type: x.type, label: x.label, probability: pctCount(r.hits, N), diff: pctCount(d, N), flat: Math.abs(d) < 0.02 * N, _d: d }; })
    .sort((x, y) => y._d - x._d).map(({ _d, ...rest }) => rest);
  return { currentProbability: pctCount(b.hits, N), targetProbability: 70.0, monthlySurplus: won(surplus),
    requiredVariableExpenseCut: cut, requiredMonthlySaving: save,
    requiredPeriod: { months: per, reach70Date: per == null ? null : addMonths(inp.startMonth, per) }, emergencyStrategies: strategies, levers };
}

// ── 7.5
function robust(inp, c0, planC) {
  let st = 12345; const rnd = () => { st = (st + 0x6D2B79F5) | 0; let t = Math.imul(st ^ (st >>> 15), 1 | st); t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t; return ((t ^ (t >>> 14)) >>> 0) / 4294967296; };
  const U = (a, b) => a + (b - a) * rnd(), pickOne = arr => arr[Math.floor(rnd() * arr.length) % arr.length];
  const QTAB = {"long|0.67|base": 0.09358, "long|0.67|buf3": 0.06386, "long|0.67|buf12": 0.1421, "long|0.67|spend": 0.06998, "long|1.0|base": 0.04817, "long|1.0|buf3": 0.03324, "long|1.0|buf12": 0.07747, "long|1.0|spend": 0.03681, "long|1.5|base": 0.02733, "long|1.5|buf3": 0.01914, "long|1.5|buf12": 0.04436, "long|1.5|spend": 0.02133, "short|0.67|base": 0.06882, "short|0.67|buf3": 0.03865, "short|0.67|buf12": 0.12156, "short|0.67|spend": 0.04469, "short|1.0|base": 0.03552, "short|1.0|buf3": 0.01997, "short|1.0|buf12": 0.06605, "short|1.0|spend": 0.02341, "short|1.5|base": 0.0202, "short|1.5|buf3": 0.01148, "short|1.5|buf12": 0.03809, "short|1.5|spend": 0.01358};
  const K = 60, M = 2000, probs = [], diffs = [], trials = [];
  const hitsAt = (r, gm, goal) => { let h = 0; for (let k = 0; k < M; k++) if (r.saved[k * (gm + 1) + gm] >= goal) h++; return h; };
  for (let j = 0; j < K; j++) {
    const p = E.clone(E.PARAMS);
    Object.assign(p.inflation, { mu_y: U(.0138, .0278), sigma_y: U(.0097, .0204), phi: U(.1, .9) });
    Object.assign(p.wage, { macro_mu: U(.018, .038), idio_sd: U(.006, .147), rho_macro: U(.21, .87) }); p.wage.exp_prem = [0.0652, 0.0515, 0.0445, U(.0367, .0401)];
    const sc = U(.78, 1); p.unemp.u_by_age = E.PARAMS.unemp.u_by_age.map(x => x * sc);
    const stc = pickOne(["long", "short"]), am = pickOne(["0.67", "1.0", "1.5"]), pv = pickOne(["base", "buf3", "buf12", "spend"]), amf = parseFloat(am);
    p.unemp.exit_m = stc === "long" ? 0.092 : 0.241; p.shock.amounts = [[120 * amf, .7], [300 * amf, .3]]; p.shock.prob_m = QTAB[`${stc}|${am}|${pv}`];
    Object.assign(p.unemp, { invol_share: U(.2, .35), scar_per_year: U(.03, .1) }); p.spend.raise_share = U(.24, .5);
    const nz = E.randomNoise(900 + j, M);
    const a = hitsAt(E.simulate(c0.pr, p, nz, M, c0.gm, { collect: true, bufferFirst: c0.buf }), c0.gm, c0.goal);
    const b = hitsAt(E.simulate(planC.pr, p, nz, M, planC.gm, { collect: true, bufferFirst: planC.buf }), planC.gm, planC.goal);
    probs.push(a / M); diffs.push((b - a) / M); trials.push({ j, stc, am: amf, pv, baseHits: a, planHits: b });
  }
  const q = (arr, x) => { const s = [...arr].sort((u, v) => u - v), h = (s.length - 1) * x, l = Math.floor(h); return s[l] + (s[Math.min(l + 1, s.length - 1)] - s[l]) * (h - l); };
  return { probs, diffs, trials, q };
}

// ── 실행
const out = { version: "1.0", engine: "engine.js v1.6 (= kernel.py v1.6)", seed: SEED, paths: N, horizon: T,
  rounding: "금액 원 HALF_UP, 확률·비율은 개수(정수)에서 BigDecimal 소수 1자리 HALF_UP, 오차·z·범위는 double 값의 HALF_UP (구현 명세서 2.4, 7.5)",
  cases: [] };
for (const cs of CASES) {
  const t0 = Date.now(), inp = cs.input;
  const c0 = toEngine(inp), b = runAll(c0);
  const sim = simulateResult(inp, b);
  // 미리보기: 변동지출 10만 원 줄이기 (변동지출이 10만 원 미만이면 0원으로)
  const vW = expTotal(inp.expenses.variable), adjP = { monthlyVariableExpense: Math.max(0, vW - 100000) };
  const a = runAll(toEngine(inp, adjP)), cv = planView(inp, b), av = planView(inp, a), sg = paired(a, b);
  const preview = { adjustments: adjP, current: { probability: cv.probability, reach70Date: cv.reach70Date, yearlyP50: cv.yearlyP50 },
    adjusted: { probability: av.probability, reach70Date: av.reach70Date, yearlyP50: av.yearlyP50 },
    table: [["PROBABILITY", "probability"], ["DEBT_PROBABILITY", "debtProbability"], ["WORST_CASE_AT_GOAL", "worstCaseAtGoal"], ["EARLY_TERMINATION", "earlyTerminationProbability"], ["EXPECTED_SUBSIDY", "expectedSubsidy"]]
      .map(([metric, k]) => ({ metric, current: cv[k], adjusted: av[k],
        diff: cv[k] == null ? null : k === "probability" ? pctCount(a.hits - b.hits, N) : k === "debtProbability" ? pctCount(a.neg - b.neg, N) : k === "earlyTerminationProbability" ? pctCount(a.brk - b.brk, N) : av[k] - cv[k] })),
    significance: { z: sg.z, meaningful: sg.meaningful } };
  // 비교: 계획 2~3개
  const plans = [{ name: "변동지출 10만 원 줄이기", adjustments: adjP }, { name: "비상금 300만 원 먼저", adjustments: { emergencyFirstAmount: 3000000 } }];
  if (c0.gm + 12 <= 120) plans.push({ name: "목표 1년 늦추기", adjustments: { goalTargetDate: addMonths(inp.goal.targetDate, 12) } });
  const compare = { goalMonths: c0.gm, plans: [{ name: "지금 계획", isBase: true, diffFromBase: 0.0, z: null, meaningful: null, ...cv }] };
  for (const pl of plans) { const r = runAll(toEngine(inp, pl.adjustments)), g = paired(r, b);
    compare.plans.push({ name: pl.name, adjustments: pl.adjustments, isBase: false, diffFromBase: pctCount(r.hits - b.hits, N), z: g.z, meaningful: g.meaningful, ...planView(inp, r) }); }
  const rev = reverse(inp, c0, b);
  // 범위: 지금 계획 + 변동지출 10만 원 줄이기
  const rb = robust(inp, c0, toEngine(inp, adjP)), mainDiff = a.hits - b.hits;
  const kept = rb.diffs.filter(d => Math.sign(d) === Math.sign(mainDiff)).length;
  const P = x => dec(x * 100, 1);
  const robustRes = { trials: 60, pathsPerTrial: 2000,
    probability: { median: P(rb.q(rb.probs, .5)), low: P(rb.q(rb.probs, .025)), high: P(rb.q(rb.probs, .975)) },
    plans: [{ name: "변동지출 10만 원 줄이기", diffMedian: P(rb.q(rb.diffs, .5)), diffLow: P(rb.q(rb.diffs, .025)), diffHigh: P(rb.q(rb.diffs, .975)), conclusionKept: kept, trials: 60 }],
    message: `가정을 60가지로 바꿔 다시 계산하면 대부분 ${halfUpInt(P(rb.q(rb.probs, .025)))}%~${halfUpInt(P(rb.q(rb.probs, .975)))}% 사이예요. 정확한 한 숫자보다 이 범위를 봐 주세요.`,
    trialsDetail: rb.trials };
  out.cases.push({ key: cs.key, note: cs.note, input: inp, engineProfile: { ...c0.pr, goal: c0.goal, goalMonths: c0.gm },
    simulate: sim, preview, compare, reverse: rev, robust: robustRes });
  console.log(`${cs.key}: ${sim.goal.probability}% (${sim.goal.hits}) · 미리보기 ${av.probability}% z ${sg.z} · 범위 ${robustRes.probability.median} (${robustRes.probability.low}~${robustRes.probability.high}) · ${((Date.now() - t0) / 1000).toFixed(1)}초`);
}
fs.writeFileSync(__dirname + "/analysis_golden.json", JSON.stringify(out, null, 1));
console.log("analysis_golden.json 저장");
