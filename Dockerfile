# Stage 1: Build
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app

# Download dependencies first (layer cache)
COPY pom.xml .
RUN mvn dependency:go-offline -q

COPY src ./src
RUN mvn package -DskipTests -q

# Stage 2: Runtime
# Ubuntu 22.04 (jammy) — glibc + OpenSSL 3, required by TDLib linux_amd64_gnu_ssl3
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
    libssl3 \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /app/target/*.jar app.jar

# TDLib session is stored here — mount a volume for persistence
VOLUME ["/app/tdlib-session"]

EXPOSE 8080

ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-Xmx150m", "-Xms150m", \
    "-XX:MaxMetaspaceSize=120m", \
    "-XX:ReservedCodeCacheSize=48m", \
    "-XX:MaxDirectMemorySize=20m", \
    "-Xss256k", \
    "-XX:+UseSerialGC", \
    "-XX:+ExitOnOutOfMemoryError", \
    "-jar", "app.jar", \
    "--spring.profiles.active=heroku"]
