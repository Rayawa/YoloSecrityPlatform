# AI 视觉分析与模型训练

本目录对应实训讲义第五、六章。识别服务默认同时加载 `doc/yolov5s-coco.pt` 与 `doc/yolov5s-dfire.pt`，将人员、车辆、动物、物品、烟雾和火焰结果交给 Spring Boot 转换为业务告警。

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

推荐使用 Python 3.10 至 3.12。首次识别时，服务会把 `doc/yolov5-master.zip` 自动解压到 `ai-service/.runtime`，不需要手动下载 YOLOv5 仓库。
