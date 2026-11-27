# 결정 노트 (Decision)

> 작업명: 금리 자동 갱신 (한국은행 ECOS)
> 최종 업데이트: 2026-11-27
> 상태: 구현 완료, 리뷰 대기
> 기준: params.json v1.7, [[engine-port/decision]]의 구조 위에 얹음

## 무엇이 바뀌나

- 통장·일반 적금·마이너스 금리(엔진 값)와 대출 종류별 기본 금리(주택담보·신용·전세자금)를 한국은행 ECOS의 최신 월 값으로 쓴다. **ECOS 키(`ECOS_API_KEY`)가 있을 때만.**
- 키가 없으면 지금과 똑같다: params.json·코드 기본값 그대로. 테스트·CI도 이 상태라 정답 대조(golden)는 그대로다.
- `GET /api/meta`: `parameters`의 `rates` 칸이 받은 값·기준 달·출처로 바뀌고, `defaultRates`(비운 칸에 넣는 금리와 기준 달)가 새로 붙는다.
- 입력 API는 그대로다. 사용자가 비운 금리 칸에 들어가는 값만 바뀐다.

## 가져오는 통계

모두 ECOS 「예금은행 가중평균금리」 월 통계 (단위 연리%). 통계표·항목 코드는 ECOS `StatisticItemList`로 확인했다 (2026-11-27).

| 쓰는 곳 | 통계표 | 항목 | 지금 값 | 2026년 10월 |
|---|---|---|---|---|
| 통장 (`params.liquid`) | `121Y013` 수신금리(잔액 기준) | `BEABAB22` 수시입출식 저축성예금 | 0.68 (2025년 평균) | 0.81 |
| 일반 적금 (`general.rate`, 비운 `generalSavingRate`) | `121Y002` 수신금리(신규취급액 기준) | `BEABAA212` 정기적금 | 3.03 (2025년 평균) | 3.08 |
| 마이너스 (`params.overdraft`) | `121Y015` 대출금리(잔액 기준) | `BECBLB020206` 일반신용대출 | 5.31 (2025년 평균) | 5.90 |
| 주택담보대출 기본 금리 | `121Y006` 대출금리(신규취급액 기준) | `BECBLA0302` 주택담보대출 | 4.7 (`LoanType`) | 4.98 |
| 신용대출 기본 금리 | `121Y006` | `BECBLA03051` 일반신용대출 | 6.3 | 6.43 |
| 전세자금대출 기본 금리 | `121Y006` | `BECBLA03041` 전세자금대출 | 4.3 | 4.63 |

- 위 세 줄은 params.json에 넣은 2025년 연평균과 같은 통계다 (kernel.py 주석). ECOS의 2025년 값 0.68·3.03·5.31과 일치하는 것을 확인했다.
- 학자금(1.7%)·기타(5.0%) 대출은 맞는 통계가 없어 코드 값 그대로 둔다.

## 구현 결정 사항

### params.json은 그대로 두고, 계산에 쓰는 값만 덮어쓴다
- 결정: `ParamsRepository`가 params.json 값(`baseEngineParams`·`baseMeta`)과 계산에 쓰는 값(`engineParams`·`meta`)을 따로 들고, 받은 금리는 계산에 쓰는 값에만 덮어쓴다. 서비스들은 지금처럼 `getEngineParams()`를 부른다.
- 이유: params.json은 파이썬 기준 구현에서 만든 값이라, 바뀌면 정답 대조(`EngineGoldenTest`·`AnalysisGoldenTest`)가 깨진다. 테스트는 키 없이 돌아 params.json 값으로 계산한다.
- 검토한 대안: 받은 값으로 params.json을 다시 만들기 → 기준 구현(kernel.py → build_params.py → check_params.js) 순서를 건너뛰게 되므로 기각.

