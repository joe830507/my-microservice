# 六個後端服務共用一份 Dockerfile，用 build arg 指定服務：
#   docker build -f backend/Dockerfile --build-arg SERVICE=user-service -t my-microservice/user-service:0.1.0 backend
# SERVICE 是資料夾名稱去掉 spring- 前綴：api-gateway、user-service、product-service、inventory-service、order-service、payment-service
# 建置階段沒有用到 SERVICE，所以六次建置會共用同一個建置快取，只編譯一次

# ---------- 階段 1：編譯所有模組（common + 6 個服務）----------
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /src

# 先只複製 pom 與 Maven wrapper：依賴沒變時，下載依賴這一層可以用快取
COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY common/pom.xml common/pom.xml
COPY spring-api-gateway/pom.xml spring-api-gateway/pom.xml
COPY spring-user-service/pom.xml spring-user-service/pom.xml
COPY spring-product-service/pom.xml spring-product-service/pom.xml
COPY spring-inventory-service/pom.xml spring-inventory-service/pom.xml
COPY spring-order-service/pom.xml spring-order-service/pom.xml
COPY spring-payment-service/pom.xml spring-payment-service/pom.xml
# Windows 的工作目錄可能是 CRLF 行尾，mvnw 的 #!/bin/sh\r 在 Linux 上會找不到直譯器
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw

COPY common common
COPY spring-api-gateway spring-api-gateway
COPY spring-user-service spring-user-service
COPY spring-product-service spring-product-service
COPY spring-inventory-service spring-inventory-service
COPY spring-order-service spring-order-service
COPY spring-payment-service spring-payment-service

# 測試要 Docker（Testcontainers），在 CI 先跑，不放進映像檔的建置
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -DskipTests package

# ---------- 階段 2：執行 ----------
FROM eclipse-temurin:21-jre-alpine
ARG SERVICE
# 非 root 使用者；固定 uid，K8s 的 runAsNonRoot 才能驗證
RUN addgroup -g 10001 app && adduser -D -u 10001 -G app app
WORKDIR /app
COPY --from=build /src/spring-${SERVICE}/target/${SERVICE}-0.0.1-SNAPSHOT.jar app.jar
USER 10001:10001

# 容器記憶體上限的 75% 給 JVM 堆積；OOM 時直接結束讓 K8s 重啟，不要拖著不健康的程序
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
