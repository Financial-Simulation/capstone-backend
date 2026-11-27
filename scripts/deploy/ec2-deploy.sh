#!/bin/bash
# EC2 안에서 실행된다. GitHub Actions가 SSM으로 이 파일 내용을 보내고, 앞에 아래 변수를 붙인다.
#   REGION, REGISTRY(예: 123456789012.dkr.ecr.ap-southeast-2.amazonaws.com), IMAGE(레지스트리/저장소:태그)
#   ECOS_API_KEY(production Secret. 비어 있으면 금리 자동 갱신 없이 돈다)
# 하는 일: 새 이미지 받기 → 지금 컨테이너를 기록해 두고 교체 → 헬스체크 → 실패하면 이전 이미지로 되돌림
set -euo pipefail

# 컨테이너에는 `-e ECOS_API_KEY`(이름만)로 넘긴다. 값이 docker 명령줄·로그에 남지 않게
export ECOS_API_KEY="${ECOS_API_KEY:-}"

NAME=app
STATE_DIR=/opt/capstone
HEALTH_URL=http://localhost/actuator/health
mkdir -p "$STATE_DIR"

log() { echo "[$(date -u +%H:%M:%S)] $*"; }

run_container() {
  # 메모리 2GB 서버에서 OS·Docker 몫을 남기고 1.5GB까지. JVM은 그 75%를 힙으로 쓴다(Dockerfile)
  docker run -d --name "$NAME" --restart unless-stopped \
    --memory 1500m -p 80:8080 -e PORT=8080 -e ECOS_API_KEY "$1" > /dev/null
}

# 지금 컨테이너에 들어 있는 ECOS 키. 비교에만 쓰고 출력하지 않는다
running_ecos_key() {
  docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$NAME" 2>/dev/null | sed -n 's/^ECOS_API_KEY=//p' || true
}

wait_healthy() {
  for i in $(seq 1 60); do
    if curl -fsS "$HEALTH_URL" > /dev/null 2>&1; then
      log "헬스체크 통과 (${i}회째, 약 $((i * 2))초)"
      return 0
    fi
    sleep 2
  done
  return 1
}

log "ECR 로그인"
aws ecr get-login-password --region "$REGION" | docker login --username AWS --password-stdin "$REGISTRY" > /dev/null

log "이미지 받기: $IMAGE"
docker pull -q "$IMAGE"

PREV=$(docker inspect --format '{{.Config.Image}}' "$NAME" 2>/dev/null || true)
log "현재 이미지: ${PREV:-없음(첫 배포)}"

if [ "$PREV" = "$IMAGE" ]; then
  # Secret에 키를 넣거나 바꾼 뒤 같은 커밋을 다시 배포하면 컨테이너를 새로 만들어야 키가 들어간다
  if [ "$(running_ecos_key)" = "$ECOS_API_KEY" ]; then
    log "이미 같은 이미지가 같은 설정으로 돌고 있어 교체하지 않습니다"
    wait_healthy && exit 0
  else
    log "같은 이미지지만 ECOS 키가 바뀌어 컨테이너를 다시 만듭니다"
  fi
fi

docker rm -f "$NAME" > /dev/null 2>&1 || true
run_container "$IMAGE"

if wait_healthy; then
  [ -n "$PREV" ] && echo "$PREV" > "$STATE_DIR/previous-image"
  echo "$IMAGE" > "$STATE_DIR/current-image"
  echo "$(date -u +%FT%TZ) $IMAGE" >> "$STATE_DIR/deploy-history.log"
  # 최근 것만 남기고 안 쓰는 이미지는 지운다 (디스크 20GB)
  docker image prune -af --filter "until=168h" > /dev/null 2>&1 || true
  log "배포 완료"
  exit 0
fi

log "헬스체크 실패. 새 컨테이너 로그:"
docker logs --tail 50 "$NAME" 2>&1 || true
docker rm -f "$NAME" > /dev/null 2>&1 || true

if [ -n "$PREV" ]; then
  log "롤백: $PREV"
  run_container "$PREV"
  if wait_healthy; then
    echo "$(date -u +%FT%TZ) ROLLBACK $IMAGE -> $PREV" >> "$STATE_DIR/deploy-history.log"
    log "롤백 완료. 이전 버전이 서비스 중입니다"
  else
    log "롤백한 이미지도 헬스체크 실패. 직접 확인이 필요합니다"
  fi
else
  log "첫 배포라 되돌릴 이미지가 없습니다"
fi
exit 1
