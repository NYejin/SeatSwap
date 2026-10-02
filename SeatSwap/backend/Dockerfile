# ---- Build stage ----
FROM gradle:8.7-jdk17 AS build
WORKDIR /app
COPY build.gradle ./
COPY src ./src
RUN gradle build -x test --no-daemon

# ---- Run stage ----
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
