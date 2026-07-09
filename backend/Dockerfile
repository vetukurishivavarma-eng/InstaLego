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
CMD ["java", "-jar", "app.jar", "--spring.profiles.active=prod"]
