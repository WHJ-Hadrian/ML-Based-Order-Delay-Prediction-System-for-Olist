FROM maven:3.9.9-eclipse-temurin-17 AS build

ARG SERVICE_MODULE
WORKDIR /workspace
COPY pom.xml .
COPY platform-runtime platform-runtime
COPY api-gateway api-gateway
COPY order-service order-service
COPY dataset-service dataset-service
COPY job-service job-service
COPY prediction-service prediction-service
COPY model-management-service model-management-service
COPY analytics-query-service analytics-query-service
RUN mvn -B -pl "${SERVICE_MODULE}" -am -DskipTests package

FROM eclipse-temurin:17-jre
ARG SERVICE_MODULE
WORKDIR /app
COPY --from=build /workspace/${SERVICE_MODULE}/target/${SERVICE_MODULE}-*.jar /app/application.jar
USER 10001
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/application.jar"]
