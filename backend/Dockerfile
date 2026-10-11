FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY backend/pom.xml .
COPY backend/src ./src
# 容器内走阿里云镜像：当前网络直连 Maven Central 会被 403 拒绝（宿主机的 ~/.m2/settings.xml
# 配了同样的镜像，这里把口径带进构建，保证容器内外解析到同一份依赖）。
RUN mkdir -p /root/.m2 && printf '%s\n' \
    '<?xml version="1.0" encoding="UTF-8"?>' \
    '<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">' \
    '  <mirrors>' \
    '    <mirror>' \
    '      <id>aliyun</id>' \
    '      <mirrorOf>central</mirrorOf>' \
    '      <url>https://maven.aliyun.com/repository/public</url>' \
    '    </mirror>' \
    '  </mirrors>' \
    '</settings>' > /root/.m2/settings.xml
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
# tesseract + chi_sim：视觉分支的 OCR 引擎（P1 决策 #4）。缺了它 OCR 会整路降级为「无画面文本」，
# 降级不阻断主链路，但验收要求的中文命中率基准就没得测了。
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg curl tesseract-ocr tesseract-ocr-chi-sim \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /build/target/*.jar app.jar
ENV FFMPEG_PATH=ffmpeg \
    WORKBENCH_STORAGE_DIR=/app/storage
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
