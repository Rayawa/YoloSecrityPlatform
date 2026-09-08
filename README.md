# 智能安防远程监控与告警平台

基于《智能安防远程监控与告警平台-实训讲义》实现的一个前后端分离教学项目:**Spring Boot 3 + Vue 3 + MySQL 8 + MyBatis-Plus + WebSocket**,配合独立的 **FastAPI + YOLO** 视觉分析服务,实现"AI 识别 → 规则映射 → 数据库落盘 → WebSocket 实时推送 → 前端大屏展示与处置"的完整安防告警闭环。

管理端采用经典后台布局,包含**监控大屏、设备信息、AI 识别记录、实时告警中心、告警处置记录**五个页面。Vue 使用本地静态版本由 Spring Boot 静态目录直接托管,**不需要 Node.js 环境**,克隆即可运行。

> 零基础想读懂每一行代码?请阅读配套教程 [doc/代码详解与入门教程.md](doc/代码详解与入门教程.md)。

## 系统架构

```
┌─────────────────────────────┐       ┌──────────────────────────────┐
│  浏览器(单页应用)             │       │   AI 视觉分析服务 (Python)      │
│  Vue 3 全局版 + app.js       │       │   FastAPI + YOLOv5            │
│  http://localhost:423        │       │   http://127.0.0.1:8000      │
└──────┬──────────┬───────────┘       └──────────┬─────────▲─────────┘
       │ HTTP/JSON │ WebSocket(实时告警)           │ 扫描    │ HTTP POST /detect
       ▼          ▼                              ▼         │
┌───────────────────────────────────────────┐  ┌─────────────┐ │
│                Spring Boot 管理平台 (端口 423, 默认)│  │ ai-watch    │ │
│  Controller → Service → AlarmRuleMapper   │  │ 投图文件夹   │ │
│            → Mapper → MySQL 8             │  │ (模拟摄像头) │ │
│  静态页面托管: src/main/resources/static   │  └──────┬──────┘ │
└────────────────────────────────────▲──────┘    POST /api/ai/event
                                     │ JDBC        (识别→告警→归档 processed/)
                              MySQL 8(默认 127.0.0.1:3306)
                              security_monitor 库(device、alarm 表)
```

一次告警的完整链路(两个触发入口):

1. **入口 A(手动发起)**:前端在"AI 识别记录"页填写图片地址,Spring Boot 转发给 Python AI 服务,由 YOLO 完成目标检测;
2. **入口 B(目录自动识别)**:把图片放入 `ai-service/ai-watch/` 文件夹(模拟摄像头画面),AI 服务后台线程每 2 秒扫描一次,自动识别并把结果 `POST /api/ai/event` 推送给平台,处理完的图片归档到 `ai-watch/processed/`;
3. `AlarmRuleMapper` 按规则把对象归类成业务告警(烟火异常/人员聚集/人员闯入/车辆异常/动物进入/物品异常);未归类的类名(如 traffic light、chair)属"目标识别异常",**跳过不入库不推送**,仅出现在响应的 `skipped` 清单中;
4. 告警写入 MySQL `alarm` 表,事务提交后通过 WebSocket 向所有在线页面广播;
5. 页面收到 `new-alarm` 实时弹出提示并刷新列表,安保人员可"开始处置 / 关闭告警"。

## 功能特性

