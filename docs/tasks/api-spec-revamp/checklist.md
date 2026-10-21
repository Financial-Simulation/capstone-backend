# 체크리스트 (Checklist)

> 작업명: API 명세 개편
> 최종 업데이트: 2026-10-10

## 명세

- [x] 공통 규칙을 서버 비저장 구조로 변경
- [x] 공통 입력 객체 `SimulationInput`, `adjustments`, 오류·경고 코드 표 정리
- [x] 기존 페이지 9개 갱신, 신규 3개 생성 (`/robust`, `/robust/{jobId}`, `/examples`)
- [x] 없어진 23개를 「폐기된 API」 페이지로 이동
- [x] `/meta`에 파라미터 20개
- [ ] `/meta`의 비어 있는 `asOf`(기준일) 채우기 — 조민석, `params.json` 만들 때
- [ ] 경고 코드 전체 목록 채우기 (보고서 7.2 거부 23종·경고 6종) — `validate_input.py`에서 옮기기

## 팀 확정 필요

- [ ] `/reports`를 서버에 둘지, 프론트가 조립할지
- [ ] `/examples`를 서버에 둘지, 프론트 상수로 둘지
- [ ] 연봉 상승률 '내 값 쓰기': 엔진에 넣을지, 보고서 4.3 문구를 고칠지
- [ ] 달성 가능성 색 구분 기준 (프론트 한 곳에서)
- [ ] Java 이식 후 `/robust` 동기 전환 여부

## 보고서 수정 요청 (보고서 담당)

- [ ] 2.2·4.2에 `startMonth`, `/inputs/validate`, `/examples` 반영 (FR-13 인용 정리)
- [ ] FR-09 "필요 월 저축액" 재정의
- [ ] 3.1 예시 경로와 위험 원인 분류(`prolongedUnemployment`) 확인
- [ ] S1 입력이 처음부터 적자인 것이 의도인지 확인
- [ ] 8.2 '범위 밖'에 월별 현금흐름 표·부수입·다중 목표 명시

## 구현 (시작 전)

- [ ] 공통: `ApiResponse`, `ErrorStatus`, GlobalExceptionHandler, 요청 본문 로그 제외
- [ ] `SimulationInput` DTO + Bean Validation
- [ ] `LoanCalculator` (상환 4종) + `/loans/repayment-preview`
- [ ] `/inputs/validate`
- [ ] 엔진 Java 이식 + 정답 파일 대조 테스트
- [ ] `/simulate`
- [ ] `/simulate/preview`, `/compare` (공통 난수)
- [ ] `/reverse`
- [ ] `/robust` + `/robust/{jobId}`
- [ ] `/meta`, `/events/presets`, `/examples`, `/reports`
