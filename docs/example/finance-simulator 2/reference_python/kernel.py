"""
재무 시뮬레이터 기준 구현 v1.6  (단위: 만원 / 개월 / 소수 이율)

v1.4 → v1.5
- 실직 위험을 연령별 실업률로 (경제활동인구조사 2016~2025 평균): 20~24 / 25~29 / 30~34 / 35~39
- 청년미래적금 중도해지 이율: 우리은행 상품설명서 표 그대로 (보유기간별 기본이율 × 비율 × 보유/계약, 최저 0.15%)
- 해지 후 재가입: 해지월 기준 2개월 후, 기여금 비율 = (36 − 누적 가입개월)/36 (상품설명서)
- 금리: 한국은행 2025년 연평균 (수시입출식 저축성예금 잔액 0.68%, 정기적금 신규 3.03%, 일반신용대출 잔액 5.31%)
- 소득 상승분 중 지출 비율 β: 가계금융복지조사 실질 한계소비성향 (29세 이하 0.44, 1인 가구 0.50)
- 프로필 필드를 경로별 배열로 받을 수 있음 (인구 단위 검증용)
"""
import numpy as np

PARAMS = {
    "inflation": {"mu_y": 0.0208, "sigma_y": 0.0132, "phi": 0.55, "last": 0.021},                 # B KOSIS 2010~2025
    "wage": {"macro_mu": 0.0285, "macro_sd": 0.0115, "idio_sd": 0.115, "rho_macro": 0.65,             # B / B / E / C
             "exp_edges": [2.0, 4.0, 7.5], "exp_prem": [0.0652, 0.0515, 0.0445, 0.0401]},         # B (마지막 구간 C: 연령 유사패널로 교차 확인)
    "unemp": {"age_edges": [25, 30, 35], "u_by_age": [0.088, 0.074, 0.037, 0.025],                 # B 경활 2016~2025 평균 (20~24 / 25~29 / 30~34 / 35~39)
              "exit_m": 0.092, "invol_share": 0.274,                                               # C / B
              "contract_ratio": 12.3 / 9.9, "contract_share_pop": 0.34, "scar_per_year": 0.067},   # C / C / C
    "ui": {"floor": 198.144, "cap": 204.3, "replace": 0.60, "waiting_frac": 7 / 30, "gross_over_net": 1 / 0.88,
           "days_edges": [1, 3, 5, 10], "days": [120, 150, 180, 210, 240],
           "min_insured_m": 6, "window_m": 18},                                                       # A 피보험 180일(최근 18개월)                         # A 고용보험법
    "spend": {"raise_share": 0.45, "unemp_cut_ui": 0.068, "unemp_cut_noui": 0.22},                 # C 한계소비성향 / C Gruber(1997)
    "shock": {"prob_m": 0.0482, "amounts": [(120.0, 0.70), (300.0, 0.30)]},                        # D / E
    "overdraft": {"annual_rate": 0.0531}, "liquid": {"annual_rate": 0.0068},                       # B 한국은행 2025
}
POLICY = {"name": "청년미래적금", "cap": 50.0, "rate": 0.050, "term": 36, "match": 0.06, "match_cap": 3.0,
          "window": 6, "tax": 0.154, "age_max": 34, "gross_max_y": 7500.0, "rejoin_wait": 2, "rejoin": True,
          # 우리은행 상품설명서 중도해지 이율: (보유개월 하한, 기본이율 대비 비율). 3개월 미만은 연 0.1% 고정
          "early_table": [(3, 0.5), (6, 0.7), (9, 0.8), (11, 0.9)], "early_floor": 0.0015, "early_under3": 0.001}
GENERAL = {"rate": 0.0303, "early_ratio": 0.5, "tax": 0.154}                                       # B 금리 / E 중도해지 비율


def _arr(x, N, dtype=float):
    return np.broadcast_to(np.asarray(x, dtype=dtype), (N,)).copy()


