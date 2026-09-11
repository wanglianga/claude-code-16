# ---------- 构建阶段 ----------
FROM gradle:8.7-jdk17 AS build
WORKDIR /home/gradle/src
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
COPY src ./src
RUN --mount=type=cache,target=/home/gradle/.gradle,uid=1000,gid=1000 \
    gradle --no-daemon shadowJar \
    || { sleep 8; gradle --no-daemon shadowJar; } \
    || { sleep 8; gradle --no-daemon shadowJar; }

# ---------- 运行阶段 ----------
FROM eclipse-temurin:17-jre-jammy
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd -r app && useradd -r -g app -u 10001 app
WORKDIR /app
COPY --from=build /home/gradle/src/build/libs/scale-guard-all.jar app.jar
RUN mkdir -p /app/uploads && chown -R app:app /app
USER app
EXPOSE 8080
ENV PORT=8080 UPLOAD_DIR=/app/uploads
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS http://localhost:8080/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
