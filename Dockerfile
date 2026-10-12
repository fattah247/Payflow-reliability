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
ENTRYPOINT ["java", "-jar", "app.jar"]
