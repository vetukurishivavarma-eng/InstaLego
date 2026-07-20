# Stage 1: Build with Maven
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# Copy pom.xml from the backend/ subdirectory
COPY backend/pom.xml .
# Download dependencies first (caching layer)
RUN mvn dependency:go-offline -B

# Copy source code
COPY backend/src ./src

# Build the JAR
RUN mvn package -DskipTests -B

# Stage 2: Run with JDK (slim)
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
# Heap is sized for Render's free-tier 512MB container (not the earlier 1024m default, which was
# tuned for local dev) — 384m heap + a capped metaspace leaves headroom for thread stacks/JIT/OS
# overhead. On a paid tier with more RAM, raise -Xmx accordingly for extra safety margin on very
# large files.
CMD ["java", "-Xmx384m", "-XX:MaxMetaspaceSize=96m", "-XX:+UseSerialGC", "-XX:+ExitOnOutOfMemoryError", "-XX:+HeapDumpOnOutOfMemoryError", "-XX:HeapDumpPath=/tmp/heapdump.hprof", "-jar", "app.jar", "--spring.profiles.active=prod"]
