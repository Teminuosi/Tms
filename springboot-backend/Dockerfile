# 构建阶段
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# 运行阶段
FROM eclipse-temurin:21-jre
WORKDIR /app

# CI 注入构建标识:镜像 tag 固定是 :latest,没有版本号可比,
# 所以拿这个 commit 跟 GitHub 上 main 的最新 commit 比,判断有没有新版本。
# 本地手工构建不传就是 dev,面板一律不提示更新。
ARG BUILD_COMMIT=dev
ARG BUILD_TIME=""
ENV TMS_BUILD_COMMIT=$BUILD_COMMIT
ENV TMS_BUILD_TIME=$BUILD_TIME

ENV JAVA_OPTS="-Xmx512m -Xms256m -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"
ENV LANG=en_US.UTF-8
ENV LANGUAGE=en_US:en
ENV LC_ALL=en_US.UTF-8

# Ubuntu 24.04 起源配置在 /etc/apt/sources.list.d/ubuntu.sources(deb822),老的 sources.list 只剩注释,
# 只改它等于没换源(2026-11-11 GitHub 构建机解析不了 *.ubuntu.com、连续失败才发现)。两处都换;arm64 走 ports
RUN for f in /etc/apt/sources.list /etc/apt/sources.list.d/ubuntu.sources; do \
        if [ -f "$f" ]; then sed -i -e 's#http://archive.ubuntu.com#http://mirrors.ustc.edu.cn#g' \
            -e 's#http://security.ubuntu.com#http://mirrors.ustc.edu.cn#g' \
            -e 's#http://ports.ubuntu.com#http://mirrors.ustc.edu.cn#g' "$f"; fi; \
    done \
    && apt-get update \
    && apt-get install -y --no-install-recommends fontconfig fonts-dejavu \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /app/target/*.jar app.jar
EXPOSE 6365

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
