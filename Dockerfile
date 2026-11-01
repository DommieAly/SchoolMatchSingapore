# syntax=docker/dockerfile:1
# Production image of SchoolMatch SG (docs/deployment.md). Build from the repository root:
#   docker build -t schoolmatch:latest .                                    for a quick local run
#   deploy/lightsail/deploy.sh <ip>                                         for the server (docs/deployment.md, step 5)
# The build stage runs on the build computer's own CPU ($BUILDPLATFORM), so an Apple silicon Mac does not emulate
# Maven; only the small runtime stage is made for the target platform (linux/amd64 or linux/arm64).
# Tests are not run here: CI runs ./mvnw verify on every push.

FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS build
WORKDIR /src
# Dependencies first, so a code-only change reuses this layer
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline
COPY src src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -Dmaven.test.skip=true package \
    && cp target/schoolmatch-*.jar /src/app.jar

FROM eclipse-temurin:21-jre
# The app reads data/snapshots relative to its working directory (app.dataset.dir) and writes nothing to disk.
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app --home-dir /app app
COPY data/snapshots data/snapshots
COPY --from=build /src/app.jar app.jar
USER app
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError" \
    TZ=Asia/Singapore
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
