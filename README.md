# 智能安防远程监控与告警平台

基于《智能安防远程监控与告警平台-实训讲义》实现的一个前后端分离教学项目:**Spring Boot 3 + Vue 3 + MySQL 8 + MyBatis-Plus + WebSocket**,配合独立的 **FastAPI + YOLO** 视觉分析服务,实现"AI 识别 → 规则映射 → 数据库落盘 → WebSocket 实时推送 → 前端大屏展示与处置"的完整安防告警闭环。

管理端采用经典后台布局,包含**监控大屏、设备信息、AI 识别记录、实时告警中心、告警处置记录**五个页面。Vue 使用本地静态版本由 Spring Boot 静态目录直接托管,**不需要 Node.js 环境**,克隆即可运行。

> 零基础想读懂每一行代码?请阅读配套教程 [docs/代码详解与入门教程.md](代码详解与入门教程.md)。

## 系统架构

```
┌─────────────────────────────┐       ┌──────────────────────────────┐
│  浏览器(单页应用)             │       │   AI 视觉分析服务 (Python)      │
│  Vue 3 全局版 + app.js       │       │   FastAPI + YOLOv5            │
│  http://localhost:423        │       │   http://127.0.0.1:8000      │
└──────┬──────────┬───────────┘       └───────────────▲──────────────┘
       │ HTTP/JSON │ WebSocket(实时告警)                 │ HTTP POST /detect
       ▼          ▼                                    │
┌───────────────────────────────────────────────────────┴───────────┐
│                Spring Boot 管理平台 (端口 423, 默认)                  │
│  Controller → Service → AlarmRuleMapper → Mapper → MySQL 8          │
│  静态页面托管: src/main/resources/static                            │
└──────────────────────────────────────────────▲────────────────────┘
                                               │ JDBC
                                        MySQL 8(默认 127.0.0.1:3306)
                                        security_monitor 库(device、alarm 表)
```

一次告警的完整链路:

1. 前端在"AI 识别记录"页填写图片地址,或外部系统直接 POST 检测结果;
2. Spring Boot 将图片地址转发给 Python AI 服务,由 YOLO 完成目标检测;
3. AI 服务返回识别出的对象列表(如 `smoke 0.96`、`person 0.91`);
4. `AlarmRuleMapper` 按规则把对象归类成业务告警(烟火异常/人员闯入/人员聚集/车辆异常/动物进入/物品异常);
5. 告警写入 MySQL `alarm` 表,事务提交后通过 WebSocket 向所有在线页面广播;
6. 页面收到 `new-alarm` 实时弹出提示并刷新列表,安保人员可"开始处置 / 关闭告警"。

## 功能特性

| 模块 | 说明 |
| --- | --- |
| 监控大屏 | 在线设备数、待处置告警数、告警总数、AI 事件数等指标与最新告警流 |
| 设备信息 | 摄像头 / 门禁 / 烟感等设备列表,支持按区域、状态筛选 |
| AI 识别记录 | 填图片地址发起 YOLO 识别,查看识别对象与映射出的告警 |
| 实时告警中心 | 待处置 / 处置中的告警,WebSocket 自动刷新,支持处置状态流转 |
| 告警处置记录 | 已关闭告警的历史台账 |
| WebSocket 推送 | 新告警与状态变更实时广播,顶部显示通道连接状态 |
| OpenAPI 文档 | 启动后访问 `/swagger-ui/index.html` 在线调试接口 |

## 技术版本

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| Java | 17 | 后端语言 |
| Spring Boot | 3.3.4 | Web、WebSocket、事务、AOP 等基础框架 |
| MyBatis-Plus | 3.5.7 | ORM,内置通用 CRUD(`BaseMapper` / `IService`) |
| MySQL | 8.0 | 数据存储(`mysql-connector-j` 8.0.33) |
| Vue | 3(全局版) | 前端,免 Node.js 构建 |
| Python | 3.10–3.12 | AI 服务 |
| FastAPI / Uvicorn | 0.115 / 0.34 | AI 服务 HTTP 框架 |
| Ultralytics YOLOv5 | - | 目标检测推理 |
| Lombok | - | 编译期生成 getter/setter 等样板代码 |

## 目录结构

