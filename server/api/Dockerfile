# Build context is the repository root (see server/compose.yaml).
FROM gradle:9.8.0-jdk17 AS build
WORKDIR /workspace

COPY gradle/libs.versions.toml gradle/libs.versions.toml
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY core core
COPY server/api server/api

RUN gradle --no-daemon :server:api:installDist

FROM eclipse-temurin:24-jre
WORKDIR /app
COPY --from=build /workspace/server/api/build/install/api/ /app/
ENV PATH="/app/bin:${PATH}"
EXPOSE 8080
CMD ["/app/bin/api"]
