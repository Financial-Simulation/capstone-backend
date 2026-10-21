# capstone-backend 문서

사회초년생 경제 시뮬레이터 (캡스톤디자인 6조) 백엔드 문서 모음.

## 구조

| 폴더 | 내용 |
|---|---|
| `architecture/` | API 전체 구조, 공통 규칙 요약 |
| `tasks/<작업명>/` | 작업 단위 기록. `decision.md`(무엇을 왜 결정했나) · `context.md`(배경·관련 자료) · `checklist.md`(남은 일) |

## 작업 목록

| 작업 | 상태 | 요약 |
|---|---|---|
| [api-spec-revamp](tasks/api-spec-revamp/decision.md) | 노션 반영 완료 (2026-10-08) | 엔드포인트 32개 → 12개, 서버 입력 비저장 |
| [infra-queue-k6-aws-cicd](tasks/infra-queue-k6-aws-cicd/plan.md) | 팀 합의 전 | `/robust` 작업 큐, k6 부하 테스트, AWS, CI/CD |

## 원본 문서

- API 명세 (원본): 노션 「선빈 api 명세서」
- 설계 보고서: 노션 「설계보고서 피드백 반영 전」 (2026-10-07)
- 수정 제안서: `API_명세서_수정본.pdf`

## 기록 원칙

- 결정할 때마다 `decision.md`에 **결정 · 이유 · 검토한 대안(기각 이유)** 을 남긴다.
- 성능은 **개선 전후를 같은 조건으로** 측정해 수치로 남긴다.
