# Stage 1: Build Jar
FROM eclipse-temurin:21-jdk-alpine AS build
RUN apk add --no-cache maven

WORKDIR /app
COPY pom.xml .
COPY src ./src

# Cache downloaded JARs across builds
RUN --mount=type=cache,target=/root/.m2 mvn package -DskipTests \
    -Dmaven.artifact.threads=1 \
    -Daether.connector.basic.threads=1 \
    -Daether.connector.http.retryHandler.count=10 \
    -Daether.connector.requestTimeout=300000 \
    -Dhttp.keepAlive=false

# Stage 2: Minimal Runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

EXPOSE 4000
ENTRYPOINT ["java", "-jar", "app.jar"]