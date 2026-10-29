"""params.json 생성: 엔진 값은 기준 구현 kernel.py v1.6에서 그대로 가져오고, 근거(등급·출처·기간)는 설계 보고서 5.2·5.3·9.1에서 옮김
   사용: python build_params.py  →  params.json
   백엔드는 이 파일을 서버 시작 때 한 번 읽는다 (읽기 전용). 값을 바꾸려면 kernel.py를 고치고 이 스크립트를 다시 돌린다."""
import json, os, sys, math
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "reference_python"))
from kernel import PARAMS, POLICY, GENERAL


def plain(x):
    """튜플 → 리스트 (JSON 배열). 숫자는 그대로 두어 double 값이 바뀌지 않게 한다."""
    if isinstance(x, dict): return {k: plain(v) for k, v in x.items()}
    if isinstance(x, (list, tuple)): return [plain(v) for v in x]
    return x


# 결과 화면 '근거'와 GET /api/meta 의 parameters (API 명세 6.9와 같은 11개, asOf는 보고서 9.1의 자료 기간)
META = [
    {"key": "unemploymentByAge", "label": "연령별 실업률", "value": "20~24세 8.8%, 25~29세 7.4%, 30~34세 3.7%, 35~39세 2.5%",
     "grade": "B", "source": "경제활동인구조사 성·연령별 실업률 (월별 값의 평균)", "asOf": "2016~2025", "paths": ["params.unemp.u_by_age", "params.unemp.age_edges"]},
    {"key": "reemploymentRate", "label": "실직 후 다시 일할 확률", "value": "매달 9.2% (평균 11개월)",
     "grade": "C", "source": "국회예산정책처 NABO Focus 제157호 「청년층 첫 직장 현황 및 시사점」", "asOf": "2026.4 발간", "paths": ["params.unemp.exit_m"]},
    {"key": "unemploymentBenefit", "label": "실업급여", "value": "월 198~204만 원, 근무 기간에 따라 4~8개월",
     "grade": "A", "source": "고용보험법 (하한 일 66,048원·상한 68,100원, 대기 7일)", "asOf": "2026", "paths": ["params.ui"]},
    {"key": "wageGrowth", "label": "연봉 인상", "value": "모두 함께 2.85% + 경력에 따라 4.0~6.5%",
     "grade": "B", "source": "고용형태별근로실태조사 경력별 임금", "asOf": "2010~2025", "paths": ["params.wage.macro_mu", "params.wage.macro_sd", "params.wage.exp_edges", "params.wage.exp_prem"]},
    {"key": "wageDispersion", "label": "개인별 연봉 차이", "value": "연 11.5%",
     "grade": "C", "source": "임금근로일자리 소득 통계 연령대별 평균·중위소득", "asOf": "2016~2024", "paths": ["params.wage.idio_sd", "params.wage.rho_macro"]},
    {"key": "inflation", "label": "물가", "value": "연 2.08% (전년 영향 0.55)",
     "grade": "B", "source": "소비자물가조사 연도별 소비자물가 등락률", "asOf": "2010~2025", "paths": ["params.inflation"]},
    {"key": "emergencyExpense", "label": "급한 지출", "value": "월 4.82% 확률, 120만 원(70%)·300만 원(30%), 물가 반영",
     "grade": "D·E", "source": "청년도약계좌 해지율로 보정(확률, D), 청년희망적금으로 검증. 금액은 가정(E)", "asOf": "2025.7 (보정 기준 해지율)", "paths": ["params.shock"]},
    {"key": "spendingRaise", "label": "소득이 오를 때 지출도 오르는 비율", "value": "늘어난 소득의 45%",
     "grade": "C", "source": "가계금융복지조사 실질 한계소비성향", "asOf": "2017~2025", "paths": ["params.spend.raise_share"]},
    {"key": "unemploymentSpendingCut", "label": "실직 중 지출 감소", "value": "실업급여 받으면 6.8%, 못 받으면 22%",
     "grade": "C", "source": "Gruber (1997), American Economic Review 87(1)", "asOf": "1997", "paths": ["params.spend.unemp_cut_ui", "params.spend.unemp_cut_noui"]},
    {"key": "youthFutureSaving", "label": "청년미래적금", "value": "월 50만 원 한도, 3년, 연 5.0% 단리, 지원금 6%",
     "grade": "A", "source": "우리은행 「우리 청년미래적금」 상품설명서", "asOf": "2026.9", "paths": ["policy"]},
    {"key": "rates", "label": "통장·일반 적금·마이너스 금리", "value": "연 0.68% · 3.03% · 5.31%",
     "grade": "B", "source": "한국은행 금융기관 가중평균금리 (2025년 연평균)", "asOf": "2025", "paths": ["params.liquid", "general.rate", "params.overdraft"]},
]

