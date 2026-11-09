FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# 의존성만 먼저 받아 두면 소스만 바뀐 빌드에서 이 층을 다시 쓴다
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon -q > /dev/null

COPY src src
# bootJar는 -plain.jar도 같이 만들어서 실행 jar만 골라 둔다
RUN ./gradlew bootJar -x test --no-daemon -q \
    && cp "$(ls build/libs/*.jar | grep -v -- '-plain.jar$')" app.jar

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --no-create-home app
COPY --from=build /app/app.jar app.jar
USER app

EXPOSE 8080
# Render 등은 포트를 PORT로 넘긴다. 메모리는 플랜 크기에 맞춰 75%까지
ENTRYPOINT ["sh", "-c", "exec java -XX:MaxRAMPercentage=75 -jar app.jar --server.port=${PORT:-8080}"]
