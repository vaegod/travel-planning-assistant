# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /src
COPY travel-java-server/pom.xml ./pom.xml
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp dependency:go-offline
COPY travel-java-server/src/main ./src/main
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -g 10001 app && adduser -D -u 10001 -G app app && mkdir -p /app/cache && chown -R app:app /app
WORKDIR /app
COPY --from=build --chown=app:app /src/target/travel-java-server-0.0.1-SNAPSHOT.jar /app/app.jar
USER 10001:10001
EXPOSE 3200
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=12 CMD wget -q -O - http://127.0.0.1:3200/api/health > /dev/null || exit 1
ENTRYPOINT ["java","-XX:MaxRAMPercentage=60","-jar","/app/app.jar"]