### 받는 때와 실패할 때
- 서버가 켜지고 3초 뒤 한 번, 그 뒤 매일 07:00 (한국 시간, `app.market.refresh-cron`). 한국은행은 보통 다음 달 말에 전달 금리를 발표한다.
- 항목마다 최근 6개월 가운데 가장 최근 달 값을 쓴다. 발표가 늦거나 빠진 달이 있어도 찾는다.
- 요청마다 ECOS를 부르지 않는다. ECOS가 느리거나 멈춰도 계산은 그대로 돌아간다.
- 실패한 항목은 마지막 값을 그대로 둔다.
- 키가 주소에 들어가므로 주소는 로그·예외 메시지에 남기지 않는다.

### 받은 값은 메모리에만 둔다
- 결정: 파일·DB에 저장하지 않는다. 서버를 다시 켜면 처음 받을 때까지(3초 뒤) params.json 값이다.
- 이유: 이 서버는 "DB·파일 쓰기·캐시가 없다"는 원칙으로 만들었다 ([review-context](../../architecture/review-context.md)). `/robust` 결과도 같은 이유로 메모리에만 둔다.
- 검토한 대안: 마지막 값을 파일로 저장해 다시 켤 때 먼저 쓰기 → 원칙에 어긋나고, 다시 켠 직후 3초 동안만 차이가 나서 기각.

### 비운 칸 기본값은 정적 값(`RateDefaults`)으로 읽는다
- 결정: `SimulationInput.SavingPlan`·`SimulationInput.LoanItem`·`RepaymentPreviewRequest`가 기본 금리를 `RateDefaults`에서 읽는다. `MarketRateService`가 받은 값을 넣는다.
- 이유: 입력 record는 Spring bean이 아니라 주입을 받을 수 없다. 기본값을 채우는 자리를 그대로 두어야 바뀌는 곳이 적다.
- 검토한 대안: 비운 칸을 null로 두고 서비스마다 채우기 → `ProfileMapper`·`InputValidationService`·`LoanController`·`PlanAdjuster`와 기존 테스트를 모두 고쳐야 해서 기각.

### 같은 입력 + 같은 seed = 같은 결과
- 같은 금리 기준 달 안에서는 그대로다. 기준 달이 바뀌면 결과도 바뀐다. 기준 달은 `GET /api/meta`의 `rates` 칸(`asOf`)과 `defaultRates.asOf`에 있다.
- 테스트는 `build.gradle`에서 키를 비워, 셸에 `ECOS_API_KEY`가 있어도 늘 params.json 값으로 돈다.

## `GET /api/meta`에 붙는 칸

```json
"defaultRates": {
  "generalSavingRate": 3.08,
  "loanRates": { "STUDENT": 1.7, "JEONSE": 4.63, "CREDIT": 6.43, "MORTGAGE": 4.98, "OTHER": 5.0 },
  "asOf": "2026-10",
  "source": "한국은행 ECOS 예금은행 가중평균금리 (신규취급액 기준). 학자금·기타 대출은 코드 기본값"
}
```

키가 없거나 아직 받지 못했으면 코드 기본값(3.03, 대출 1.7·4.3·6.3·4.7·5.0)과 `"asOf": null`이다.

## 켜는 법

1. ECOS Open API(https://ecos.bok.or.kr/api)에서 인증키를 신청한다. 키를 받기 전에 시험만 할 때는 ECOS 예시 키 `sample`을 넣어도 받아진다 (요청이 10줄 이하라서).
2. 로컬: `ECOS_API_KEY=발급받은키 ./gradlew bootRun`. 로그에 `금리 갱신: 6/6개 받음 …`이 찍히면 된다.
3. 배포 서버(EC2): `scripts/deploy/ec2-deploy.sh`의 `docker run`에 키를 넘겨야 한다. 키를 서버 어디에 둘지 정하지 않아서 아직 넣지 않았다.

## 남은 일

- 배포 서버에 키 넣기 (위 3번).
- 프론트 `src/config/policy.ts`의 대출 기본 금리·일반 적금 3.03%를 `/api/meta`의 `defaultRates`로 바꾸기. 지금 프론트는 이 값을 안내 문장·placeholder에만 써서 계산에는 영향이 없다.
