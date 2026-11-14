# 시스템 설명 (리뷰용)

> PR마다 AI 리뷰 프롬프트에 그대로 들어간다 (`scripts/ai-review/review.py`). 새 팀원 안내용으로도 쓴다.
> **코드에서 확인한 사실만 적는다.** 설계가 바뀌면 이 파일을 같이 고친다. 틀린 설명은 리뷰 오탐을 만든다.
> 최종 확인: 2026-11-15 (main `4732886`)

## 한 줄 요약

사회초년생 금융 시뮬레이터 백엔드. 사용자 재무 입력을 매 요청 본문으로 받아 2,000개 미래(경로) × 최대 120개월을 몬테카를로로 계산하고, 확률·범위·개선안을 돌려준다. **DB가 없고 입력을 저장하지 않는다.**

## 요청 흐름

```
Controller (@Valid @RequestBody 요청 record)
  └ SimulationService
      1. inputValidationService.checkRequired(input)   ← 입력 검증은 여기서 한다
      2. profileMapper.toPlan(input)                   ← 원·% → 만 원·연 소수
      3. analyzer.xxx(..., seed)                       ← NoiseSource.sfc32(seed, 2000) 생성
           └ SimulationEngine.simulate(...)            ← 경로를 parallel()로 계산
      4. 결과 ×10,000, HALF_UP 반올림 → ApiResponse
```

## 입력 검증 (오탐 주의)

- 요청 record의 `SimulationInput input`에는 **일부러 `@Valid`를 붙이지 않는다.** 모든 계산 API(`Simulate`, `Preview`, `Compare`, `Reverse`, `Robust`, `Recommend` 등)가 같다.
- 검증은 서비스 첫 줄의 `InputValidationService.checkRequired(input)`가 한다. `validator.validate(input, InputStep.ALL.groupsUpToHere())`로 **단계별 그룹**(`Default` + `Basic`·`Assets`·`Goal`·`Events`)을 모두 검사하고, 칸 사이 규칙(`checkCrossRules`)까지 본다.
- 필수 칸 제약은 `groups = InputStep.Basic.class` 같은 그룹에 붙어 있다. 요청 record에 `@Valid`를 붙이면 `Default` 그룹(범위 검사)만 돌아 필수 칸은 검사되지 않고, 검사가 두 번 돈다. **새 계산 API가 `checkRequired`를 부르는지**가 확인할 점이다.
- 화면 단계별 검사는 `POST /api/inputs/validate`가 `InputStep`별로 한다 (저장 없음).
- 검증 실패는 `FieldValidationException` / `GeneralException(ErrorStatus.…)` → `GlobalExceptionHandler`가 400 + `ApiResponse`로 바꾼다.

## 난수와 재현성 (오탐 주의)

- `NoiseSource`는 **상태가 없다.** `NoiseSource.sfc32(seed, n)`은 람다를 돌려주고, `noise(p, t)`를 부를 때마다 `Sfc32.of(seed, p, …)`로 생성기를 **새로 만든다.** 같은 `(p, t)`면 몇 번을 불러도, 어떤 순서로 불러도, 여러 스레드에서 불러도 같은 값이다. 그래서 한 인스턴스를 여러 엔진 호출에 넘겨도 된다.
- 단, 값은 `t`(기간)에 따라 달라진다 (정규난수를 `t`개 뽑은 뒤 연 단위 난수를 이어서 뽑기 때문). **같은 경로를 비교하는 두 호출은 같은 `t`를 넘겨야 한다.** 확인할 점은 이것이다.
- `NoiseSource.zero()`는 확정 계산(난수 0)용이다.
- 같은 입력 + 같은 seed = 같은 결과 (NFR-02). seed 기본값 `20261007`. 계획 비교·미리보기는 **같은 seed로 함께** 계산한다 (공통 난수).
- `Sfc32`는 engine.js와 비트 단위로 같다. 생성 순서·버리는 개수(12개)·`mix` 식을 바꾸면 모든 확률이 바뀐다.

## 계산 엔진

- `SimulationEngine.simulate`: 경로 `IntStream.range(0, n).parallel()`. 경로끼리 상태를 공유하지 않는다. 결과 배열은 경로 번호로 칸이 나뉘어 있다.
- 경로 수 2,000, 기간 120개월은 보고서 기준이라 바꾸지 않는다 (`Analyzer.PATHS`, `HORIZON`).
- 기준 구현은 Python `kernel.py` / JS `engine.js` (`finance-simulator-reference` 저장소). Java는 이식본이다.
- 파라미터는 `resources/params.json`을 서버 시작 때 한 번 읽는다 (`ParamsRepository`, 읽기 전용).

## 정답 대조 테스트

- `EngineGoldenTest`(golden.json, 엔진 16개 프로필), `AnalysisGoldenTest`(analysis_golden.json, API 결과)가 기준 구현의 결과와 비교한다. 상대오차 1e-9, 확률은 정확히 같아야 한다.
- 계산 결과가 바뀌는 변경은 **기준 구현부터 고치고 정답 파일을 다시 만든다.** 정답 파일만 Java에 맞춰 고치는 건 안 된다.
- 정답 파일이 바뀐 PR은 decision.md에 이유와 바뀐 값이 적혀 있어야 한다.

## 단위

| 구간 | 금액 | 금리·비율 | 확률 |
|---|---|---|---|
| API 입출력 | `Long` 원 | `BigDecimal` 연 % | `BigDecimal` %, 소수 1자리 |
| 엔진 | `double` 만 원 (`ProfileMapper.man`: ÷10,000) | 연 소수 (÷100) | 적중 경로 수 / 2,000 |

반올림은 `Rounding` (HALF_UP). 원 ↔ 만 원, % ↔ 소수 변환 누락이 가장 흔한 실수다.

## 비동기 작업 (`/api/robust`)

- `RobustJobService`: 스레드 2개 고정 풀, 동시 실행 2개 상한(넘으면 `JOB4001`), 결과는 메모리 `ConcurrentHashMap`에 완료 후 10분 보관. 서버 재시작 시 사라지는 게 의도다.

## 저장·로그

- DB·파일 쓰기·캐시가 없다. 입력은 요청이 끝나면 사라진다 (NFR-06).
- 요청 본문(금융 입력값)을 로그에 남기지 않는다. 새 `log.*` 호출에 입력값이 들어가면 문제다.

## 응답 시간 목표 (NFR-03)

`/simulate`·`/simulate/preview` 1초, `/compare`(계획 3개)·`/reverse` 3초. 무료 서버(CPU 0.1개)에서는 못 지키며 원인은 CPU다 ([[../tasks/infra-queue-k6-aws-cicd/decision]]).