# 엔진은 쓰지만 META 11개에 묶이지 않은 값의 근거 (보고서 5.2) — 내부 확인용
OTHER_GRADES = {
    "params.unemp.invol_share": ("B", "NABO Focus 제157호 (실업급여 대상 비자발 이직)"),
    "params.unemp.contract_ratio": ("C", "NABO Focus 제157호, 첫 직장 형태별 현재 실업자 비중 12.3% vs 9.9%"),
    "params.unemp.contract_share_pop": ("C", "전체 평균 실직 위험이 연령별 실업률과 같도록 맞추는 계약직 비중. 보고서에 출처 표기 없음 (kernel.py 주석 등급 C) — 확인 필요"),
    "params.unemp.scar_per_year": ("C", "NABO Focus 제157호가 인용한 이재호(2026, 한국은행)"),
    "general.early_ratio": ("E", "은행별 상이, 약정이율의 50% 가정"),
    "general.tax": ("A", "이자소득세 15.4% (소득세 14% + 지방소득세 1.4%)"),
}

out = {
    "schemaVersion": 1,
    "modelVersion": "1.6",
    "builtFrom": "reference_python/kernel.py v1.6 (값), 설계 보고서 5.2·5.3·9.1 (근거)",
    "units": "금액 만 원, 금리·비율 연 소수, 기간 개월 (구현 명세서 0장)",
    "simulation": {
        "defaultSeed": 20261007, "paths": 2000, "horizonMonths": 120, "targetProbability": 0.70,
        "checkpointsMonths": [12, 36, 60, 120], "quantiles": [0.05, 0.10, 0.50, 0.90],
        "meaningfulDiff": 0.02, "zThreshold": 1.96,
        "emergencyFirstOptions": [0, 100, 200, 300, 500],
    },
    "params": plain(PARAMS),
    "policy": plain(POLICY),
    "general": plain(GENERAL),
    "loanDefaults": {
        "STUDENT": {"annualRate": 1.7, "repaymentType": "EQUAL_PAYMENT"},
        "JEONSE": {"annualRate": 4.3, "repaymentType": "INTEREST_ONLY"},
        "CREDIT": {"annualRate": 6.3, "repaymentType": "EQUAL_PAYMENT"},
        "MORTGAGE": {"annualRate": 4.7, "repaymentType": "EQUAL_PAYMENT"},
        "OTHER": {"annualRate": 5.0, "repaymentType": "EQUAL_PAYMENT"},
    },
    # GET /api/events/presets (API 명세 6.10). 금액은 원 단위 (API 입력과 같은 단위)
    "eventPresets": [
        {"presetKey": "INDEPENDENCE", "name": "독립", "basis": "가정값, 사용자가 고침",
         "events": [{"eventType": "MOVE", "deposit": 5000000, "monthlyChange": 450000, "amount": 800000}]},
        {"presetKey": "JEONSE_MOVE", "name": "전세 이사", "basis": "가정값, 사용자가 고침",
         "events": [{"eventType": "MOVE", "deposit": 10000000, "monthlyChange": -300000, "amount": 1500000}]},
        {"presetKey": "WEDDING", "name": "결혼 비용", "basis": "가정값, 사용자가 고침",
         "events": [{"eventType": "ONE_TIME", "amount": 8000000}]},
        {"presetKey": "JOB_CHANGE", "name": "이직", "basis": "가정값, 사용자가 고침",
         "events": [{"eventType": "JOB_CHANGE", "incomeChangeRate": 10.0, "gapMonths": 2}]},
        {"presetKey": "CAR", "name": "자동차", "basis": "가정값, 사용자가 고침",
         "events": [{"eventType": "ONE_TIME", "amount": 5000000}, {"eventType": "SPEND_CHANGE", "monthlyChange": 300000}]},
    ],
    # 가능성 범위(구현 명세서 7.5): B~D 등급 값의 불확실 범위. 뽑는 순서는 명세서 7.5가 정한다
    "robust": {
        "trials": 60, "pathsPerTrial": 2000, "paramSeed": 12345, "noiseSeedBase": 900,
        "uniform": {
            "params.inflation.mu_y": [0.0138, 0.0278], "params.inflation.sigma_y": [0.0097, 0.0204], "params.inflation.phi": [0.1, 0.9],
            "params.wage.macro_mu": [0.018, 0.038], "params.wage.idio_sd": [0.006, 0.147], "params.wage.rho_macro": [0.21, 0.87],
            "params.wage.exp_prem[3]": [0.0367, 0.0401], "params.unemp.u_by_age (배수)": [0.78, 1.0],
            "params.unemp.invol_share": [0.20, 0.35], "params.unemp.scar_per_year": [0.03, 0.10], "params.spend.raise_share": [0.24, 0.50],
        },
        "exitByStructure": {"long": 0.092, "short": 0.241},
        "shockAmountMultipliers": [0.67, 1.0, 1.5],
        "cohortVariants": ["base", "buf3", "buf12", "spend"],
        "shockProbByStructureAmountCohort": {
            "long|0.67|base": 0.09358, "long|0.67|buf3": 0.06386, "long|0.67|buf12": 0.1421, "long|0.67|spend": 0.06998,
            "long|1.0|base": 0.04817, "long|1.0|buf3": 0.03324, "long|1.0|buf12": 0.07747, "long|1.0|spend": 0.03681,
            "long|1.5|base": 0.02733, "long|1.5|buf3": 0.01914, "long|1.5|buf12": 0.04436, "long|1.5|spend": 0.02133,
            "short|0.67|base": 0.06882, "short|0.67|buf3": 0.03865, "short|0.67|buf12": 0.12156, "short|0.67|spend": 0.04469,
            "short|1.0|base": 0.03552, "short|1.0|buf3": 0.01997, "short|1.0|buf12": 0.06605, "short|1.0|spend": 0.02341,
            "short|1.5|base": 0.0202, "short|1.5|buf3": 0.01148, "short|1.5|buf12": 0.03809, "short|1.5|spend": 0.01358},
    },
    "meta": {
        "gradeLegend": {"A": "법령·제도", "B": "공공통계 직접 사용", "C": "공공통계를 모델 형태로 변환", "D": "보정(다른 통계에 맞춰 역산)", "E": "가정(민감도 분석 필수)"},
        "editableInputs": ["savingPlan.generalSavingRate"],
        "parameters": META,
        "otherGrades": {k: {"grade": g, "source": s} for k, (g, s) in OTHER_GRADES.items()},
        "validation": {
            "golden": "같은 난수를 넣으면 파이썬 기준 구현과 1조분의 1 이내 일치 (JS 시제품, 12개 프로필)",
            "backtest": "청년희망적금 3개 시점 실제 해지율과 차이 0.6~0.9%p",
            "robustness": "가정 60가지 변경에도 비교 결론 60번 유지",
        },
    },
}

# 자체 확인: JSON으로 쓰고 다시 읽었을 때 엔진 값이 kernel.py와 비트까지 같은가
text = json.dumps(out, ensure_ascii=False, indent=1)
back = json.loads(text)
assert back["params"] == plain(PARAMS) and back["policy"] == plain(POLICY) and back["general"] == plain(GENERAL)
for m in META:
    assert m["grade"] and m["source"] and m["asOf"], m["key"]
def walk(o):
    if isinstance(o, dict): [walk(v) for v in o.values()]
    elif isinstance(o, list): [walk(v) for v in o]
    elif isinstance(o, float): assert math.isfinite(o)
walk(back)
open(os.path.join(HERE, "params.json"), "w", encoding="utf-8").write(text + "\n")
print("params.json 저장 (kernel.py 값과 왕복 일치 확인)")