| 模块 | 说明 |
| --- | --- |
| 监控大屏 | 在线设备数、待处置告警数、告警总数、AI 事件数等指标与最新告警流 |
| 设备信息 | 摄像头 / 门禁 / 烟感等设备列表,支持按区域、状态筛选 |
| AI 识别记录 | 填图片地址发起 YOLO 识别,查看识别对象与映射出的告警 |
| 目录自动识别 | `ai-watch` 文件夹投图,2~3 秒内自动识别、推送告警、归档到 processed/ |
| 实时告警中心 | 待处置 / 处置中的告警,WebSocket 自动刷新,支持处置状态流转 |
| 告警处置记录 | 已关闭告警的历史台账 |
| WebSocket 推送 | 新告警与状态变更实时广播,顶部显示通道连接状态 |
| OpenAPI 文档 | 启动后访问 `/swagger-ui/index.html` 在线调试接口 |
| Docker 一键部署(可选) | Dockerfile + docker-compose.yml(mysql:8.0 + app),详见下文 |

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
├── Dockerfile                     # 管理平台生产镜像(讲义第七章 Docker 部署)
├── docker-compose.yml             # mysql:8.0 + 管理平台 一键编排
├── .dockerignore                  # 缩小 Docker 构建上下文
├── src/main/java/top/rayawa/monitor/
│   ├── SecurityMonitorApplication.java   # 启动入口(@SpringBootApplication)
│   ├── controller/                # REST 接口层:/api/devices、/api/alarms、/api/dashboard、/api/ai/*
│   ├── service/                   # 业务层:AlarmService(告警)、AiVisionService(AI 调用)、AlarmRuleMapper(识别结果→告警规则)
│   ├── service/impl/              # 业务实现(事务 + 提交后 WebSocket 广播)
│   ├── mapper/                    # MyBatis-Plus Mapper 接口
│   ├── domain/                    # 实体类(与数据库表一一对应)
│   ├── dto/                       # 接口出入参 record(AI 请求/响应、事件推送响应等)
│   └── websocket/                 # WebSocket 配置与告警广播处理器
├── src/main/resources/
│   ├── application.yml            # 端口/数据源/AI 服务地址配置(全部可用环境变量覆盖)
│   ├── static/                    # 前端站点(Vue 全局版 + index.html + app.js + app.css)
│   └── top/rayawa/monitor/mapper/ # MyBatis XML(当前 CRUD 由 MyBatis-Plus 完成,XML 留作自定义 SQL 扩展点)
├── src/test/java/.../AlarmRuleMapperTest.java   # 告警规则映射的单元测试
├── ai-service/                    # Python AI 视觉分析服务
│   ├── server.py                  # FastAPI 服务:健康检查 / 图片检测(URL 与上传)/ ai-watch 目录自动识别
│   ├── models/                    # 模型权重(从 doc/ 复制的副本,运行时使用)
│   │   ├── yolov5s-coco.pt
│   │   └── yolov5s-dfire.pt
│   ├── yolov5-master.zip          # YOLOv5 源码包(从 doc/ 复制的副本,首次推理自动解压)
│   ├── ai-watch/                  # 投图文件夹(模拟摄像头),处理后归档到 ai-watch/processed/
│   ├── train.py                   # YOLO 模型训练脚本
│   ├── firesec.yaml               # 烟火检测数据集的 YOLO 配置
│   └── requirements.txt           # Python 依赖
├── sql/                           # 建库脚本(从 doc/ 复制的副本,本地导入与 Docker 初始化使用)
│   └── mysql8-security-monitor.sql# 建库建表 + 示例数据(device、alarm)
├── doc/                           # 老师下发的原始文件(讲义、作业模板、模型与脚本原件),运行时不得直接依赖
├── config/                        # maven-settings-macos.xml(阿里云镜像,可选)
└── target/                        # Maven 构建产物(自动生成)
```

## 快速上手

> **图省事?一键启动**——MySQL、管理平台(423)、AI 服务(8000)一条命令全部拉起(已运行的自动跳过,日志在 `logs/`):
>
> ```bash
> ./start-all.sh     # 启动
> ./stop-all.sh      # 停止
> ```

### 0. 环境要求

- macOS / Linux / Windows(Win 下将命令中的 `JAVA_HOME`、`brew` 按本机环境调整)
- JDK 17、Maven 3.8+
- MySQL 8.0(本仓库按 macOS `brew` 安装的私有实例编写)
- Python 3.10–3.12、可选 Homebrew

### 1. 初始化并启动本机 MySQL 8

```bash
brew services start mysql@8.0
mysql -h127.0.0.1 -P3306 -uroot -proot < sql/mysql8-security-monitor.sql
```

脚本会创建数据库 `security_monitor` 以及 `device`、`alarm` 两张表,并插入示例设备与告警,可重复执行(示例行 `INSERT IGNORE` 不会覆盖已有数据;改了示例等级后需清空 alarm 表再导入才会生效)。

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

### 3. 启动 AI 视觉分析服务(可选,用于真实图片识别与目录自动识别)

```bash
cd ai-service
python3.12 -m venv .venv        # 首次:创建虚拟环境
source .venv/bin/activate
pip install -r requirements.txt # 首次:安装依赖
python server.py
```

健康检查:<http://127.0.0.1:8000/health>。模型推理细节与训练方法见 [ai-service/README.md](ai-service/README.md)。

> 模型权重(`yolov5s-coco.pt`、`yolov5s-dfire.pt`)与 YOLOv5 源码包(`yolov5-master.zip`)使用 `ai-service/` 下的副本(原始文件存放于 `doc/`,首次使用需复制到 `ai-service/models/` 与 `ai-service/`);首次推理时服务会在 `ai-service/.runtime/` 自动解压源码包。

### 4. 功能验证

**联调接口(无需 Python 服务即可验证完整链路)** —— 直接提交模拟识别结果,可验证"对象映射告警 → 写入 MySQL → WebSocket 推送":

```bash
curl -X POST http://localhost:423/api/ai/event \
  -H 'Content-Type: application/json' \
  -d '{"image":"demo-fire.jpg","area":"A区西门","deviceCode":"CAM-001","objects":[{"class":"smoke","conf":0.96,"model":"demo"}]}'
