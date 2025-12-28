# ---- Build stage ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# Сначала копируем файлы сборки — так лучше кешируется
COPY gradlew .
COPY gradle/ gradle/
COPY build.gradle.kts settings.gradle.kts gradle.properties ./

# Потом исходники
COPY src/ src/

# Собираем fat jar (shadowJar)
RUN chmod +x ./gradlew && ./gradlew clean shadowJar --no-daemon

# ---- Run stage ----
FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=build /app/build/libs/*-all.jar /app/app.jar

ENV HOST=0.0.0.0
ENV PORT=8080
EXPOSE 8080

CMD ["java", "-jar", "/app/app.jar"]