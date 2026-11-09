# capstone-backend
test

## Docker로 실행

```bash
docker build -t capstone-backend .
docker run -p 8080:8080 capstone-backend   # http://localhost:8080
```

- 포트는 `PORT` 환경변수로 바꾼다 (기본 8080). JVM 메모리는 컨테이너 메모리의 75%까지 쓴다.
- 프론트 주소(CORS)는 `APP_CORS_ALLOWEDORIGINS` 환경변수로 덮어쓴다 (쉼표로 여러 개). 기본값은 `application.properties`의 `app.cors.allowed-origins`.

## Render 배포

1. New → Web Service → 이 저장소 연결, Language는 **Docker** (저장소의 `Dockerfile`을 그대로 씀)
2. 환경변수: 브라우저가 이 서버를 바로 부르면 `APP_CORS_ALLOWEDORIGINS`에 프론트 주소. 프론트 서버가 대신 부르면(Next rewrites) 필요 없음
3. 무료 플랜(CPU 0.1개, 512MB)은 `/allocations/recommend`·`/robust`가 많이 느리다. 계산까지 시연하려면 유료 플랜을 쓴다
