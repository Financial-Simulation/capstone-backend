# 결정 노트 (Decision)

> 작업명: 세금 모듈 v1.7 반영
> 최종 업데이트: 2026-11-02
> 기준: 조민석 계산 엔진 v1.7 (kernel.py / engine.js, 구현 명세서 v1.7), [[engine-port/decision]]의 구조 위에 얹음
> 작성: 조민석 (Claude Code로 이식)

## 무엇이 바뀌나

- 엔진: 연말정산 환급(매년 2월), 주택청약(소득공제·이자 비과세·5년 내 해지 추징), 연금저축(세액공제·중도 인출 16.5%), 4대보험, 월세 세액공제, 이자소득세. 청년미래적금 정부 매칭은 세전 6,000만 원 이하만.
- 입력: 모두 선택 칸 6개 (`annualGrossSalary`, `monthlyRent`, `homelessHouseholder`, `policyMonthly`, `housingMonthly`, `pensionMonthly`). 비우면 v1.6과 같은 값이 나온다.
- 응답: `/simulate`에 `tax`, `/compare` 계획 줄에 `medianAtGoal`·`taxNetBenefit`·`allocation`. 기존 칸은 값이 같다.
- 새 API: `POST /api/allocations/recommend`, `POST /api/reports`.
- 칸별 상세는 [api-changes.md](api-changes.md).

## 구현 결정 사항

### 기존 구조를 그대로 두고 이식한다
- 결정: `simulation/engine`의 record·`SimulationEngine`·`Analyzer`·타입 DTO 구조를 유지하고, 필드와 단계만 더했다. 새 파일은 `YearEndTax`(세액 계산), `dto/Allocation`, `dto/RecommendResponse`, `report/` 네 개뿐이다.
- 이유: 정선빈이 PR #2로 정리한 구조(record, typed DTO, `@SpringBootTest` 정답 대조)를 바꾸지 않아야 리뷰가 쉽다.
- 검토한 대안: 조민석 쪽 합본(Map 응답, 엔진 한 파일)을 통째로 덮어쓰기 → 구조가 되돌아가므로 기각.

### 상품 배분 순서와 해지 순서
- 배분: 청년미래 → 청약 → 연금저축 → 나머지 일반 적금. 합이 월 저축액보다 크면 뒤 상품부터 줄인다.
- 급전 때 깨는 순서: 일반 적금 → 청약 → 청년미래 → 연금저축. 세금 손해가 작은 것부터다.
- 모은 돈(`saved`)은 상품 계좌를 **세후 해지 금액**으로 센다. 지금 깨면 실제로 손에 쥐는 돈이라서다.

### `/allocations/recommend`
- 청년미래 0~min(월 저축액, 50만) 10만 간격(+상한값) × 청약 {0, 10, 25만} × 연금저축 {0, 10, 20, 30, 50만} 중 합이 월 저축액 이하인 배분을 같은 미래 2,000개로 모두 계산한다 (S1은 39개).
- 세금 합은 경로별 값을 먼저 더한 뒤 2,000으로 나눈다. 기준 구현과 덧셈 순서가 같아야 원 단위까지 맞는다.
- 측정: S1 약 1.6초 (2코어 컨테이너, 예열 후). 동기 API로 두었다. 부하가 문제면 `/robust`처럼 작업으로 돌리는 것을 검토한다.

### `/reports`를 서버에 구현했다
- 결정: 새 계산 없이 `/simulate`·`/reverse`(개선 포인트 상위 3개)·`/compare`·`/meta`(근거 표) 결과를 묶어 돌려준다. PDF는 화면이 브라우저 인쇄로 만든다.
- 이유: [[engine-port/decision]]의 "팀 결정 대기" 항목이었는데, 화면 스크립트(리포트 저장 버튼)가 이 API를 쓴다. 계산이 없으니 프론트 조립으로 바꾸기로 하면 `report/` 폴더만 지우면 된다.
- **팀 확인 필요**: 서버에 둘지, 프론트가 조립할지.

### `/robust` 작업 실패 처리
- 결정: 작업 안에서 `RuntimeException`뿐 아니라 `Error`(메모리 부족 등)도 잡아 `FAILED`로 끝낸다.
- 이유: 잡지 않으면 작업이 `RUNNING`으로 남아 화면이 계속 폴링하고, 동시 2개 상한도 풀리지 않는다.

### 입력 검사
- 청약 월 0~50만, 연금저축 월 0~150만 (연금계좌 한도 연 1,800만).
- 세전 연봉을 넣으면 `세전 × 1.1 ≥ 월 실수령 × 12`여야 한다 (`income.grossConsistentWithNet`). 10% 여유는 비과세 식대를 뺀 총급여를 넣는 경우를 위한 것.

## 검증 결과 (2026-11-02)

Gradle을 쓸 수 없는 환경이라 delombok + Jackson 2 스텁으로 같은 소스를 컴파일해 JUnit 테스트 본문을 그대로 돌렸다. **`./gradlew test`로 다시 확인이 필요하다.**

| 항목 | 결과 |
|---|---|
| 엔진 정답 16개 프로필 | 플래그·첫 해지·세금 4종 일치, 최대 상대오차 9.3e-12 |
| 분석 정답 11개 입력 | simulate·preview·compare·reverse·robust·recommend 전 항목 일치 |
| v1.6과의 호환 | 기존 8개 입력(S1~S3, E1~E5)의 v1.6 정답 항목이 v1.7 정답에서 값까지 모두 같음 |
| 리포트 | 섹션 숫자가 simulate·reverse 정답과 같음 |
| 입력 검사·계획 조정 | `SimulationInputValidationTest` 7개, `PlanAdjusterTest` 9개 통과 |
| MockMvc 테스트 | 이 환경에서 못 돌림. `modelVersion` 기대값을 1.7로 바꾸고 새 엔드포인트 2개 테스트 추가 |

## 남은 일

- `./gradlew test` 전체 통과 확인 (정선빈)
- `/reports` 서버 유지 여부 팀 결정
- 화면: `frontend-addon/tax-addon.js`를 앱 소스 컴포넌트로 옮기기 (이윤제)
- 노션 API 명세에 새 칸·새 API 반영
