# ==============================================================================
# Build Stage: Compile and Extract Spring Boot Layered Jar
# ==============================================================================
FROM eclipse-temurin:17-jdk-jammy AS builder

WORKDIR /workspace

# Copy Gradle wrapper and configuration files first to cache dependency downloads
COPY gradlew .
COPY gradle gradle
COPY gradle.properties .
COPY settings.gradle.kts .
COPY app/build.gradle.kts app/

# Ensure wrapper script is executable and strip potential Windows CRLF
RUN chmod +x gradlew && sed -i 's/\r$//' gradlew

# Pre-download dependencies into a cached Docker layer
RUN ./gradlew :app:dependencies --no-daemon

# Copy source code and build the application (skip tests per task specification)
COPY app/src app/src
RUN ./gradlew :app:bootJar -x test --no-daemon

# Extract Spring Boot 3.3+ layered jar using jarmode=tools
RUN java -Djarmode=tools -jar app/build/libs/app-0.0.1-SNAPSHOT.jar extract --layers --launcher --destination app/build/extracted

# ==============================================================================
# Runtime Stage: Minimal JRE running as a Non-Root User
# ==============================================================================
FROM eclipse-temurin:17-jre-jammy AS runtime

# Install curl minimally for container healthcheck
# apt-get cache is cleaned in the same layer to minimize image size (< 5MB overhead)
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Create dedicated non-root user and group
RUN groupadd -r appgroup && useradd -r -u 1001 -g appgroup appuser

WORKDIR /app

# Copy layered jar directories in order of change frequency:
# dependencies and loader change rarely; application changes with every commit
COPY --from=builder --chown=appuser:appgroup /workspace/app/build/extracted/dependencies/ ./
COPY --from=builder --chown=appuser:appgroup /workspace/app/build/extracted/spring-boot-loader/ ./
COPY --from=builder --chown=appuser:appgroup /workspace/app/build/extracted/snapshot-dependencies/ ./
COPY --from=builder --chown=appuser:appgroup /workspace/app/build/extracted/application/ ./

USER appuser

# Container-aware JVM options
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=50.0 -XX:+ExitOnOutOfMemoryError"

EXPOSE 8080

# Healthcheck hitting Spring Boot Actuator health endpoint
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
