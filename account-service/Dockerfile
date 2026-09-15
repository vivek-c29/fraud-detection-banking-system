# Account service Dockerfile

# Stage 1: Build the JAR
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /app

# Copy Maven wrapper and configuration
COPY .mvn .mvn
COPY mvnw .
COPY pom.xml .

# Download dependencies first
RUN chmod +x mvnw
RUN ./mvnw dependency:go-offline

# Copy source code
COPY src src

# Build the application
RUN ./mvnw clean package -DskipTests

# Stage 2: Run the JAR

FROM eclipse-temurin:21-jre

WORKDIR /app

# Copy only the built JAR from builder stage
COPY --from=builder /app/target/*.jar app.jar

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "app.jar"]