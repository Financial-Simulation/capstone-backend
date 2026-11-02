# API 변경 (v1.7 세금 모듈)

> 프론트(이윤제)·백엔드(정선빈)용. 상세 계산 규칙은 구현 명세서 v1.7.

기존 요청은 그대로 동작하고 기존 응답 칸도 값이 같습니다. 칸이 **추가**만 됐습니다.

## 요청 (SimulationInput) — 모두 선택
| 칸 | 뜻 | 비우면 | 화면 |
|---|---|---|---|
| `income.annualGrossSalary` | 세전 연봉 (원) | 월 실수령액 × 12 ÷ 0.88 | 01-1 '더 정확하게' |
| `expenses.monthlyRent` | 고정지출 중 월세 (원, 고정지출 이하) | 0 | 01-1 고정지출 아래 한 줄 |
| `person.homelessHouseholder` | 무주택 세대주 | true | '더 정확하게' |
| `savingPlan.policyMonthly` | 청년미래적금 월 납입 (0~50만) | min(월 저축액, 50만) | 결과 '배분 바꿔 보기' |
| `savingPlan.housingMonthly` | 주택청약 월 납입 (0~50만, 상품 약관 월 2만~50만) | 0 | 결과 '배분 바꿔 보기' |
| `savingPlan.pensionMonthly` | 연금저축 월 납입 (0~150만, 연금계좌 한도 연 1,800만) | 0 | 결과 '배분 바꿔 보기' |

- 배분은 월 저축액 안에서 청년미래 → 청약 → 연금저축 순서로 떼고 나머지가 일반 적금입니다. 합이 월 저축액보다 크면 뒤 상품을 줄입니다 (응답 `allocation`에 실제 값).
- `adjustments`(preview·compare)에도 `policyMonthly`·`housingMonthly`·`pensionMonthly`가 추가됐습니다. 다른 칸처럼 null이면 '바꾸지 않음'입니다.
- `startMonth`의 달로 연말정산 환급 시점(매년 2월)을 정합니다.
- 세전 연봉을 넣으면 `세전 연봉 × 1.1 ≥ 월 실수령액 × 12`여야 합니다 (어기면 400, 키 `income.grossConsistentWithNet`). 10% 여유는 비과세 수당을 뺀 총급여를 넣는 경우를 위한 것입니다.

## 응답
### POST /api/simulate → `tax` (목표 시점까지, 2,000개 미래 평균, 원)
| 칸 | 뜻 |
|---|---|
| `refundTotal`, `refundPerYear` | 돌려받는 세금 합계, 연평균 (청약·연금·월세가 없을 때와의 차이) |
| `interestTax` | 낸 이자소득세 (일반 적금·청약·청년미래 일반해지) |
| `clawback` | 청약 5년 안 해지 추징 |
| `pensionTax` | 연금저축 중도 인출 기타소득세 16.5% |
| `netBenefit` | refundTotal − interestTax − clawback − pensionTax |
| `housingBreakProbability`, `pensionBreakProbability` | 목표 시점까지 청약·연금을 깰 확률 (%), 상품이 없으면 null |
| `allocation` | 실제 월 배분 {policyMonthly, housingMonthly, pensionMonthly, generalMonthly} |

### POST /api/compare → 각 계획 줄에 추가
`medianAtGoal`(목표 시점 모은 돈 중간값, 원), `taxNetBenefit`(위 netBenefit), `allocation`

### 새 API (v1.7)
#### POST /api/allocations/recommend
요청: `{input}`. 청년미래 0~min(월 저축액, 50만) 10만 간격(+상한값)·청약 {0, 10, 25만}·연금저축 {0, 10, 20, 30, 50만} 중 합이 월 저축액 이하인 배분을 모두 같은 미래 2,000개로 계산한다.
응답: `monthlySaving`, `evaluated`(계산한 배분 수), `current`·`bestProbability`·`bestBenefit` 각각 `{allocation, hits, probability, taxNetBenefit, subsidy, totalBenefit, policyBreakProbability, diffFromBase}`, `rule`.
- `bestProbability`: 목표 달성 수 최대 (같으면 totalBenefit 큰 쪽)
- `bestBenefit`: totalBenefit(세금 순이득 + 정부 지원금) 최대, 단 목표 달성 수가 지금 배분보다 2%p 넘게 줄지 않는 것 중에서
- 세금·지원금은 **목표 시점까지** 평균이다. /simulate의 `risks.policySaving.expectedSubsidy`는 48개월 안에 받는 돈이라 숫자가 다를 수 있다.
#### POST /api/reports
요청: `{input, plans (0~3개), seed (null이면 20261007)}`. 응답은 리포트 한 장에 들어갈 숫자 전부:
`generatedAt, seed, modelVersion, startMonth, inputSummary, headline, goal, forecast{checkpoints(1·3·5·10년 p5·p10·p50·p90), deterministicAtGoal}, risks, causes, tax, levers(상위 3개), comparison(/compare와 같은 줄), parameters(근거 표), warnings, disclaimer`.
PDF는 서버가 만들지 않는다. 화면이 이 데이터로 인쇄용 화면을 그리고 브라우저 인쇄(PDF로 저장)를 쓴다.

## 정답 파일
- `src/test/resources/golden/golden.json`: 16개 프로필 (세금 4개 추가, `tax`·`brkH`·`brkPen` 포함)
- `src/test/resources/golden/analysis_golden.json`: 11개 입력 (T1~T3 추가, 각 입력에 `recommend` 추가)
- 테스트: `EngineGoldenTest`(엔진 16개), `AnalysisGoldenTest`(분석 11개 × simulate·preview·compare·reverse·robust·recommend), `ReportServiceTest`(리포트 숫자 = simulate·reverse 정답), `SimulationControllerTest`(새 엔드포인트 2개)

## 화면 참고 (`frontend-addon/tax-addon.js`)
조민석 쪽 합본에서 빌드된 index.html에 덧붙여 세금 화면을 확인한 스크립트다. 이 저장소에서는 실행되지 않고 이윤제가 앱 소스로 옮길 때 참고용으로 둔다.
- 01-1: 월 실수령액 아래 '더 정확하게 (선택)'(세전 연봉, 무주택 세대주), 고정지출 아래 '그중 월세 (선택)'
- 결과: '위험' 아래 '세금'(돌려받는 세금·낸 세금·순이득·안내 문장, 우대형 안내), '어디에 얼마나'(상품별 배분 입력 → /api/compare로 최대 3개 비교 → '이 배분으로' 누르면 결과 전체 다시 계산, '가장 좋은 배분 찾기' → /api/allocations/recommend)
- 결과 첫 카드 아래: '이 숫자는 얼마나 믿을 수 있나요?' → /api/robust 작업을 맡기고 1.5초마다 GET으로 진행률 확인, 범위 띠 그림
- '이렇게 바꿔 보세요' 아래: '직접 바꿔 보기' → /api/simulate/preview, '비교에 담기'(최대 3개) → /api/compare, '가정을 바꿔도 결론이 같은지 보기' → /api/robust
- 위쪽 버튼 줄: '리포트 저장' → /api/reports → 인쇄용 화면 → 브라우저 인쇄(PDF로 저장)
- 값은 브라우저 localStorage `taxAddon.v1`에만 저장하고, 계산 API 요청의 input에 칸을 넣는다 (fetch를 감쌈). 앱 소스로 옮길 때는 화면 구성·문구·규칙을 컴포넌트로 옮기고 fetch 감싸기는 지우면 된다.
