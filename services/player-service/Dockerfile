FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B dependency:go-offline
RUN mvn -B dependency:copy -Dartifact=io.opentelemetry.javaagent:opentelemetry-javaagent:2.15.0 -DoutputDirectory=/otel
COPY src ./src
RUN mvn -B package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /otel/opentelemetry-javaagent-2.15.0.jar /otel/opentelemetry-javaagent.jar
ENV JAVA_TOOL_OPTIONS="-javaagent:/otel/opentelemetry-javaagent.jar"
ENV OTEL_INSTRUMENTATION_COMMON_DB_STATEMENT_SANITIZER_ENABLED=true
ENV OTEL_INSTRUMENTATION_SPRING_SCHEDULING_ENABLED=false
ENV OTEL_INSTRUMENTATION_LOGBACK_APPENDER_EXPERIMENTAL_CAPTURE_MDC_ATTRIBUTES=*
RUN apk add --no-cache wget
COPY --from=build /src/target/player-service-1.0.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