def hazard(P, employment, age_now):
    U = P["unemp"]; u = np.asarray(U["u_by_age"])[np.searchsorted(U["age_edges"], age_now, side="right")]
    e = U["exit_m"]; h = u * e / (1 - u)
    c, r = U["contract_share_pop"], U["contract_ratio"]; base = 1.0 / ((1 - c) + c * r)
    mult = np.where(employment == "계약", base * r, np.where(employment == "정규", base, 1.0))
    return h * mult


def exp_prem(P, years):
    W = P["wage"]; return np.asarray(W["exp_prem"])[np.searchsorted(W["exp_edges"], years, side="right")]


def ui_months(P, tenure):
    d = np.asarray(P["ui"]["days"])[np.searchsorted(P["ui"]["days_edges"], tenure, side="right")]
    return np.rint(d / 30).astype(int)


def early_interest_ratio(policy, held):
    """중도해지 시 받는 이자 / 기본금리로 쌓인 이자 (보유개월 held, 계약 term)"""
    held = np.asarray(held, float); base = policy["rate"]
    ratio = np.zeros_like(held)
    for lo, k in policy["early_table"]:
        ratio = np.where(held >= lo, k, ratio)
    eff = np.where(held < 3, policy["early_under3"], np.maximum(base * ratio * held / policy["term"], policy["early_floor"]))
    return eff / base


def loan_schedule(loans, T):
    pay = np.zeros(T); bal_end = np.zeros(T)
    for ln in loans or []:
        B, i, n, m = float(ln["balance"]), ln["rate"] / 12, int(ln["months"]), ln.get("method", "annuity")
        A = B * i * (1 + i) ** n / ((1 + i) ** n - 1) if (m == "annuity" and i > 0) else (B / n if m == "annuity" else 0)
        for t in range(T):
            if B <= 1e-9: continue
            it = B * i
            if m == "annuity": p = min(A, B + it)
            elif m == "equal_principal": p = min(B, float(ln["balance"]) / n) + it
            elif m == "interest_only": p = it if t < n else 0.0          # 전세자금: 원금은 보증금 반환으로 상환 (현금흐름 밖)
            else: p = it + (B if t == n - 1 else 0)
            B = max(B - (p - it), 0.0) if m != "interest_only" else B; pay[t] += p; bal_end[t] += B
            if m == "interest_only" and t == n - 1: B = 0.0
    return pay, bal_end


