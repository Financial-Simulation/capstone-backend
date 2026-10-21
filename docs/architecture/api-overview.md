# API 구조 개요

> 기준: 2026-10-08 개편 (계산 엔진 v1.6, 설계 보고서 2026-10-07)
> 상세 필드·예시는 노션 「선빈 api 명세서」가 원본이다. 이 문서는 한눈에 보기용.

## 핵심 원칙

1. **서버는 사용자 입력을 보관하지 않는다** (NFR-06). 입력은 브라우저 localStorage(`simInput.v1`)에 있고, 계산 API가 매번 본문 `input`으로 받는다.
2. **같은 입력 + 같은 seed = 같은 결과** (NFR-02). 기준 달은 서버 날짜가 아니라 입력의 `startMonth`.
3. **비교는 같은 seed로 함께 계산한다** (보고서 6.4 공통 난수). 따로 계산한 결과끼리 비교하지 않는다.
4. **계산은 백엔드 엔진 한 곳에서만** 한다. 프론트는 표시용 합계만.

## 엔드포인트 (12개)

| 메서드 | 경로 | 처리 | 목표 응답 | 역할 |
|---|---|---|---|---|
| POST | `/api/inputs/validate` | 동기 | — | 입력 단계별 검사. 오류·경고·확정 합계 (저장 없음) |
| POST | `/api/loans/repayment-preview` | 동기 | — | 대출 한 건의 월 상환액 (`LoanCalculator` 공유) |
| POST | `/api/simulate` | 동기 | 1초 | 기본 결과: 확률·범위·위험·원인·확정 계산·첫 문장 |
| POST | `/api/simulate/preview` | 동기 | 1초 | 조절 막대: 지금 계획 vs 바꾼 계획 1개 |
| POST | `/api/compare` | 동기 | 3초 | 지금 계획 + 비교 계획 1~3개, 짝지은 검정 |
| POST | `/api/reverse` | 동기 | 3초 | 역산(필요 지출 조정·저축액·기간), 비상금 전략, 개선 포인트 |
| POST | `/api/robust` | 비동기 | — | 가정 60가지 × 2,000경로 작업 시작 (202) |
| GET | `/api/robust/{jobId}` | 동기 | — | 진행률·결과 (10분 보관) |
| POST | `/api/reports` | 동기 | — | 리포트 데이터 (프론트 조립으로 대체 가능) |
| GET | `/api/meta` | 동기 | 즉시 | 파라미터 20개 + 근거 등급·출처 (FR-10) |
| GET | `/api/events/presets` | 동기 | 즉시 | 예정된 일 프리셋 5종 |
| GET | `/api/examples` | 동기 | 즉시 | S1~S3 예시 입력 (프론트 상수로 대체 가능) |

## 흐름

```
[브라우저]                               [백엔드]
입력 단계마다 ──── POST /inputs/validate ──→ 검사만, 저장 X
'계산' ─────────── POST /simulate ─────────→ 엔진 2,000경로 × 120개월
조절 막대 ──────── POST /simulate/preview ─→ 같은 seed 2개 계획
계획 비교 ──────── POST /compare ──────────→ 같은 seed 최대 4개 계획
'가능성 범위' ──── POST /robust ───────────→ 작업 등록 (202, jobId)
   1초 폴링 ────── GET /robust/{jobId} ───→ RUNNING / DONE
```

## 공통 규칙 요약

| 항목 | 규칙 |
|---|---|
| 응답 래퍼 | `ApiResponse<T>` `{ isSuccess, code, message, result }` |
| HTTP 상태 | 200 / 202 / 400 / 404(작업 없음만) / 500. 4xxx 비즈니스 오류는 400 |
| 금액 | `Long` 원 단위. 엔진에는 ÷10,000(만 원), 결과는 ×10,000 후 HALF_UP |
| 금리·비율 | `BigDecimal` 연 %. 엔진에는 ÷100 |
| 확률 | `BigDecimal` %, 소수 1자리. 적중 경로 수로 계산 후 HALF_UP |
| 분위수 | 정렬한 N개 값의 ⌊q·N⌋번째 (0부터) |
| 난수 | 경로 2,000개 고정, seed 기본 `20261007`, 응답에 항상 반환 |
| 경고 | 계산을 막지 않고 `result.warnings[]` |
| 로그 | 요청 본문(금융 입력값)은 남기지 않음 |
| 도메인 코드 | INPUT / LOAN / GOAL / EVENT / SIM / JOB |

## 계층

```
Controller ── 검증(@Valid), 단위 변환(원 ↔ 만 원)
  └ SimulationService ── seed 관리, 계획별 adjustments 적용, 공통 난수 묶음 계산
      ├ Engine ── 월별 14단계 (보고서 6.1), 파이썬 기준 구현 kernel.py를 Java로 이식
      ├ LoanCalculator ── 상환 4종 (repayment-preview와 공유)
      └ ParamsRepository ── params.json (배포 전 고정, 읽기 전용)
```
