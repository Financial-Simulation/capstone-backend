"""입력 검증 규칙 (v1.5 입력 전체) — 반환: (errors, warnings)"""
import numpy as np
LIMITS = {"age": (19, 39), "exp_years": (0, 10), "net_income": (1.0, 3000.0), "fixed": (0.0, 3000.0), "variable": (0.0, 3000.0),
          "cash0": (0.0, 100000.0), "save": (0.0, 3000.0), "thr": (0.0, 10000.0), "invest0": (0.0, 100000.0),
          "invest_monthly": (0.0, 3000.0), "goal": (1.0, 100000.0), "goal_month": (12, 120)}
REQUIRED = ["age", "exp_years", "net_income", "fixed", "variable", "cash0", "save", "goal", "goal_month"]
def _num(v): return isinstance(v, (int, float, np.integer, np.floating)) and not isinstance(v, bool)
def validate(pr):
    E, W = [], []
    for k in REQUIRED:
        if k not in pr: E.append(f"{k}: 필수 입력")
    for k, (lo, hi) in LIMITS.items():
        if k not in pr: continue
        v = pr[k]
        if not _num(v): E.append(f"{k}: 숫자가 아님"); continue
        if not np.isfinite(v): E.append(f"{k}: NaN/무한대"); continue
        if v < lo or v > hi: E.append(f"{k}: 허용 범위 {lo}~{hi} 벗어남")
    if _num(pr.get("goal_month", 12)) and pr.get("goal_month", 12) % 1 != 0: E.append("goal_month: 정수 개월")
    if pr.get("employment", "정규") not in ("정규", "계약"): E.append("employment: 정규/계약")
    if _num(pr.get("age")) and _num(pr.get("exp_years")) and pr["exp_years"] > pr["age"] - 15: E.append("exp_years: 나이에 비해 경력이 김")
    for i, ln in enumerate(pr.get("loans") or []):
        if not all(_num(ln.get(k)) and np.isfinite(ln.get(k)) for k in ("balance", "rate", "months")): E.append(f"loans[{i}]: 숫자 누락"); continue
        if ln["balance"] < 0 or not (0 <= ln["rate"] <= 0.2) or ln["months"] < 1 or ln["months"] > 480: E.append(f"loans[{i}]: 범위 오류")
        if ln.get("method", "annuity") not in ("annuity", "equal_principal", "bullet", "interest_only"): E.append(f"loans[{i}]: 상환방식 오류")
    for i, ev in enumerate(pr.get("events") or []):
        if ev.get("kind") not in ("lump", "spend", "income", "move"): E.append(f"events[{i}]: 종류 오류"); continue
        if not (_num(ev.get("t")) and 0 <= ev["t"] < 120): E.append(f"events[{i}]: 시점 0~119개월")
        if ev["kind"] == "income" and not (-0.9 <= ev.get("pct", 0) <= 2.0 and 0 <= ev.get("gap", 0) <= 24): E.append(f"events[{i}]: 소득 변화·공백 범위")
        if ev["kind"] == "move" and ev.get("deposit", 0) < 0: E.append(f"events[{i}]: 보증금 음수")
        if ev["kind"] == "lump" and not (_num(ev.get("amount")) and ev["amount"] >= 0): E.append(f"events[{i}]: 금액 오류")
    if not E:
        if pr["fixed"] + pr["variable"] >= pr["net_income"]: W.append("지출이 소득 이상: 매달 부족분 발생")
        if pr["save"] > pr["net_income"] - pr["fixed"] - pr["variable"]: W.append("저축액이 월 여유자금보다 큼: 납입이 부족할 수 있음")
        if pr["goal"] <= pr["cash0"] + pr.get("invest0", 0): W.append("목표가 현재 모은 돈 이하: 이미 달성")
        if pr["age"] > 34: W.append("만 34세 초과: 청년미래적금 대신 일반 적금 규칙 적용")
    return E, W
