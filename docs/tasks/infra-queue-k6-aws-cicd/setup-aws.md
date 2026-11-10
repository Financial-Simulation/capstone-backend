# AWS 배포 설정 순서 (Setup)

> 작업명: 인프라 — CI/CD
> 최종 업데이트: 2026-11-10
> 결정 이유는 [[decision]], 남은 일은 [[checklist]]

처음 한 번만 하면 된다. 이후에는 main에 머지하면 자동으로 배포된다.

## 전체 흐름

```
main에 머지
  → CI (.github/workflows/ci.yml): 테스트(정답 파일 대조) → 이미지 빌드 → 컨테이너 헬스체크
  → Deploy (.github/workflows/deploy.yml)
      1. GitHub OIDC로 AWS 임시 권한 받기 (저장된 키 없음)
      2. 이미지를 ECR에 푸시 (태그 = 커밋 SHA, 덮어쓰기 금지)
      3. SSM으로 EC2에 배포 스크립트 전송 (scripts/deploy/ec2-deploy.sh)
         → 새 컨테이너 헬스체크 실패 시 이전 이미지로 자동 롤백
      4. 밖에서 /actuator/health 확인
      5. 실행 요약에 배포 기록
```

## 현재 값

| 항목 | 값 |
|---|---|
| 계정 ID | 260172549184 |
| 리전 | ap-southeast-2 (시드니). 무료 플랜이라 이 리전만 쓸 수 있다 |
| 인스턴스 | t3.small (x86, 2 vCPU, 2GB), 이름 태그 `capstone-backend` |
| ECR 저장소 | `capstone-backend` |
| 인스턴스 ID · 공인 IP | GitHub `production` 환경 변수에 있다 |

## 1. 계정 보안

- [ ] 루트 계정 MFA: 오른쪽 위 계정 이름 → 보안 자격 증명 → MFA 할당
- [ ] 예산 알림: Budgets → 예산 생성 → 월별 비용 예산 $5 → 이메일
- [ ] **액세스 키는 만들지 않는다** (OIDC를 쓴다)

## 2. ECR 저장소

ECR → 리포지토리 생성
- [ ] 이름 `capstone-backend`, 비공개
- [ ] 이미지 태그 변경 불가(Immutable) 켜기
- [ ] 푸시 시 스캔 켜기

## 3. EC2 역할 `capstone-ec2-role`

IAM → 역할 → 역할 생성 → AWS 서비스 → EC2
- [ ] `AmazonSSMManagedInstanceCore` (SSM으로 명령 받기)
- [ ] `AmazonEC2ContainerRegistryReadOnly` (ECR에서 이미지 받기)

## 4. EC2 인스턴스

EC2 → 인스턴스 시작
- [ ] 이름 `capstone-backend` (배포 권한이 이 이름 태그로 걸려 있다. 바꾸면 배포가 막힌다)
- [ ] AMI: Amazon Linux 2023, 64비트(x86) / 유형: t3.small
- [ ] 키 페어: **키 페어 없이 계속 진행**
- [ ] 보안 그룹: SSH(22) 규칙 삭제, HTTP(80) 0.0.0.0/0 추가
- [ ] 스토리지 20GiB gp3
- [ ] 고급 세부 정보 → IAM 인스턴스 프로파일 `capstone-ec2-role`
- [ ] 고급 세부 정보 → 사용자 데이터:
  ```bash
  #!/bin/bash
  dnf install -y docker
  systemctl enable --now docker
  ```
- [ ] 시작 후 탄력적 IP → 할당 → 인스턴스에 연결 (안 하면 재시작할 때마다 주소가 바뀜)
- [ ] 5분 뒤 Systems Manager → Fleet Manager에 "온라인"으로 보이는지 확인

이미 만든 인스턴스의 유형만 바꿀 때: 중지 → 작업 → 인스턴스 설정 → 인스턴스 유형 변경 → 시작.
x86 인스턴스는 ARM 유형(t4g)으로 바꿀 수 없다 (OS가 다름).

## 5. GitHub OIDC