def make_noise(N, T, seed, antithetic=True):
    """생성 순서 고정: z1, z2, u_onset, u_dur, u_invol, u_shock, u_amt"""
    if antithetic and N % 2: antithetic = False
    rng = np.random.Generator(np.random.PCG64(seed)); Y = T // 12 + 1
    if antithetic:
        a = rng.standard_normal((N // 2, T)); b = rng.standard_normal((N // 2, Y))
        z1, z2 = np.concatenate([a, -a]), np.concatenate([b, -b])
    else:
        z1, z2 = rng.standard_normal((N, T)), rng.standard_normal((N, Y))
    out = {"z1": z1, "z2": z2}
    for k in ["u_onset", "u_dur", "u_invol", "u_shock", "u_amt"]:
        out[k] = rng.random((N, T))
    return out


def zero_noise(T, N=1):
    Y = T // 12 + 1
    return {"z1": np.zeros((N, T)), "z2": np.zeros((N, Y)), "u_onset": np.ones((N, T)), "u_dur": np.zeros((N, T)),
            "u_invol": np.ones((N, T)), "u_shock": np.ones((N, T)), "u_amt": np.zeros((N, T))}


def _pick(u, pmf):
    v = np.array([a for a, _ in pmf]); c = np.cumsum([p for _, p in pmf])
    return v[np.clip(np.searchsorted(c, u, side="right"), 0, len(v) - 1)]


def _geom(u, e, cap):
    return np.minimum(1 + np.floor(np.log(np.clip(1 - u, 1e-12, 1)) / np.log(1 - e)).astype(int), cap)


def simulate(pr, P, noise, T, buffer_first=0.0, collect=False, policy=POLICY, general=GENERAL):
    """pr 필드는 스칼라 또는 길이 N 배열:
       age, exp_years, net_income, fixed, variable, cash0, save, employment, thr, invest0, invest_monthly
       loans[], events[] 는 모든 경로 공통"""
    N = noise["z1"].shape[0]; Pi, Wg, U, UI = P["inflation"], P["wage"], P["unemp"], P["ui"]
    age0 = _arr(pr["age"], N); exp0 = _arr(pr["exp_years"], N)
    net0 = _arr(pr["net_income"], N); sfix = _arr(pr["fixed"], N); svar = _arr(pr["variable"], N)
    save = _arr(pr["save"], N); thr = _arr(pr.get("thr", 50.0), N); inv_m = _arr(pr.get("invest_monthly", 0.0), N)
    emp_type = _arr(pr.get("employment", "정규"), N, dtype=object).astype(str)
    gross_y = net0 * 12 * UI["gross_over_net"]
    income_ok = gross_y <= policy["gross_max_y"] + 1e-6                                 # 경계값 포함 (부동소수점 오차 방지)
    eligible = (age0 <= policy["age_max"]) & income_ok
    a1 = np.where(eligible, np.minimum(save, policy["cap"]), 0.0)
    e = U["exit_m"]
    loan_pay, loan_bal = loan_schedule(pr.get("loans"), T)
    ev = sorted(pr.get("events") or [], key=lambda x: x["t"])

    eps_sd = Pi["sigma_y"] * np.sqrt(1 - Pi["phi"] ** 2)
    sig = float(np.hypot(Wg["macro_sd"], Wg["idio_sd"])); rho = Wg["rho_macro"] * Wg["macro_sd"] / sig if sig > 0 else 0.0
    g_rate = float(pr["general_rate"]) if pr.get("general_rate") is not None else general["rate"]   # 사용자가 아는 적금 금리 (없으면 시장 평균)
    r_od, r_L, r_P, r_G = P["overdraft"]["annual_rate"] / 12, P["liquid"]["annual_rate"] / 12, policy["rate"] / 12, g_rate / 12
    ui_amt = np.clip(UI["replace"] * net0 * UI["gross_over_net"], UI["floor"], UI["cap"])

    L = _arr(pr["cash0"], N); od = np.zeros(N); INV = _arr(pr.get("invest0", 0.0), N); DEP = np.zeros(N)
    P1 = np.zeros(N); C1 = np.zeros(N); M1 = np.zeros(N); held = np.zeros(N, int); act1 = a1 > 0
    cum_held = np.zeros(N, int); match_mult = np.ones(N); wait = np.full(N, -1)
    G = np.zeros(N); CG = np.zeros(N); g_held = np.zeros(N, int); Mp = np.zeros(N)   # 일반 적금 회차 개월, 기여금 원금
    started = (L >= buffer_first) | (buffer_first <= 0)
    base = net0.copy(); income_mult = np.ones(N); cum = np.ones(N); pi_prev = np.full(N, Pi["last"]); m_inf = np.zeros(N)
    unemp = np.zeros(N, int); ui_left = np.zeros(N, int); ui_first = np.zeros(N, bool); since = np.full(N, 10 ** 6)
    gap_len = np.zeros(N, int); vol_gap = np.zeros(N, int); last_shock = np.full(N, -99); extra_fixed = 0.0
    insured = np.minimum(exp0 * 12, UI["window_m"])                                   # 최근 18개월 중 피보험 개월(근사)
    f = {k: np.zeros(N, bool) for k in ["unemp", "overdraft", "brk1_sp", "brk1_gen", "brkG", "mature1", "limit",
                                       "cause_shock", "cause_deficit", "cause_unemp", "rejoin", "c1_sp", "c1_unemp", "c1_shock", "c1_deficit"]}
    first_brk_t = np.full(N, -1); match_paid = np.zeros(N); contrib_policy = np.zeros(N)
    saved = np.zeros((N, T + 1)) if collect else None
    if collect: saved[:, 0] = L + P1 + G + INV + DEP - od

    for t in range(T):
        age_now = age0 + t / 12; exp_now = exp0 + t / 12
        # ① 연초: 물가 AR(1), 연봉 인상
        if t % 12 == 0:
            n = min(12, T - t); Zy = noise["z1"][:, t:t + n].sum(1) / np.sqrt(n)
            pi = Pi["mu_y"] + Pi["phi"] * (pi_prev - Pi["mu_y"]) + eps_sd * Zy
            m_inf = (1 + pi) ** (1 / 12) - 1; pi_prev = pi
            if t > 0:
                Z1 = noise["z1"][:, t - 12:t].sum(1) / np.sqrt(12)
                g = Wg["macro_mu"] + exp_prem(P, exp_now - 1) + sig * (rho * Z1 + np.sqrt(1 - rho ** 2) * noise["z2"][:, t // 12 - 1])
                base = base * (1 + g)
        cum = cum * (1 + m_inf)
        # ② 예정 이벤트
        lump = 0.0; dep_add = 0.0
        for x in ev:
            if x["t"] != t: continue
            if x["kind"] == "lump": lump += x["amount"]
            elif x["kind"] == "spend": extra_fixed += x["delta"]
            elif x["kind"] == "move": dep_add += x.get("deposit", 0.0); extra_fixed += x.get("delta", 0.0); lump += x.get("cost", 0.0)
            elif x["kind"] == "income":
                income_mult = income_mult * (1 + x.get("pct", 0.0))
                if x.get("gap", 0) > 0: vol_gap = np.where(unemp == 0, int(x["gap"]), vol_gap)
        # ③ 고용 (연령별 실직 위험)
        h = hazard(P, emp_type, age_now)
        start = (unemp == 0) & (vol_gap == 0) & (noise["u_onset"][:, t] < h)
        D = _geom(noise["u_dur"][:, t], e, T); invol = noise["u_invol"][:, t] < U["invol_share"]
        unemp = np.where(start, D, unemp); gap_len = np.where(start, D, gap_len)
        ui_ok = invol & (insured >= UI["min_insured_m"])                                    # 비자발 + 피보험 180일 이상
        ui_left = np.where(start & ui_ok, ui_months(P, exp_now), np.where(start, 0, ui_left))
        ui_first = np.where(start & ui_ok, True, ui_first); since = np.where(start, 0, since)
        vg = vol_gap > 0
        unemp = np.where(vg, np.maximum(unemp, vol_gap), unemp); gap_len = np.where(vg, vol_gap, gap_len)
        since = np.where(vg, 0, since); vol_gap[:] = 0
        employed = unemp == 0; f["unemp"] |= ~employed
        income = np.where(employed, base * income_mult, np.where(ui_left > 0, ui_amt * np.where(ui_first, 1 - UI["waiting_frac"], 1), 0.0))
        # ④ 월초 이자
        od = od * (1 + r_od); L = L * (1 + r_L)
        # ⑤ 급전
        hit = noise["u_shock"][:, t] < P["shock"]["prob_m"]
        shock = np.where(hit, _pick(noise["u_amt"][:, t], P["shock"]["amounts"]) * cum, 0.0);   # 급전 금액도 물가 반영
        last_shock = np.where(hit, t, last_shock)
        # ⑥ 현금 흐름 (실직 중 소비 감소)
        lifestyle = P["spend"]["raise_share"] * np.maximum(base * income_mult - net0 * cum, 0)
        cut = np.where(employed, 0.0, np.where(ui_left > 0, P["spend"]["unemp_cut_ui"], P["spend"]["unemp_cut_noui"]))
        living = ((sfix + svar + extra_fixed) * cum + lifestyle) * (1 - cut)
        L = L + income - living - shock - lump - loan_pay[t] - dep_add; DEP = DEP + dep_add
        # ⑦ 마이너스 우선 상환
        rep = np.minimum(np.maximum(L, 0), od); L -= rep; od -= rep
        # ⑧ 재가입 (해지월 + 2개월, 나이·소득 자격, 기여금 비율 축소)
        if policy["rejoin"]:
            wait = np.where(wait > 0, wait - 1, wait)
            rj = (wait == 0) & (age_now <= policy["age_max"]) & income_ok & (cum_held < policy["term"])
            act1 = act1 | rj; held = np.where(rj, 0, held); f["rejoin"] |= rj
            match_mult = np.where(rj, (policy["term"] - cum_held) / policy["term"], match_mult); wait = np.where(rj, -1, wait)
        # ⑨ 납입: 비상금 우선이면 L ≥ B 이후. 정책적금 → 일반 적금 → 투자
        started |= L >= buffer_first
        can = employed & started
        put1 = np.where(can & act1, np.minimum(a1, np.maximum(L, 0)), 0.0); L -= put1
        P1 += put1; C1 += put1; contrib_policy += put1; m_new = np.minimum(policy["match"] * put1, policy["match_cap"]) * match_mult; M1 += m_new; Mp += m_new
        rest = np.where(act1, save - a1, save)
        putG = np.where(can, np.minimum(rest, np.maximum(L, 0)), 0.0); L -= putG; G += putG; CG += putG
        putI = np.where(employed, np.minimum(inv_m, np.maximum(L, 0)), 0.0); L -= putI; INV += putI
        # 적금 이자 (납입 직후 한 달치): 입금건별 단리 → 입금한 달부터 만기 전달까지 매달 원금 × 연이율/12
        P1 = np.where(act1, P1 + C1 * r_P, P1); M1 = np.where(act1, M1 + Mp * r_P, M1)   # 청년미래적금 (상품설명서)
        hasG = CG > 0                                                                       # 일반 적금: 1년 만기마다 세후 이자 재예치
        G = np.where(hasG, G + CG * r_G, G); g_held = np.where(hasG, g_held + 1, 0)
        roll = g_held >= 12
        CG = np.where(roll, CG + (G - CG) * (1 - general["tax"]), CG); G = np.where(roll, CG, G); g_held = np.where(roll, 0, g_held)
        # ⑩ 만기
        held = held + act1; cum_held = cum_held + act1; mat = act1 & (held >= policy["term"])
        match_paid += np.where(mat, M1, 0.0)
        L = L + np.where(mat, P1 + M1, 0.0); P1 = np.where(mat, 0, P1); C1 = np.where(mat, 0, C1); M1 = np.where(mat, 0, M1); Mp = np.where(mat, 0, Mp)
        f["mature1"] |= mat; act1 = act1 & ~mat
        # ⑪ 부족분 → 마이너스
        deficit = np.maximum(-L, 0); od += deficit; L = np.maximum(L, 0); f["overdraft"] |= deficit > 0
        # ⑫ 해지: 일반 적금 먼저, 그다음 정책적금 (투자·보증금 미사용)
        needG = (od > thr) & (G > 0)
        prG = np.where(needG, CG + (G - CG) * general["early_ratio"] * (1 - general["tax"]), 0.0)
        r2 = np.minimum(prG, od); od -= r2; L += prG - r2; G = np.where(needG, 0, G); CG = np.where(needG, 0, CG); g_held = np.where(needG, 0, g_held); f["brkG"] |= needG
        need1 = act1 & (od > thr); sp = need1 & (since <= policy["window"]); gn = need1 & ~sp
        er = early_interest_ratio(policy, held)
        pr1 = np.where(sp, P1 + M1, np.where(gn, C1 + (P1 - C1) * er * (1 - policy["tax"]), 0.0)); match_paid += np.where(sp, M1, 0.0)
        r3 = np.minimum(pr1, od); od -= r3; L += pr1 - r3
        P1 = np.where(need1, 0, P1); C1 = np.where(need1, 0, C1); M1 = np.where(need1, 0, M1); Mp = np.where(need1, 0, Mp); act1 = act1 & ~need1
        wait = np.where(need1, policy["rejoin_wait"], wait)
        first = need1 & (first_brk_t < 0); first_brk_t = np.where(first, t, first_brk_t)
        f["c1_sp"] |= first & sp; f["c1_unemp"] |= first & gn & ~employed                   # 첫 해지 원인 (합 100%)
        f["c1_shock"] |= first & gn & employed & (t - last_shock <= 2); f["c1_deficit"] |= first & gn & employed & (t - last_shock > 2)
        f["brk1_sp"] |= sp; f["brk1_gen"] |= gn
        f["cause_unemp"] |= gn & ~employed                                             # 공백이 6개월을 넘겨 깬 경우도 실업 원인
        f["cause_shock"] |= gn & employed & (t - last_shock <= 2); f["cause_deficit"] |= gn & employed & (t - last_shock > 2)
        f["limit"] |= od > thr
        # ⑬ 카운터 + 재취업 낙인
        ending = (~employed) & (unemp == 1)
        base = np.where(ending, base * np.maximum(1 - U["scar_per_year"] * gap_len / 12, 0.5), base)
        insured = np.where(employed, np.minimum(insured + 1, UI["window_m"]), np.maximum(insured - 1, 0))
        unemp = np.maximum(unemp - 1, 0)
        ui_left = np.where(~employed, np.maximum(ui_left - 1, 0), ui_left); ui_first = np.where(~employed, False, ui_first)
        since = since + 1
        if collect: saved[:, t + 1] = L + P1 + G + INV + DEP - od

    final = L + P1 + G + INV + DEP - od
    return {"final": final, "saved": saved, "flags": f, "first_brk_t": first_brk_t, "eligible": eligible,
            "match_paid": match_paid, "contrib": contrib_policy,
            "events": {k: float(v.mean()) for k, v in f.items()}}


def summarize(res, goal, goal_month):
    s = res["saved"]; hitm = s[:, goal_month] >= goal; p = float(hitm.mean()); N = len(hitm)
    first = np.where((s >= goal).any(1), (s >= goal).argmax(1), np.inf)
    cps = {m: {q: float(np.quantile(s[:, m], q)) for q in (.05, .1, .5, .9)} for m in (12, 36, 60, 120) if m < s.shape[1]}
    f = res["flags"]; gen = f["brk1_gen"].sum()
    return {"prob": p, "se": float(np.sqrt(p * (1 - p) / N)), "first_median": float(np.median(first)), "checkpoints": cps,
            "events": res["events"],
            "cause_share": _first_cause(f)}


def _first_cause(f):
    """첫 해지 기준 원인 비율 (합 100%)"""
    n = (f["c1_sp"] | f["c1_unemp"] | f["c1_shock"] | f["c1_deficit"]).sum()
    if n == 0: return {"실직": 0.0, "실업 장기화": 0.0, "급전": 0.0, "상시 적자": 0.0}
    return {"실직": float(f["c1_sp"].sum() / n), "실업 장기화": float(f["c1_unemp"].sum() / n),
            "급전": float(f["c1_shock"].sum() / n), "상시 적자": float(f["c1_deficit"].sum() / n)}


def compare(a, b):
    d = a.astype(float) - b.astype(float); sd = d.std(ddof=1)
    return float(d.mean()), (0.0 if sd == 0 else float(d.mean() / (sd / np.sqrt(len(d)))))
