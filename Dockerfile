ARG JAVA_VERSION=21
FROM node:22.22.1-bookworm-slim AS web
WORKDIR /build
COPY admin-web/package*.json ./
RUN npm ci --no-audit --no-fund
COPY admin-web/ ./
RUN npm run build

FROM node:22.22.1-bookworm-slim AS pi
WORKDIR /runtime/pi
COPY runtime/pi/package*.json ./
RUN npm ci --omit=dev --ignore-scripts --no-audit --no-fund
COPY runtime/pi/limited-rpc.mjs ./

FROM maven:3.9.14-eclipse-temurin-${JAVA_VERSION} AS java
WORKDIR /build
COPY pom.xml ./
COPY src/main/ src/main/
COPY --from=web /build/dist admin-web/dist
RUN --mount=type=cache,target=/root/.m2 mvn -B -Pfrontend -DskipTests -Dmaven.wagon.http.retryHandler.count=5 package

FROM eclipse-temurin:${JAVA_VERSION}-jre-noble
RUN apt-get -o Acquire::Retries=3 update && apt-get install -y --no-install-recommends libstdc++6 ca-certificates && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 bot && useradd --uid 10001 --gid bot --no-create-home bot
COPY --from=web /usr/local/bin/node /usr/local/bin/node
WORKDIR /app
COPY --from=java /build/target/qqbot-pi-0.2.0-SNAPSHOT.jar app.jar
COPY --from=pi /runtime/pi runtime/pi
RUN mkdir /app/.state && chown bot:bot /app/.state
USER bot
ENV BOT_STATE_DIR=/app/.state
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=65","-jar","/app/app.jar"]
