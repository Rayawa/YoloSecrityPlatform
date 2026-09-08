# 智能安防管理平台 —— 生产镜像（讲义第七章 Docker 部署）
# 构建前需先在项目根目录执行：mvn clean package -DskipTests
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY target/security-monitor-1.0.0.jar app.jar
# 端口、数据库、AI 服务地址全部由 docker-compose.yml 的 environment 注入
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
