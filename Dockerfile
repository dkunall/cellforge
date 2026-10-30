FROM maven:3.9.9-eclipse-temurin-21-alpine AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package \
    && java -Djarmode=tools -jar target/cellforge-*.jar extract --layers --launcher --destination /extracted

# One image for both roles; CELLFORGE_ROLE picks edge or cell at startup.
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S cellforge && adduser -S -G cellforge -u 10001 cellforge
WORKDIR /app
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
USER 10001
ENV PORT=8080 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=5s --timeout=3s --start-period=60s --retries=10 \
    CMD wget -qO- "http://localhost:${PORT}/actuator/health/readiness" | grep -q UP || exit 1
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
