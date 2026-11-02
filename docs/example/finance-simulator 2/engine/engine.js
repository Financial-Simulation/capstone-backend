/* 재무 시뮬레이터 계산 엔진 — 파이썬 기준 구현 v1.6과 같은 순서·같은 수식 (단위: 만원 / 개월) */
const ENGINE = (() => {
  const PARAMS = {
    inflation: { mu_y: 0.0208, sigma_y: 0.0132, phi: 0.55, last: 0.021 },
    wage: { macro_mu: 0.0285, macro_sd: 0.0115, idio_sd: 0.115, rho_macro: 0.65,
            exp_edges: [2.0, 4.0, 7.5], exp_prem: [0.0652, 0.0515, 0.0445, 0.0401] },
    unemp: { age_edges: [25, 30, 35], u_by_age: [0.088, 0.074, 0.037, 0.025], exit_m: 0.092, invol_share: 0.274,
             contract_ratio: 12.3 / 9.9, contract_share_pop: 0.34, scar_per_year: 0.067 },
    ui: { floor: 198.144, cap: 204.3, replace: 0.60, waiting_frac: 7 / 30, gross_over_net: 1 / 0.88,
          days_edges: [1, 3, 5, 10], days: [120, 150, 180, 210, 240], min_insured_m: 6, window_m: 18 },
    spend: { raise_share: 0.45, unemp_cut_ui: 0.068, unemp_cut_noui: 0.22 },
    shock: { prob_m: 0.0482, amounts: [[120.0, 0.70], [300.0, 0.30]] },
    overdraft: { annual_rate: 0.0531 }, liquid: { annual_rate: 0.0068 },
  };
  const POLICY = { cap: 50.0, rate: 0.050, term: 36, match: 0.06, match_cap: 3.0, window: 6, tax: 0.154, age_max: 34,
                   gross_max_y: 7500.0, rejoin_wait: 2, rejoin: true,
                   early_table: [[3, 0.5], [6, 0.7], [9, 0.8], [11, 0.9]], early_floor: 0.0015, early_under3: 0.001 };
  const GENERAL = { rate: 0.0303, early_ratio: 0.5, tax: 0.154 };

  // np.searchsorted(edges, x, side="right") = x 이하인 경계의 개수
  const sr = (edges, x) => { let i = 0; while (i < edges.length && x >= edges[i]) i++; return i; };
  function hazard(P, emp, age) {
    const U = P.unemp, u = U.u_by_age[sr(U.age_edges, age)], h = u * U.exit_m / (1 - u);
    const c = U.contract_share_pop, r = U.contract_ratio, base = 1 / ((1 - c) + c * r);
    return h * (emp === "계약" ? base * r : emp === "정규" ? base : 1);
  }
  const expPrem = (P, y) => P.wage.exp_prem[sr(P.wage.exp_edges, y)];
  const uiMonths = (P, tenure) => Math.round(P.ui.days[sr(P.ui.days_edges, tenure)] / 30);
  function earlyRatio(pol, held) {
    let ratio = 0; for (const [lo, k] of pol.early_table) if (held >= lo) ratio = k;
    const eff = held < 3 ? pol.early_under3 : Math.max(pol.rate * ratio * held / pol.term, pol.early_floor);
    return eff / pol.rate;
  }
  function loanSchedule(loans, T) {
    const pay = new Float64Array(T), bal = new Float64Array(T);
    for (const ln of loans || []) {
      let B = +ln.balance; const i = ln.rate / 12, n = Math.trunc(ln.months), m = ln.method || "annuity";
      const A = (m === "annuity" && i > 0) ? B * i * (1 + i) ** n / ((1 + i) ** n - 1) : (m === "annuity" ? B / n : 0);
      for (let t = 0; t < T; t++) {
        if (B <= 1e-9) continue;
        const it = B * i; let p;
        if (m === "annuity") p = Math.min(A, B + it);
        else if (m === "equal_principal") p = Math.min(B, +ln.balance / n) + it;
        else if (m === "interest_only") p = t < n ? it : 0;   // 전세자금: 원금은 보증금 반환으로 상환 (현금흐름 밖)
        else p = it + (t === n - 1 ? B : 0);
        B = m === "interest_only" ? B : Math.max(B - (p - it), 0); pay[t] += p; bal[t] += B;
        if (m === "interest_only" && t === n - 1) B = 0;
      }
    }
    return { pay, bal };
  }
  const geom = (u, e, cap) => Math.min(1 + Math.floor(Math.log(Math.min(Math.max(1 - u, 1e-12), 1)) / Math.log(1 - e)), cap);
  function pick(u, pmf) { let c = 0; const cs = pmf.map(([, p]) => (c += p)); return pmf[Math.min(sr(cs, u), pmf.length - 1)][0]; }

  // ── 난수: 경로 번호마다 결정되는 스트림 (공통 난수), 정규분포는 짝 경로끼리 부호 반대 (대조 변량)
  function sfc32(a, b, c, d) {
    return () => { a |= 0; b |= 0; c |= 0; d |= 0; const t = (((a + b) | 0) + d) | 0; d = (d + 1) | 0; a = b ^ (b >>> 9);
      b = (c + (c << 3)) | 0; c = (c << 21) | (c >>> 11); c = (c + t) | 0; return (t >>> 0) / 4294967296; };
  }
  function mix(seed, k, s) { let h = (seed ^ 0x9e3779b9) >>> 0; for (const v of [k, s]) { h = Math.imul(h ^ (v + 0x7f4a7c15), 0x85ebca6b) >>> 0; h ^= h >>> 13; h = Math.imul(h, 0xc2b2ae35) >>> 0; h ^= h >>> 16; } return h; }
  function rng(seed, k, s) { const r = sfc32(mix(seed, k, s), mix(seed, k, s + 11), mix(seed, k, s + 23), mix(seed, k, s + 37)); for (let i = 0; i < 12; i++) r(); return r; }
  function randomNoise(seed, N) {
    const half = N % 2 === 0 ? N / 2 : N;
    return (p, T) => {
      const Y = Math.floor(T / 12) + 1, k = p % half, sign = (N % 2 === 0 && p >= half) ? -1 : 1;
      const rn = rng(seed, k, 1), ru = rng(seed, p, 2);
      const gauss = () => { let u = 0; while (u === 0) u = rn(); return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * rn()); };
      const z1 = new Float64Array(T), z2 = new Float64Array(Y);
      for (let j = 0; j < T; j++) z1[j] = sign * gauss();
      for (let j = 0; j < Y; j++) z2[j] = sign * gauss();
      const U = () => { const a = new Float64Array(T); for (let j = 0; j < T; j++) a[j] = ru(); return a; };
      return { z1, z2, u_onset: U(), u_dur: U(), u_invol: U(), u_shock: U(), u_amt: U() };
    };
  }
  const zeroNoise = () => (p, T) => {
    const Y = Math.floor(T / 12) + 1, f = (n, v) => new Float64Array(n).fill(v);
    return { z1: f(T, 0), z2: f(Y, 0), u_onset: f(T, 1), u_dur: f(T, 0), u_invol: f(T, 1), u_shock: f(T, 1), u_amt: f(T, 0) };
  };

  const FLAGS = ["unemp", "overdraft", "brk1_sp", "brk1_gen", "brkG", "mature1", "limit", "rejoin", "c1_sp", "c1_unemp", "c1_shock", "c1_deficit"];

  function simulate(pr, P, noise, N, T, opt = {}) {
    const bufferFirst = opt.bufferFirst || 0, collect = !!opt.collect, pol = opt.policy || POLICY, gen = opt.general || GENERAL;
    const Pi = P.inflation, Wg = P.wage, U = P.unemp, UI = P.ui;
    const age0 = +pr.age, exp0 = +pr.exp_years, net0 = +pr.net_income, sfix = +pr.fixed, svar = +pr.variable, save = +pr.save;
    const thr = pr.thr ?? 50, invM = pr.invest_monthly || 0, emp = pr.employment || "정규";
    const incomeOk = net0 * 12 * UI.gross_over_net <= pol.gross_max_y + 1e-6, eligible = age0 <= pol.age_max && incomeOk;
    const a1 = eligible ? Math.min(save, pol.cap) : 0, e = U.exit_m;
    const { pay: loanPay } = loanSchedule(pr.loans, T);
    const ev = [...(pr.events || [])].sort((a, b) => a.t - b.t);
    const epsSd = Pi.sigma_y * Math.sqrt(1 - Pi.phi ** 2), sig = Math.hypot(Wg.macro_sd, Wg.idio_sd), rho = sig > 0 ? Wg.rho_macro * Wg.macro_sd / sig : 0;
    const gRate = pr.general_rate != null ? +pr.general_rate : gen.rate;   // 사용자가 아는 적금 금리 (없으면 시장 평균)
    const rOd = P.overdraft.annual_rate / 12, rL = P.liquid.annual_rate / 12, rP = pol.rate / 12, rG = gRate / 12;
    const uiAmt = Math.min(Math.max(UI.replace * net0 * UI.gross_over_net, UI.floor), UI.cap);

    const saved = collect ? new Float64Array(N * (T + 1)) : null;
    const flags = Object.fromEntries(FLAGS.map(k => [k, new Uint8Array(N)]));
    const firstBrk = new Int32Array(N).fill(-1), matchPaid = new Float64Array(N);

    for (let p = 0; p < N; p++) {
      const nz = noise(p, T);
      let L = +pr.cash0, od = 0, INV = pr.invest0 || 0, DEP = 0, P1 = 0, C1 = 0, M1 = 0, held = 0, act1 = a1 > 0;
      let cumHeld = 0, matchMult = 1, wait = -1, G = 0, CG = 0, gHeld = 0, Mp = 0, started = (L >= bufferFirst) || (bufferFirst <= 0);
      let base = net0, incomeMult = 1, cum = 1, piPrev = Pi.last, mInf = 0;
      let unemp = 0, uiLeft = 0, uiFirst = false, since = 1e6, gapLen = 0, volGap = 0, lastShock = -99, extraFixed = 0;
      let insured = Math.min(exp0 * 12, UI.window_m), mp = 0;
      const row = p * (T + 1);
      if (collect) saved[row] = L + P1 + G + INV + DEP - od;
      for (let t = 0; t < T; t++) {
        const ageNow = age0 + t / 12, expNow = exp0 + t / 12;
        if (t % 12 === 0) {   // ① 연초: 물가 AR(1), 연봉 인상
          const n = Math.min(12, T - t); let s = 0; for (let j = t; j < t + n; j++) s += nz.z1[j];
          const pi = Pi.mu_y + Pi.phi * (piPrev - Pi.mu_y) + epsSd * (s / Math.sqrt(n));
          mInf = (1 + pi) ** (1 / 12) - 1; piPrev = pi;
          if (t > 0) {
            let s2 = 0; for (let j = t - 12; j < t; j++) s2 += nz.z1[j];
            const g = Wg.macro_mu + expPrem(P, expNow - 1) + sig * (rho * (s2 / Math.sqrt(12)) + Math.sqrt(1 - rho ** 2) * nz.z2[t / 12 - 1]);
            base *= 1 + g;
          }
        }
        cum *= 1 + mInf;
        let lump = 0, depAdd = 0;   // ② 예정 이벤트
        for (const x of ev) {
          if (x.t !== t) continue;
          if (x.kind === "lump") lump += x.amount;
          else if (x.kind === "spend") extraFixed += x.delta;
          else if (x.kind === "move") { depAdd += x.deposit || 0; extraFixed += x.delta || 0; lump += x.cost || 0; }
          else if (x.kind === "income") { incomeMult *= 1 + (x.pct || 0); if ((x.gap || 0) > 0 && unemp === 0) volGap = Math.trunc(x.gap); }
        }
        // ③ 고용
        const h = hazard(P, emp, ageNow);
        const start = unemp === 0 && volGap === 0 && nz.u_onset[t] < h;
        const D = geom(nz.u_dur[t], e, T), invol = nz.u_invol[t] < U.invol_share;
        if (start) { unemp = D; gapLen = D; }
        const uiOk = invol && insured >= UI.min_insured_m;
        if (start) { uiLeft = uiOk ? uiMonths(P, expNow) : 0; if (uiOk) uiFirst = true; since = 0; }
        if (volGap > 0) { unemp = Math.max(unemp, volGap); gapLen = volGap; since = 0; }
        volGap = 0;
        const employed = unemp === 0; if (!employed) flags.unemp[p] = 1;
        const income = employed ? base * incomeMult : (uiLeft > 0 ? uiAmt * (uiFirst ? 1 - UI.waiting_frac : 1) : 0);
        // ④ 월초 이자 (마이너스·파킹통장). 적금 이자는 납입 뒤
        od *= 1 + rOd; L *= 1 + rL;
        // ⑤ 급전 (금액은 물가 반영)
        const hit = nz.u_shock[t] < P.shock.prob_m, shock = hit ? pick(nz.u_amt[t], P.shock.amounts) * cum : 0;
        if (hit) lastShock = t;
        // ⑥ 현금 흐름 (실직 중 소비 감소)
        const lifestyle = P.spend.raise_share * Math.max(base * incomeMult - net0 * cum, 0);
        const cut = employed ? 0 : (uiLeft > 0 ? P.spend.unemp_cut_ui : P.spend.unemp_cut_noui);
        const living = ((sfix + svar + extraFixed) * cum + lifestyle) * (1 - cut);
        L = L + income - living - shock - lump - loanPay[t] - depAdd; DEP += depAdd;
        // ⑦ 마이너스 우선 상환
        const rep = Math.min(Math.max(L, 0), od); L -= rep; od -= rep;
        // ⑧ 재가입
        if (pol.rejoin) {
          if (wait > 0) wait -= 1;
          if (wait === 0 && ageNow <= pol.age_max && incomeOk && cumHeld < pol.term) {
            act1 = true; held = 0; flags.rejoin[p] = 1; matchMult = (pol.term - cumHeld) / pol.term; wait = -1;
          }
        }
        // ⑨ 납입
        started = started || L >= bufferFirst;
        const can = employed && started;
        const put1 = (can && act1) ? Math.min(a1, Math.max(L, 0)) : 0; L -= put1;
        P1 += put1; C1 += put1; const mNew = Math.min(pol.match * put1, pol.match_cap) * matchMult; M1 += mNew; Mp += mNew;
        const rest = act1 ? save - a1 : save;
        const putG = can ? Math.min(rest, Math.max(L, 0)) : 0; L -= putG; G += putG; CG += putG;
        const putI = employed ? Math.min(invM, Math.max(L, 0)) : 0; L -= putI; INV += putI;
        // 적금 이자 (납입 직후 한 달치): 입금건별 단리
        if (act1) { P1 += C1 * rP; M1 += Mp * rP; }
        if (CG > 0) { G += CG * rG; gHeld += 1; if (gHeld >= 12) { CG += (G - CG) * (1 - gen.tax); G = CG; gHeld = 0; } } else gHeld = 0;
        // ⑩ 만기
        if (act1) { held += 1; cumHeld += 1; }
        if (act1 && held >= pol.term) { mp += M1; L += P1 + M1; P1 = C1 = M1 = Mp = 0; flags.mature1[p] = 1; act1 = false; }
        // ⑪ 부족분 → 마이너스
        const deficit = Math.max(-L, 0); od += deficit; L = Math.max(L, 0); if (deficit > 0) flags.overdraft[p] = 1;
        // ⑫ 해지: 일반 적금 먼저, 그다음 청년미래적금
        if (od > thr && G > 0) {
          const prG = CG + (G - CG) * gen.early_ratio * (1 - gen.tax), r2 = Math.min(prG, od);
          od -= r2; L += prG - r2; G = 0; CG = 0; gHeld = 0; flags.brkG[p] = 1;
        }
        if (act1 && od > thr) {
          const sp = since <= pol.window, er = earlyRatio(pol, held);
          const pr1 = sp ? P1 + M1 : C1 + (P1 - C1) * er * (1 - pol.tax); if (sp) mp += M1;
          const r3 = Math.min(pr1, od); od -= r3; L += pr1 - r3;
          P1 = C1 = M1 = Mp = 0; act1 = false; wait = pol.rejoin_wait;
          if (firstBrk[p] < 0) {
            firstBrk[p] = t;
            if (sp) flags.c1_sp[p] = 1;
            else if (!employed) flags.c1_unemp[p] = 1;
            else if (t - lastShock <= 2) flags.c1_shock[p] = 1;
            else flags.c1_deficit[p] = 1;
          }
          if (sp) flags.brk1_sp[p] = 1; else flags.brk1_gen[p] = 1;
        }
        if (od > thr) flags.limit[p] = 1;
        // ⑬ 카운터 + 재취업 임금 손실
        if (!employed && unemp === 1) base *= Math.max(1 - U.scar_per_year * gapLen / 12, 0.5);
        insured = employed ? Math.min(insured + 1, UI.window_m) : Math.max(insured - 1, 0);
        unemp = Math.max(unemp - 1, 0);
        if (!employed) { uiLeft = Math.max(uiLeft - 1, 0); uiFirst = false; }
        since += 1;
        if (collect) saved[row + t + 1] = L + P1 + G + INV + DEP - od;
      }
      matchPaid[p] = mp;
    }
    return { saved, flags, firstBrk, matchPaid, eligible, N, T };
  }

  const clone = o => JSON.parse(JSON.stringify(o));
  return { PARAMS, POLICY, GENERAL, simulate, randomNoise, zeroNoise, clone, FLAGS };
})();
if (typeof module !== "undefined") module.exports = ENGINE;
