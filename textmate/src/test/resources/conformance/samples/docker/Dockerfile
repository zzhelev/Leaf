# syntax=docker/dockerfile:1
FROM eclipse-temurin:25-jdk AS build

ARG GRADLE_OPTS="-Dorg.gradle.daemon=false"
ENV LANG=C.UTF-8 \
    TZ=UTC

WORKDIR /src
COPY --chown=1000:1000 . .
RUN apt-get update && apt-get install -y --no-install-recommends git git-lfs \
    && rm -rf /var/lib/apt/lists/*
RUN ./gradlew :app:packageDeb

FROM debian:12-slim
COPY --from=build /src/app/build/compose/binaries/main/deb/*.deb /tmp/
EXPOSE 8080/tcp
HEALTHCHECK CMD ["test", "-f", "/opt/leaf/bin/Leaf"]
ENTRYPOINT ["/opt/leaf/bin/Leaf"]