# 期望响应:{"alarms":[{...烟火异常/高/待处置...}],"skipped":[]}

curl -X POST http://localhost:423/api/ai/event \
  -H 'Content-Type: application/json' \
  -d '{"image":"demo-unknown.jpg","objects":[{"class":"traffic light","conf":0.70,"model":"demo"}]}'
# 期望响应:{"alarms":[],"skipped":["traffic light"]}  目标识别异常被跳过
```

**调用真实 AI 服务分析网络图片**(需先启动第 3 步):

```bash
curl -X POST http://localhost:423/api/ai/analyze \
  -H 'Content-Type: application/json' \
  -d '{"imageUrl":"https://ultralytics.com/images/bus.jpg","area":"A区西门","deviceCode":"CAM-001"}'
```

**告警状态流转**(按状态机校验:待处置 → 处置中 → 已关闭):

```bash
curl -X POST http://localhost:423/api/alarms/1/process   # 待处置 → 处置中
curl -X POST http://localhost:423/api/alarms/1/handle    # 处置中 → 已关闭
# 对已关闭告警重复操作会返回 400:{"message":"该告警已关闭，无需重复处理"}
```

浏览器打开 <http://localhost:423>,监控大屏会实时弹出新告警提示。

### 5. 目录自动识别演示(模拟摄像头,第八章系统集成)

1. 先启动平台(第 2 步),再启动 AI 服务(第 3 步)——顺序不要反,否则 AI 服务推送时平台还没起来,图片会在约 10 秒重试耗尽后被归档;
2. 把任意 JPG/PNG 图片(如包含人、车、烟火的场景图)复制到 `ai-service/ai-watch/` 文件夹;
3. AI 服务约 2 秒内自动扫描识别,并 `POST /api/ai/event` 推送给平台;观察 AI 服务日志 `[ai-watch] xxx.jpg：新增 N 条告警,跳过 [...]`;
4. 浏览器大屏弹出新告警,告警中心出现对应记录(来源"AI视觉分析");
5. 查看 `ai-service/ai-watch/processed/`,处理完的图片已自动归档到此处。

若平台以 Docker 方式部署(端口 8080),AI 服务需设置 `AI_PLATFORM_URL=http://127.0.0.1:8080` 后再启动。

### 6. (可选)Docker 一键部署

```bash
mvn clean package -DskipTests      # 先构建 jar
docker compose up --build -d       # 构建镜像并启动 mysql + app 两个容器
```

启动后访问 <http://localhost:8080>(容器内平台端口 8080,与本地 423 互不影响)。验证步骤:

- `docker compose ps` 两个容器均为 running/healthy;
- 浏览器打开 <http://localhost:8080> 能看到管理平台,示例设备与告警已初始化(建库脚本由 mysql 容器首次启动时自动执行);
- `curl http://localhost:8080/api/dashboard` 返回统计数据;
- 重启验证数据持久化:`docker compose restart app` 后数据仍在(数据存于 `mysql-data` 数据卷);
- 重灌数据:`docker compose down -v` 删除数据卷后重新 `up`,初始化脚本会重新执行。

注意事项:

- 宿主机已有 MySQL 占用 3306 时,把 `docker-compose.yml` 中 mysql 的端口映射改为 `"3307:3306"`;
- 容器内平台调用宿主机 AI 服务用 `AI_SERVICE_URL=http://host.docker.internal:8000`(compose 已默认配置);AI 服务反向推送平台用 `AI_PLATFORM_URL=http://127.0.0.1:8080`;
- 初始化脚本只在空数据卷时执行一次,改了 SQL 后需 `docker compose down -v` 才会重新执行。

## REST 接口一览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/devices?area=&status=` | 设备列表,支持区域 / 状态过滤 |
| GET | `/api/alarms?status=&source=` | 告警列表(按事件时间倒序),支持过滤 |
| GET | `/api/dashboard` | 大屏统计(在线设备、待处置告警等) |
| POST | `/api/alarms/{id}/process` | 开始处置:待处置 → 处置中 |
| POST | `/api/alarms/{id}/handle` | 处理完成:待处置/处置中 → 已关闭 |
| POST | `/api/ai/analyze` | 提交图片地址,经 AI 服务识别后生成告警 |
| POST | `/api/ai/event` | 接收识别结果生成告警(识别服务推送 / 手工联调) |

