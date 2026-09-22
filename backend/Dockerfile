FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY backend/pom.xml .
RUN mvn -B -DskipTests dependency:go-offline
COPY backend/src ./src
RUN mvn -B -DskipTests package

FROM python:3.11-slim AS whisper
COPY workers/requirements.txt /tmp/requirements.txt
RUN pip install --no-cache-dir -r /tmp/requirements.txt --no-deps \
    && pip install --no-cache-dir openai-whisper==20250625 --no-deps

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /build/target/*.jar app.jar
COPY --from=whisper /usr/local/lib/python3.11 /usr/local/lib/python3.11
COPY --from=whisper /usr/local/bin/python /usr/local/bin/python3
COPY --from=whisper /usr/local/bin/python3 /usr/local/bin/python3.11
COPY workers /app/workers
ENV FFMPEG_PATH=ffmpeg \
    WHISPER_PYTHON=python3 \
    WHISPER_WORKER=/app/workers/whisper_worker.py \
    WORKBENCH_STORAGE_DIR=/app/storage \
    WHISPER_MODEL_DIR=/app/models/whisper
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
