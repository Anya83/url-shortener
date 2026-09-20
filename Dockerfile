# Multi-stage: the JDK and the Maven cache never reach the runtime image.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# Dependencies are resolved in their own layer, keyed on pom.xml alone, so a
# source-only change does not re-download the world.
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B clean package -DskipTests

FROM eclipse-temurin:17-jre-alpine AS runtime
WORKDIR /app

# Unprivileged user. A container that only serves HTTP has no reason to run
# as root.
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

COPY --from=build --chown=spring:spring /build/target/url-shortener-*.jar app.jar

EXPOSE 8080

# MaxRAMPercentage rather than a fixed -Xmx: the JVM then sizes its heap from
# the container's cgroup limit, so changing the memory limit in compose or
# Kubernetes does not require rebuilding the image.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseG1GC"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