```
.
├── pom.xml                        # Maven 构建配置(Spring Boot 3.3.4 / Java 17)
├── src/main/java/top/rayawa/monitor/
│   ├── SecurityMonitorApplication.java   # 启动入口(@SpringBootApplication)
│   ├── controller/                # REST 接口层:/api/devices、/api/alarms、/api/dashboard、/api/ai/*
│   ├── service/                   # 业务层:AlarmService(告警)、AiVisionService(AI 调用)、AlarmRuleMapper(识别结果→告警规则)
│   ├── service/impl/              # 业务实现(事务 + 提交后 WebSocket 广播)
│   ├── mapper/                    # MyBatis-Plus Mapper 接口
│   ├── domain/                    # 实体类(与数据库表一一对应,当前业务实际使用)
│   ├── model/                     # 同名实体类(历史残留,未被引用,可忽略或删除)
│   ├── dto/                       # 接口出入参 record(AI 请求/响应、状态更新等)
│   └── websocket/                 # WebSocket 配置与告警广播处理器
├── src/main/resources/
│   ├── application.yml            # 端口/数据源/AI 服务地址配置(全部可用环境变量覆盖)
│   ├── static/                    # 前端站点(Vue 全局版 + index.html + app.js + app.css)
│   └── top/rayawa/monitor/mapper/ # MyBatis XML(当前 CRUD 由 MyBatis-Plus 完成,XML 留作自定义 SQL 扩展点)
├── src/test/java/.../AlarmRuleMapperTest.java   # 告警规则映射的单元测试
├── ai-service/                    # Python AI 视觉分析服务
│   ├── server.py                  # FastAPI 服务:健康检查 / 图片检测(URL 与上传)
│   ├── train.py                   # YOLO 模型训练脚本
│   ├── firesec.yaml               # 烟火检测数据集的 YOLO 配置
│   └── requirements.txt           # Python 依赖
├── doc/                           # 学习素材:讲义、建库脚本、YOLOv5 源码包与训练好的模型
│   └── mysql8-security-monitor.sql# 建库建表 + 示例数据(device、alarm)
├── config/                        # maven-settings-macos.xml(阿里云镜像,可选)
└── target/                        # Maven 构建产物(自动生成)
```

## 快速上手

### 0. 环境要求

- macOS / Linux / Windows(Win 下将命令中的 `JAVA_HOME`、`brew` 按本机环境调整)
- JDK 17、Maven 3.8+
- MySQL 8.0(本仓库按 macOS `brew` 安装的私有实例编写)
- Python 3.10–3.12、可选 Homebrew

### 1. 初始化并启动本机 MySQL 8

```bash
brew services start mysql@8.0
mysql -h127.0.0.1 -P3306 -uroot -proot < doc/mysql8-security-monitor.sql
```

脚本会创建数据库 `security_monitor` 以及 `device`、`alarm` 两张表,并插入示例设备与告警,可重复执行。

默认连接 `127.0.0.1:3306/security_monitor`,账号密码 `root/root`。参数不同时用环境变量覆盖(所有配置项都支持,见下文"配置项"):

```bash
export DB_HOST=127.0.0.1 DB_PORT=3306 DB_NAME=security_monitor DB_USERNAME=root DB_PASSWORD=root
```

### 2. 启动管理平台(Spring Boot)

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 mvn spring-boot:run
```

首次运行会下载依赖,若下载缓慢,可使用 `config/maven-settings-macos.xml`(阿里云镜像):
`mvn -s config/maven-settings-macos.xml spring-boot:run`。

启动成功后可访问:

| 地址 | 说明 |
| --- | --- |
| <http://localhost:423> | 管理平台(前端单页应用) |
| <http://localhost:423/swagger-ui/index.html> | OpenAPI 在线接口文档 |
| <http://localhost:423/api/dashboard> | 大屏统计 |
| <http://localhost:423/api/devices> | 设备列表 |
| <http://localhost:423/api/alarms> | 告警列表 |
| `ws://localhost:423/ws/alerts` | WebSocket 实时告警通道 |

修改端口:`SERVER_PORT=8888 mvn spring-boot:run`。

### 3. 启动 AI 视觉分析服务(可选,用于真实图片识别)

```bash
cd ai-service
python3.12 -m venv .venv        # 首次:创建虚拟环境
source .venv/bin/activate
pip install -r requirements.txt # 首次:安装依赖
python server.py
```

