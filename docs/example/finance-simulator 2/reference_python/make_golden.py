"""엔진 대조용 정답 파일 생성: 같은 난수(noise)와 파이썬 기준 구현의 결과를 golden.json으로 저장
   사용: python make_golden.py  →  ../engine/golden.json"""
import json
from kernel import *
N, T = 100, 120; nz = make_noise(N, T, 2026)
cases = [
 ("S1", dict(age=27, exp_years=2, net_income=250, fixed=95, variable=95, cash0=200, save=50, employment="정규", thr=50, loans=[{"balance": 1200, "rate": 0.019, "months": 96}]), {}),
 ("S2 비상금200", dict(age=24, exp_years=0, net_income=230, fixed=85, variable=80, cash0=0, save=50, employment="정규", thr=50, loans=[{"balance": 800, "rate": 0.017, "months": 120}]), {"bufferFirst": 200}),
 ("S3 이사", dict(age=36, exp_years=8, net_income=320, fixed=110, variable=100, cash0=1500, save=60, employment="계약", thr=100, events=[{"t": 12, "kind": "move", "deposit": 1000, "delta": -30, "cost": 150}]), {}),
 ("계약·원금균등·만기일시", dict(age=29, exp_years=4, net_income=280, fixed=120, variable=90, cash0=50, save=70, employment="계약", thr=30, loans=[{"balance": 600, "rate": 0.045, "months": 36, "method": "equal_principal"}, {"balance": 300, "rate": 0.06, "months": 24, "method": "bullet"}]), {}),
 ("이직·결혼·지출변화·투자", dict(age=26, exp_years=1, net_income=240, fixed=90, variable=85, cash0=100, save=40, employment="정규", thr=80, invest0=500, invest_monthly=10, events=[{"t": 18, "kind": "income", "pct": 0.15, "gap": 3}, {"t": 30, "kind": "lump", "amount": 800}, {"t": 40, "kind": "spend", "delta": 25}]), {}),
 ("재가입 없음", dict(age=25, exp_years=0.5, net_income=220, fixed=90, variable=80, cash0=0, save=50, employment="계약", thr=0), {"rejoin": False}),
 ("자격 미달 35세", dict(age=35, exp_years=9, net_income=300, fixed=100, variable=100, cash0=300, save=50, employment="정규", thr=50), {}),
 ("전세대출 이자만+학자금", dict(age=30, exp_years=5, net_income=300, fixed=70, variable=100, cash0=400, save=50, employment="정규", thr=100, loans=[{"balance": 1000, "rate": 0.017, "months": 60, "method": "annuity"}, {"balance": 8000, "rate": 0.043, "months": 24, "method": "interest_only"}]), {}),
 ("적금 금리 직접 입력 4.5%", dict(age=36, exp_years=8, net_income=320, fixed=110, variable=100, cash0=1500, save=60, employment="계약", thr=100, general_rate=0.045), {}),
 ("고소득 자격 미달", dict(age=28, exp_years=3, net_income=560, fixed=150, variable=150, cash0=500, save=100, employment="정규", thr=200), {}),
 # 해지 원인 우선순위: 실직 중 일반해지(실업 장기화)와, 그때 직전 3개월 안 급전이 겹친 경로
 ("실업 장기화·버틸 금액 400", dict(age=21, exp_years=1, net_income=200, fixed=85, variable=75, cash0=300, save=40, employment="계약", thr=400), {}),
 # 이직 반복: 짧은 실직 뒤 남은 실업급여 개월이 이직 공백에 쓰이는 경로 (명세서 9.2)
 ("이직 반복·남은 실업급여", dict(age=23, exp_years=2, net_income=220, fixed=80, variable=80, cash0=50, save=40, employment="계약", thr=40,
     events=[{"t": 20, "kind": "income", "pct": 0.05, "gap": 2}, {"t": 44, "kind": "income", "pct": 0.05, "gap": 3},
             {"t": 68, "kind": "income", "pct": 0.05, "gap": 2}, {"t": 92, "kind": "income", "pct": 0.05, "gap": 2}]), {}),
]
out = {"N": N, "T": T, "noise": {k: v.tolist() for k, v in nz.items()}, "cases": []}
for nm, pr, o in cases:
    pol = dict(POLICY, rejoin=o.get("rejoin", True))
    r = simulate(pr, PARAMS, nz, T, collect=True, buffer_first=o.get("bufferFirst", 0), policy=pol)
    d = simulate(pr, PARAMS, zero_noise(T), T, collect=True, buffer_first=o.get("bufferFirst", 0), policy=pol)
    out["cases"].append(dict(name=nm, pr=pr, opt=o, saved=r["saved"].tolist(), det=d["saved"][0].tolist(),
        flags={k: v.astype(int).tolist() for k, v in r["flags"].items() if k in ["unemp","overdraft","brk1_sp","brk1_gen","brkG","mature1","limit","rejoin","c1_sp","c1_unemp","c1_shock","c1_deficit"]},
        firstBrk=r["first_brk_t"].tolist(), match=r["match_paid"].tolist()))
import os
json.dump(out, open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "engine", "golden.json"), "w"), ensure_ascii=False); print("golden.json 저장")
