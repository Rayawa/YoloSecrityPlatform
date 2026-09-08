# AI 视觉分析与模型训练

本目录对应实训讲义第五、六章。识别服务默认同时加载 `ai-service/models/yolov5s-coco.pt` 与 `ai-service/models/yolov5s-dfire.pt`，将人员、车辆、动物、物品、烟雾和火焰结果交给 Spring Boot 转换为业务告警。模型原件在 `doc/` 下（老师下发的原始文件），首次使用前复制到 `ai-service/models/`。

## macOS 启动识别服务

```bash
cd ai-service
brew install python@3.12
python3.12 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
python server.py
```

健康检查：`curl http://127.0.0.1:8000/health`

服务默认监听所有网卡（`0.0.0.0`），局域网内其他设备可通过 `http://<本机IP>:8000/health` 访问；只想本机访问时用 `HOST=127.0.0.1 python server.py` 启动。

## 推理接口

```bash
curl -X POST http://127.0.0.1:8000/detect \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://ultralytics.com/images/bus.jpg"}'
```

## 训练

按讲义建立 `firesec/images/{train,val}` 与 `firesec/labels/{train,val}` 后执行：

```bash
python train.py --data firesec.yaml --epochs 50 --imgsz 640 --batch 8 --device mps
```

仅验证训练流程时可执行：

```bash
yolo detect train model=yolov8n.pt data=coco8.yaml epochs=5 imgsz=640 batch=8 device=mps
```

Intel Mac 或 MPS 不可用时，将 `--device mps` 改为 `--device cpu`。训练输出位于 `runs/detect/train/weights/best.pt`，可通过 `YOLO_FIRE_MODEL` 环境变量让识别服务加载新权重。

推荐使用 Python 3.10 至 3.12。首次识别时，服务会把 `ai-service/yolov5-master.zip`（从 `doc/` 复制的副本）自动解压到 `ai-service/.runtime`，不需要手动下载 YOLOv5 仓库。

## 目录自动识别(第八章系统集成:模拟摄像头)

识别服务启动时会在后台常驻一个扫描线程,监听 `ai-service/ai-watch/` 文件夹:把任意 JPG/PNG 图片或 MP4/AVI/MOV 视频放进该文件夹(模拟摄像头画面/录像),约 2 秒内自动识别,并把结果 `POST /api/ai/event` 推送给管理平台;处理完成的文件自动归档到 `ai-watch/processed/`。视频会均匀抽取最多 5 帧逐帧识别、合并对象后一次性推送,标注图为帧拼接长图(`processed/annotated_原名.jpg`)。

```bash
# 先启动管理平台(端口 423),再启动识别服务
cd ai-service
source .venv/bin/activate
python server.py
# 控制台出现:[ai-watch] 监听目录:/…/ai-service/ai-watch(轮询 2.0s)

# 另开一个终端投图(找一张包含人/车/烟火的图片)
cp ~/Pictures/demo.jpg ai-service/ai-watch/
# 识别服务日志:[ai-watch] demo.jpg:新增 1 条告警,跳过 []
# 图片被归档:ls ai-service/ai-watch/processed/
```

相关环境变量:

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `WATCH_DIR` | `ai-service/ai-watch` | 投图文件夹 |
| `AI_PLATFORM_URL` | `http://127.0.0.1:423` | 识别结果推送的平台地址;平台 Docker 部署(8080)时设为 `http://127.0.0.1:8080` |
| `WATCH_INTERVAL` | `2` | 扫描间隔(秒) |
| `WATCH_FILE_MIN_AGE` | `1` | 文件最短静置时间(秒),防止处理半写入的文件 |
| `WATCH_MAX_ATTEMPTS` | `5` | 单张图片失败重试上限;连续失败超限后归档并打日志 |

注意事项:

- **启动顺序**:先启动平台再启动识别服务;否则平台未就绪期间推送失败,图片会在约 10 秒(重试 5 次 × 2 秒)后被归档;
- **目标识别异常**:识别出未归类的目标(如 traffic light、chair)不会生成告警,平台响应中的 `skipped` 清单会列出它们,识别服务日志同步打印;
- **不走 /detect**:本地扫描直接调用模型推理,不经过 `/detect` 的 URL 下载路径,SSRF 防护仅对"URL 下载"生效,本地文件无此风险。
