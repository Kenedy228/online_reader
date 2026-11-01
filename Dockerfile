FROM eclipse-temurin:21-jdk-noble@sha256:bd6ac06e2a2df928af21c75c4a8a0f2829a93377a65761d6a577bb32281e2ac3 AS build
# Wrapper verifies the ZIP checksum; without unzip it silently selects tar.gz.
RUN apt-get update && apt-get install -y --no-install-recommends unzip \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY main/ main/
COPY test/ test/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp clean verify

FROM eclipse-temurin:21-jre-noble@sha256:161310cd5bd41fd549d19cf9f90b614897cb4a1532555c613b43b7e0628fe5d6
RUN groupadd --gid 10001 reader \
    && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin reader
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/target/online-reader-0.0.1-SNAPSHOT.jar app.jar
USER 10001:10001
EXPOSE 8080
STOPSIGNAL SIGTERM
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
