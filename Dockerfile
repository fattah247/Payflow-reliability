FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre-alpine
# Run as an unprivileged user; the app needs no root access.
RUN addgroup -S payflow && adduser -S payflow -G payflow
WORKDIR /app
COPY --from=build /app/target/Payflow-reliability-0.1.0.jar app.jar
USER payflow
EXPOSE 8080
# Size the heap from the container's memory limit; shut down gracefully on SIGTERM (server.shutdown=graceful).
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
  CMD wget -qO- http://localhost:8080/actuator/health/liveness >/dev/null || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
