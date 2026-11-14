# 체크리스트 (Checklist)

> 작업명: 인프라 — 작업 큐 · k6 부하 테스트 · AWS 배포 · CI/CD
> 최종 업데이트: 2026-11-10

## 합의

- [ ] 팀 합의 (범위 네 가지)
- [ ] 보고서 4장·8.3에 반영 요청
- [ ] 큐: Redis vs SQS 확정

## 작업 큐

- [ ] Redis 큐 + 결과 저장 (TTL 10분)
- [ ] 워커 분리 (API 서버와 별도 프로세스)
- [ ] 상태 `QUEUED` / `RUNNING` / `DONE` / `FAILED`
- [ ] 입력 해시 + seed 중복 방지
- [ ] 재시도 1회, 대기열 상한 `JOB4001`
- [ ] 큐 메시지 입력 즉시 삭제 확인 (NFR-06)

## k6

- [x] 응답 시간 측정 스크립트 + 매일 측정·디스코드 알림 (`perf/k6`, `perf.yml`, 2026-11-10)
- [ ] 부하 시나리오 (S1~S3, 동시 사용자 10 → 50 → 100)
- [x] 개선 전 측정: Render 무료 (decision.md 표)
- [ ] EC2 이전 후 같은 조건으로 측정 → 기록
- [ ] Perf 워크플로: 무료 서버에서 매일 실패 표시할지 결정
- [ ] 개선 후 같은 조건으로 측정 → 기록

## AWS

- [x] 컨테이너 이미지 (API) — Dockerfile
- [ ] 컨테이너 이미지 (워커)
- [ ] EC2 배포 (t3.small, 시드니) — 설정 순서는 [[setup-aws]]
- [ ] ElastiCache
- [x] 헬스체크 엔드포인트 (`/actuator/health`, 2026-11-10)
- [ ] 비밀값 관리 (Secrets Manager / Parameter Store)

## CI/CD

- [x] CI: 테스트 → 이미지 빌드 → 컨테이너 헬스체크 (`ci.yml`, 2026-11-10)
- [x] CD 작성: OIDC → ECR(SHA 태그) → SSM 배포 → 헬스체크 → 자동 롤백 (`deploy.yml`, 2026-11-10)
- [ ] AWS 설정 (OIDC 역할·정책, ECR, EC2) — [[setup-aws]]
- [ ] GitHub `production` 환경 변수 + 첫 배포 확인
- [ ] (선택) production 승인자, main 브랜치 보호
- [ ] (선택) Trivy 이미지 스캔, Dependabot
- [x] 정답 파일 대조 테스트를 파이프라인에 포함
- [ ] (선택) 기준 구현(`finance-simulator-reference`)에서 정답 파일을 다시 만들어 복사본과 비교
