# 智能安防远程监控与告警平台

项目已实现《智能安防远程监控与告警平台-实训讲义》第一至第六章（Docker 之前）的可运行版本：Spring Boot、Vue 3、MySQL 8、MyBatis-Plus、WebSocket、YOLO 推理服务和模型训练入口。

管理端采用经典后台布局，包含监控大屏、设备信息、AI识别记录、实时告警中心和告警处置记录。Vue 由 Spring Boot 静态目录直接托管，不需要 Node.js。

## 技术版本

- Java 17
- Spring Boot 3.3.4
- MyBatis-Plus 3.5.7
- MySQL Connector/J 8.0.33
- MySQL 8.0
- Vue 3 本地静态版
- Python 3、FastAPI、Ultralytics YOLO

## 一 启动本机私有 MySQL 8

```bash
brew services start mysql@8.0
mysql -h127.0.0.1 -P3306 -uroot -proot < doc/mysql8-security-monitor.sql
```

默认连接 `127.0.0.1:3306/security_monitor`。如果私有数据库参数不同，可使用环境变量覆盖：

```bash
export DB_HOST=127.0.0.1
export DB_PORT=3306
export DB_NAME=security_monitor
export DB_USERNAME=root
export DB_PASSWORD=root
```

## 二 启动管理平台

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 mvn spring-boot:run
```

访问地址：

- 管理平台：<http://localhost:423>
- OpenAPI：<http://localhost:423/swagger-ui/index.html>
- 设备接口：<http://localhost:423/api/devices>
- 告警接口：<http://localhost:423/api/alarms>
- WebSocket：`ws://localhost:423/ws/alerts`

如需修改端口，可执行 `SERVER_PORT=8888 mvn spring-boot:run`。

## 三 启动 AI 视觉分析服务

```bash
cd ai-service
brew install python@3.12
python3.12 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
python server.py
```

服务启动后访问 <http://127.0.0.1:8000/health>。模型推理和训练命令见 [ai-service/README.md](ai-service/README.md)。

## 四 联调接口

直接提交模拟识别结果，可验证“对象映射告警 → 写入 MySQL → WebSocket 推送”，无需先启动 Python：

```bash
curl -X POST http://localhost:423/api/ai/events \
  -H 'Content-Type: application/json' \
  -d '{"area":"A区西门","deviceCode":"CAM-001","objects":[{"class":"smoke","conf":0.96,"model":"demo"}]}'
```

调用真实 AI 服务分析网络图片：

```bash
curl -X POST http://localhost:423/api/ai/analyze \
  -H 'Content-Type: application/json' \
  -d '{"imageUrl":"https://ultralytics.com/images/bus.jpg","area":"A区西门","deviceCode":"CAM-001"}'
```

更新告警状态：

```bash
curl -X PATCH http://localhost:423/api/alarms/1/status \
  -H 'Content-Type: application/json' \
  -d '{"status":"处置中"}'
```

状态只允许 `待处置`、`处置中` 和 `已关闭`。
