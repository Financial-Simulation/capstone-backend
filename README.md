# capstone-backend
test

## Docker로 실행

```bash
docker build -t capstone-backend .
docker run -p 8080:8080 capstone-backend   # http://localhost:8080
```

- 포트는 `PORT` 환경변수로 바꾼다 (기본 8080). JVM 메모리는 컨테이너 메모리의 75%까지 쓴다.
- 프론트 주소(CORS)는 `APP_CORS_ALLOWEDORIGINS` 환경변수로 덮어쓴다 (쉼표로 여러 개). 기본값은 `application.properties`의 `app.cors.allowed-origins`.

## OpenRouter 모델 병렬 비교

PR의 변경분은 `.github/workflows/ai-review.yml`이 OpenRouter에 보내 최대 4개 모델로
동시에 리뷰한다. 결과는 소요 시간과 함께 모델별 접기 섹션이 있는 댓글 하나로 게시하며,
실패한 모델이 있어도 성공한 모델 결과는 남는다. 리뷰는 참고용이므로 CI를 막지 않는다.

1. GitHub 저장소의 **Settings → Secrets and variables → Actions**에서
   `OPENROUTER_API_KEY` 시크릿을 등록한다.
2. 같은 위치의 Variables에 `AI_REVIEW_MODELS`를 등록한다. 값은 JSON 배열 또는
   쉼표 구분 목록이다. 예: `["openai/gpt-4.1-mini","anthropic/claude-3.7-sonnet"]`
3. 등록하지 않으면 기존 `AI_REVIEW_MODEL` 값 또는
   `nvidia/nemotron-3.5-lightning:free` 한 모델만 사용한다.

모델 종류와 호출 횟수에 따라 비용이 발생할 수 있다. OpenRouter 모델별 데이터 처리 정책을
확인해야 하며, 외부 서비스로 보내면 안 되는 변경분이 있는 저장소에는 사용하지 않는다.
포크 PR은 시크릿을 읽을 수 없으므로 리뷰가 실행되지 않는다.

## Render 배포

1. New → Web Service → 이 저장소 연결, Language는 **Docker** (저장소의 `Dockerfile`을 그대로 씀)
2. 환경변수: 브라우저가 이 서버를 바로 부르면 `APP_CORS_ALLOWEDORIGINS`에 프론트 주소. 프론트 서버가 대신 부르면(Next rewrites) 필요 없음
3. 무료 플랜(CPU 0.1개, 512MB)은 `/allocations/recommend`·`/robust`가 많이 느리다. 계산까지 시연하려면 유료 플랜을 쓴다
