# ---- Build stage ----
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app

COPY gradlew .
COPY gradle/ gradle/
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
COPY src/ src/

RUN chmod +x ./gradlew && ./gradlew clean shadowJar --no-daemon

# ---- Run stage ----
FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=build /app/build/libs/*-all.jar /app/app.jar

ENV HOST=0.0.0.0
ENV PORT=8080
EXPOSE 8080

CMD ["java", "-jar", "/app/app.jar"]