#!/bin/bash
# EC2 안에서 실행된다. GitHub Actions가 SSM으로 이 파일 내용을 보내고, 앞에 아래 변수를 붙인다.
#   REGION, REGISTRY(예: 123456789012.dkr.ecr.ap-southeast-2.amazonaws.com), IMAGE(레지스트리/저장소:태그)
# 하는 일: 새 이미지 받기 → 지금 컨테이너를 기록해 두고 교체 → 헬스체크 → 실패하면 이전 이미지로 되돌림
set -euo pipefail

NAME=app
STATE_DIR=/opt/capstone
HEALTH_URL=http://localhost/actuator/health
mkdir -p "$STATE_DIR"

log() { echo "[$(date -u +%H:%M:%S)] $*"; }

run_container() {
  # 메모리 2GB 서버에서 OS·Docker 몫을 남기고 1.5GB까지. JVM은 그 75%를 힙으로 쓴다(Dockerfile)
  docker run -d --name "$NAME" --restart unless-stopped \
    --memory 1500m -p 80:8080 -e PORT=8080 "$1" > /dev/null
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
  log "이미 같은 이미지가 돌고 있어 교체하지 않습니다"
  wait_healthy && exit 0
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