健康检查:<http://127.0.0.1:8000/health>。模型推理细节与训练方法见 [ai-service/README.md](ai-service/README.md)。

> 模型权重(`yolov5s-coco.pt`、`yolov5s-dfire.pt`)与 YOLOv5 源码包(`yolov5-master.zip`)已随仓库放在 `doc/` 下,clone 后无需额外下载;首次推理时服务会在 `ai-service/.runtime/` 自动解压源码包。

### 4. 功能验证

**联调接口(无需 Python 服务即可验证完整链路)** —— 直接提交模拟识别结果,可验证"对象映射告警 → 写入 MySQL → WebSocket 推送":

```bash
curl -X POST http://localhost:423/api/ai/events \
  -H 'Content-Type: application/json' \
  -d '{"area":"A区西门","deviceCode":"CAM-001","objects":[{"class":"smoke","conf":0.96,"model":"demo"}]}'
```

**调用真实 AI 服务分析网络图片**(需先启动第 3 步):

```bash
curl -X POST http://localhost:423/api/ai/analyze \
  -H 'Content-Type: application/json' \
  -d '{"imageUrl":"https://ultralytics.com/images/bus.jpg","area":"A区西门","deviceCode":"CAM-001"}'
```

**更新告警状态**(状态只允许 `待处置`、`处置中`、`已关闭`):

```bash
curl -X PATCH http://localhost:423/api/alarms/1/status \
  -H 'Content-Type: application/json' \
  -d '{"status":"处置中"}'
```

浏览器打开 <http://localhost:423>,监控大屏会实时弹出新告警提示。

## REST 接口一览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/devices?area=&status=` | 设备列表,支持区域 / 状态过滤 |
| GET | `/api/alarms?status=&source=` | 告警列表(按事件时间倒序),支持过滤 |
| GET | `/api/dashboard` | 大屏统计(在线设备、待处置告警等) |
| PATCH | `/api/alarms/{id}/status` | 更新告警处置状态 |
| POST | `/api/ai/analyze` | 提交图片地址,经 AI 服务识别后生成告警 |
| POST | `/api/ai/events` | 直接提交模拟识别对象生成告警(联调用) |

WebSocket 广播消息体:

```json
{
  "event": "new-alarm | alarm-status-changed",
  "sentAt": "2026-09-07T16:00:00",
  "alarm": { "id": 1, "type": "烟火异常", "level": "高", "status": "待处置", "...": "..." }
}
```

## 配置项(环境变量覆盖)

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `SERVER_PORT` | `423` | 管理平台 HTTP 端口 |
| `DB_HOST` / `DB_PORT` / `DB_NAME` | `127.0.0.1` / `3306` / `security_monitor` | MySQL 地址 |
| `DB_USERNAME` / `DB_PASSWORD` | `root` / `root` | MySQL 账号密码 |
| `AI_SERVICE_URL` | `http://127.0.0.1:8000` | AI 视觉分析服务地址 |

AI 服务侧的环境变量见 [ai-service/README.md](ai-service/README.md)(`YOLO_COCO_MODEL`、`YOLO_FIRE_MODEL`、`YOLO_CONFIDENCE`)。

## 常见问题

- **告警数据不刷新 / 顶栏提示"实时通道重连中"**:确认页面经 `http://localhost:423` 访问,且后端在运行;WebSocket 断线后每 3 秒自动重连。
- **AI 识别报"AI识别服务不可用"**:第 3 步的 `python server.py` 未启动,或 `AI_SERVICE_URL` 指向错误。
- **`com.mysql.cj.jdbc.exceptions...` 连接失败**:MySQL 未启动或账号密码不对,检查第 1 步及数据库环境变量。
- **端口 423 被占用**:改用 `SERVER_PORT=8888 mvn spring-boot:run`,页面地址同步修改。
- **识别到对象但没有生成告警**:低于默认置信度阈值(`YOLO_CONFIDENCE=0.45`)的目标会被 AI 服务过滤,可调低阈值;未识别到任何对象时不会产生告警。

## 相关文档

- [docs/代码详解与入门教程.md](代码详解与入门教程.md) —— 逐文件、逐行讲解本项目代码,面向初学者
- [ai-service/README.md](ai-service/README.md) —— AI 推理服务与模型训练说明
- [doc/mysql8-security-monitor.sql](doc/mysql8-security-monitor.sql) —— 建库脚本(带注释)
