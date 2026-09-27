FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY backend/pom.xml .
COPY backend/src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /build/target/*.jar app.jar
ENV FFMPEG_PATH=ffmpeg \
    WORKBENCH_STORAGE_DIR=/app/storage
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
