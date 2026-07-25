# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app
RUN useradd -r -u 10001 appuser
COPY apps/worker/build/libs/med0001-worker.jar /app/app.jar
USER appuser
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
