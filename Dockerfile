# syntax=docker/dockerfile:1.7

# ── Stage 1: Build ──────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

# Copy Gradle wrapper and config first (leverage Docker cache)
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle/ gradle/

# Download dependencies (cached if build files unchanged)
RUN chmod +x gradlew && ./gradlew --no-daemon --console=plain dependencies

# Copy source and build
COPY src/ src/
RUN ./gradlew --no-daemon --console=plain bootJar -x test

# ── Stage 2: Runtime Base ───────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime-base

RUN addgroup -S app \
  && adduser -S app -G app \
  && apk add --no-cache su-exec

WORKDIR /app

COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh

RUN chown -R app:app /app \
  && chmod +x /usr/local/bin/docker-entrypoint.sh

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1

# ── Stage 3: Runtime (prebuilt jar) ────────────────────────
FROM runtime-base AS runtime-prebuilt

COPY backend.jar app.jar

ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
CMD ["java", \
  "-XX:+UseZGC", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]

# ── Stage 4: Runtime (build from source) ───────────────────
FROM runtime-base AS runtime

COPY --from=builder /app/build/libs/*.jar app.jar

ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
CMD ["java", \
  "-XX:+UseZGC", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
