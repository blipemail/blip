FROM gradle:8.12-jdk21 AS build

WORKDIR /app
COPY . .
RUN gradle :server:core:buildFatJar --no-daemon

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app
COPY --from=build /app/server/core/build/libs/*-all.jar app.jar

EXPOSE 8080

ENV PORT=8080

CMD ["java", "-jar", "app.jar"]
