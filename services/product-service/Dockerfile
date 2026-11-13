# Multi-stage build: compile in Maven image, run on slim JRE.
# Layer caching: dependencies resolve before source copy → faster rebuilds.

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml .
COPY .mvn .mvn 2>/dev/null || true
# Build from repo root context so parent POM resolves; see docker-compose.
COPY services/product-service services/product-service
RUN cd services/product-service && \
    mvn -q -B dependency:go-offline && \
    mvn -q -B package -DskipTests

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
COPY --from=build /workspace/services/product-service/target/product-service-*.jar app.jar
USER app
EXPOSE 8082
# JVM flags: container-aware memory (UseContainerSupport is default in 17),
# MaxRAMPercentage keeps heap proportional to the pod limit.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+UseG1GC", "-jar", "app.jar"]