1. [ ] IAM → 자격 증명 공급자 → 공급자 추가
   - OpenID Connect, URL `https://token.actions.githubusercontent.com`, 대상 `sts.amazonaws.com`
2. [ ] IAM → 역할 → 역할 생성 → 웹 자격 증명 → 위 공급자 선택 → 이름 `github-actions-deploy`
3. [ ] 역할 → 신뢰 관계 → 신뢰 정책 편집 → [`infra/aws/github-actions-deploy-trust.json`](../../../infra/aws/github-actions-deploy-trust.json) 내용으로 통째로 교체
   - 이 레포의 `production` 환경에서 도는 작업만 역할을 받을 수 있다
4. [ ] 역할 → 권한 추가 → 인라인 정책 생성 → JSON → [`infra/aws/github-actions-deploy-policy.json`](../../../infra/aws/github-actions-deploy-policy.json) 붙여 넣기 → 이름 `deploy`
   - ECR은 `capstone-backend` 저장소만, 명령은 이름 태그가 `capstone-backend`인 인스턴스에만

## 6. GitHub 설정

레포 Settings → Environments → New environment → `production` → Environment variables

| 이름 | 값 |
|---|---|
| `AWS_REGION` | `ap-southeast-2` |
| `AWS_ROLE_ARN` | `arn:aws:iam::260172549184:role/github-actions-deploy` |
| `ECR_REPOSITORY` | `capstone-backend` |
| `EC2_INSTANCE_ID` | `i-...` |
| `DEPLOY_BASE_URL` | `http://<탄력적 IP>` |

- [ ] (선택, 첫 배포 성공 뒤) Required reviewers에 승인자 지정 → 배포 전에 승인을 받아야 진행
- [ ] (선택) 브랜치 보호: Settings → Branches → main → Require status checks → `Test (golden 포함)`, `Docker build + smoke test`

속도 측정(Perf 워크플로)용 설정은 저장소 수준에 둔다.
- Variables `PERF_BASE_URL`: 잴 서버 주소
- Secrets `DISCORD_WEBHOOK_URL`: 디스코드 채널 설정 → 연동 → 웹후크

## 7. 첫 배포 확인

- [ ] main에 머지 → Actions에서 CI 다음 Deploy가 도는지
- [ ] 브라우저에서 `http://<탄력적 IP>/actuator/health` → `{"status":"UP"}`
- [ ] `http://<탄력적 IP>/api/meta`가 열리는지
- [ ] Perf 워크플로의 `PERF_BASE_URL`을 EC2 주소로 바꾸고 측정 → [[decision]] 표에 기록

## 운영

| 하고 싶은 것 | 방법 |
|---|---|
| 예전 버전으로 되돌리기 | Actions → Deploy → Run workflow → 되돌릴 커밋 SHA 입력 (ECR에 이미지가 있으면 빌드 없이 배포) |
| 서버 터미널 열기 | Systems Manager → 세션 관리자 → 세션 시작 (SSH 키 불필요) |
| 배포 이력 보기 | 서버에서 `cat /opt/capstone/deploy-history.log`, 또는 Actions 실행 요약 |
| 앱 로그 보기 | 서버에서 `sudo docker logs --tail 100 app` |
| 비용 멈추기 | 인스턴스 **종료**(중지 아님) + 탄력적 IP 릴리스 |

## 막혔을 때

| 증상 | 확인할 것 |
|---|---|
| Deploy가 "환경 변수가 비어 있다"로 실패 | 6번 `production` 환경 변수 |
| `Not authorized to perform sts:AssumeRoleWithWebIdentity` | 신뢰 정책이 `environment:production`인지 (5-3) |
| `ssm:SendCommand` 권한 거부 | 인스턴스 이름 태그가 정확히 `capstone-backend`인지 |
| SSM 명령이 계속 Pending | Fleet Manager에서 인스턴스가 온라인인지, `capstone-ec2-role`이 붙었는지 |
| 서버 안 헬스체크는 통과, 외부 헬스체크 실패 | 보안 그룹 HTTP(80) 규칙 |
| `docker: command not found` | 사용자 데이터가 안 들어감 → 세션 관리자에서 4번 스크립트 직접 실행 |