`/api/ai/event` 请求与响应示例:

```json
// 请求(识别服务推送 ai-watch 中图片的识别结果)
{ "image": "cam-001.jpg", "area": "A区西门", "objects": [ { "class": "smoke", "conf": 0.96, "model": "fire" } ] }
// 响应(alarms 为已入库并推送的告警;skipped 为被跳过的目标识别异常类名)
{ "alarms": [ { "id": 42, "type": "烟火异常", "level": "高", "status": "待处置", "...": "..." } ], "skipped": [] }
```

WebSocket 广播消息体:

```json
{
  "event": "new-alarm | alarm-status-changed",
  "sentAt": "2026-09-07T16:00:00",
  "alarm": { "id": 1, "type": "烟火异常", "level": "高", "status": "待处置", "...": "..." }
}
```

## 配置项(环境变量覆盖)

管理平台侧:

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `SERVER_PORT` | `423` | 管理平台 HTTP 端口(本地默认 423;Docker 容器内由 compose 设为 8080) |
| `DB_HOST` / `DB_PORT` / `DB_NAME` | `127.0.0.1` / `3306` / `security_monitor` | MySQL 地址 |
| `DB_USERNAME` / `DB_PASSWORD` | `root` / `root` | MySQL 账号密码 |
| `AI_SERVICE_URL` | `http://127.0.0.1:8000` | AI 视觉分析服务地址 |

AI 服务侧:

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `WATCH_DIR` | `ai-service/ai-watch` | 目录自动识别的投图文件夹 |
| `AI_PLATFORM_URL` | `http://127.0.0.1:423` | 识别结果推送的平台地址(平台 Docker 部署时用 `http://127.0.0.1:8080`) |
| `WATCH_INTERVAL` | `2` | 目录扫描间隔(秒) |
| `WATCH_FILE_MIN_AGE` | `1` | 文件最短静置时间(秒),防止处理半写入文件 |
| `WATCH_MAX_ATTEMPTS` | `5` | 单张图片失败重试上限,超限归档并告警 |
| `YOLO_COCO_MODEL` / `YOLO_FIRE_MODEL` | `ai-service/models/` 下两个 pt 文件 | 模型权重路径 |
| `YOLO_CONFIDENCE` | `0.45` | 识别置信度阈值 |

其余见 [ai-service/README.md](ai-service/README.md)。

## 常见问题

- **告警数据不刷新 / 顶栏提示"实时通道重连中"**:确认页面经 `http://localhost:423` 访问,且后端在运行;WebSocket 断线后每 3 秒自动重连。
- **页面按钮报 404 / 时间显示差 8 小时**:前端 js 有改动而浏览器还在用缓存的旧版本,硬刷新(macOS 按 `Cmd+Shift+R`)或清缓存后重试;时间差 8 小时也可能是浏览器缓存了旧版 `formatTime`。
- **AI 识别报"AI识别服务不可用"**:第 3 步的 `python server.py` 未启动,或 `AI_SERVICE_URL` 指向错误。
- **`com.mysql.cj.jdbc.exceptions...` 连接失败**:MySQL 未启动或账号密码不对,检查第 1 步及数据库环境变量。
- **端口 423 被占用**:改用 `SERVER_PORT=8888 mvn spring-boot:run`,页面地址同步修改。
- **识别到对象但没有生成告警**:分两类——(1) 识别出的是未归类的目标(如 traffic light、chair、bench 等),属"目标识别异常"被设计性跳过,只出现在 `/api/ai/event` 响应的 `skipped` 清单中;(2) 已知目标但置信度低于阈值(`YOLO_CONFIDENCE=0.45`)会被 AI 服务过滤,可调低阈值。
- **投图到 ai-watch 后没有反应**:确认 AI 服务已启动且平台先于 AI 服务启动;检查 AI 服务日志的 `[ai-watch]` 输出;平台以 Docker 部署时检查 `AI_PLATFORM_URL` 是否指向 8080;处理失败的图片达到重试上限后会被归档到 `processed/`(日志有记录)。

## 相关文档

- [doc/代码详解与入门教程.md](doc/代码详解与入门教程.md) —— 逐文件、逐行讲解本项目代码,面向初学者
- [ai-service/README.md](ai-service/README.md) —— AI 推理服务、模型训练与目录自动识别说明
- [sql/mysql8-security-monitor.sql](sql/mysql8-security-monitor.sql) —— 建库脚本(带注释)
