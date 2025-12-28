FROM eclipse-temurin:21-jre
WORKDIR /app

# Копируем fat jar (shadowJar), который содержит зависимости
COPY build/libs/*-all.jar /app/app.jar

ENV HOST=0.0.0.0
ENV PORT=8080
EXPOSE 8080

CMD ["java", "-jar", "/app/app.jar"]