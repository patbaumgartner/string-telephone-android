# Build context is the repository root (see server/compose.yaml).
FROM gradle:9.8.1-jdk27 AS build
WORKDIR /workspace

COPY gradle/libs.versions.toml gradle/libs.versions.toml
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY core core
COPY server/api server/api

RUN gradle --no-daemon :server:api:installDist

FROM eclipse-temurin:27-jre
# The Compose healthcheck probes /ready from inside the container, and the
# slim JRE image ships no HTTP client. Without curl the container never turns
# healthy, so `docker compose up --wait` fails on a perfectly fine API.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /workspace/server/api/build/install/api/ /app/
ENV PATH="/app/bin:${PATH}"
EXPOSE 8080
CMD ["/app/bin/api"]
