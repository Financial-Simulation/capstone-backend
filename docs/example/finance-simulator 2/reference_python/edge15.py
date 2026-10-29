import numpy as np, copy
from kernel import *
from validate_input import validate
B = dict(age=27, exp_years=2, net_income=250, fixed=95, variable=95, cash0=200, save=50, goal=3000, goal_month=60, employment="정규", thr=50,
         loans=[{"balance": 1200, "rate": 0.019, "months": 96}])
reject = {
 "소득 0": dict(B, net_income=0), "음수 지출": dict(B, fixed=-1), "NaN": dict(B, cash0=float("nan")), "무한대": dict(B, goal=float("inf")),
 "문자열": dict(B, net_income="250"), "불리언": dict(B, save=True), "극단값": dict(B, cash0=1e9), "필수 누락": {k: v for k, v in B.items() if k != "goal"},
 "나이 18": dict(B, age=18), "나이 45": dict(B, age=45), "경력 음수": dict(B, exp_years=-1), "경력 > 나이": dict(B, age=20, exp_years=8),
 "고용형태 오타": dict(B, employment="정규직"), "목표 기간 6개월": dict(B, goal_month=6), "목표 기간 150개월": dict(B, goal_month=150),
 "목표 기간 소수": dict(B, goal_month=36.5), "대출 기간 0": dict(B, loans=[{"balance": 1200, "rate": 0.019, "months": 0}]),
 "대출 금리 음수": dict(B, loans=[{"balance": 1200, "rate": -0.01, "months": 96}]), "상환방식 오타": dict(B, loans=[{"balance": 1200, "rate": 0.019, "months": 96, "method": "x"}]),
 "이벤트 종류 오타": dict(B, events=[{"t": 12, "kind": "wedding", "amount": 100}]), "이벤트 시점 초과": dict(B, events=[{"t": 130, "kind": "lump", "amount": 100}]),
 "이직 공백 과대": dict(B, events=[{"t": 12, "kind": "income", "pct": 0.1, "gap": 30}]), "보증금 음수": dict(B, events=[{"t": 12, "kind": "move", "deposit": -100}]),
}
warn = {"지출 > 소득": dict(B, variable=200), "저축 > 여유자금": dict(B, save=100), "목표 ≤ 현재": dict(B, goal=100), "만 35세": dict(B, age=35, exp_years=5),
        "버틸 금액 0": dict(B, thr=0), "모든 이벤트 동시": dict(B, events=[{"t": 0, "kind": "lump", "amount": 500}, {"t": 0, "kind": "move", "deposit": 3000, "delta": 50}, {"t": 0, "kind": "income", "pct": -0.5, "gap": 12}])}
ok = 0
for name, pr in reject.items():
    e, w = validate(pr); r = len(e) > 0; ok += r; print(f"[거부] {name:<12} {'통과' if r else '!! 실패'}  {e[:1]}")
for name, pr in warn.items():
    e, w = validate(pr)
    res = simulate(pr, PARAMS, make_noise(400, pr["goal_month"], 3), pr["goal_month"], collect=True)
    clean = (not e) and np.isfinite(res["saved"]).all()
    ok += clean; print(f"[실행] {name:<12} {'통과' if clean else '!! 실패'}  경고 {w[:1]}")
print(f"\n엣지 입력 {ok}/{len(reject)+len(warn)}")
