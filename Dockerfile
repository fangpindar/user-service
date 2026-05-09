# =====================================================
# Build stage
# =====================================================
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Copy pom first to leverage Docker layer caching
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source and build
COPY src ./src
RUN mvn clean package -DskipTests -B

# =====================================================
# Runtime stage
# =====================================================
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Add curl for healthcheck (optional)
RUN apk add --no-cache curl

# Run as non-root user
RUN addgroup -S app && adduser -S app -G app
USER app

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
