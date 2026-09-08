from __future__ import annotations

import base64
import logging
import os
import shutil
import threading
import time
import ipaddress
import socket
import sys
import tempfile
import zipfile
from contextlib import asynccontextmanager
from io import BytesIO
from pathlib import Path
from typing import Annotated, Any
from urllib.parse import urljoin, urlparse

import cv2
from fastapi import FastAPI, File, HTTPException, Query, UploadFile
from PIL import Image, ImageDraw
from pydantic import BaseModel, HttpUrl
import requests
import torch


RUNTIME_ROOT = Path(__file__).resolve().parent / ".runtime"
ULTRALYTICS_CONFIG = RUNTIME_ROOT / "ultralytics"
MATPLOTLIB_CONFIG = RUNTIME_ROOT / "matplotlib"
TORCH_CACHE = RUNTIME_ROOT / "torch"
for runtime_directory in (ULTRALYTICS_CONFIG, MATPLOTLIB_CONFIG, TORCH_CACHE):
    runtime_directory.mkdir(parents=True, exist_ok=True)
os.environ.setdefault("YOLO_CONFIG_DIR", str(ULTRALYTICS_CONFIG))
os.environ.setdefault("MPLCONFIGDIR", str(MATPLOTLIB_CONFIG))
os.environ.setdefault("TORCH_HOME", str(TORCH_CACHE))
# 模型与源码包使用 ai-service 目录下的副本（doc/ 仅存放老师下发的原始文件，运行时不得依赖）
AI_SERVICE_ROOT = Path(__file__).resolve().parent
COCO_MODEL_PATH = Path(os.getenv("YOLO_COCO_MODEL", AI_SERVICE_ROOT / "models" / "yolov5s-coco.pt"))
FIRE_MODEL_PATH = Path(os.getenv("YOLO_FIRE_MODEL", AI_SERVICE_ROOT / "models" / "yolov5s-dfire.pt"))
CONFIDENCE = float(os.getenv("YOLO_CONFIDENCE", "0.45"))
YOLOV5_ARCHIVE = AI_SERVICE_ROOT / "yolov5-master.zip"
YOLOV5_RUNTIME = RUNTIME_ROOT
YOLOV5_REPOSITORY = YOLOV5_RUNTIME / "yolov5-master"

# ---- 目录自动识别（模拟摄像头，第八章系统集成）配置 ----
# 把图片放入 WATCH_DIR（模拟摄像头拍摄画面），后台线程自动识别并推送给平台，
# 处理完成的图片归档到 processed/ 子目录。
WATCH_DIR = Path(os.getenv("WATCH_DIR", Path(__file__).resolve().parent / "ai-watch"))
PROCESSED_DIR = WATCH_DIR / "processed"
# 平台地址：本地平台默认 423；平台以 Docker 方式部署时改为 http://127.0.0.1:8080
AI_PLATFORM_URL = os.getenv("AI_PLATFORM_URL", "http://127.0.0.1:423").rstrip("/")
WATCH_INTERVAL = float(os.getenv("WATCH_INTERVAL", "2"))          # 轮询间隔（秒）
WATCH_FILE_MIN_AGE = float(os.getenv("WATCH_FILE_MIN_AGE", "1"))  # 文件最短静置时间，防止处理半写入文件
WATCH_MAX_ATTEMPTS = int(os.getenv("WATCH_MAX_ATTEMPTS", "5"))    # 单张图片失败重试上限

# ai-watch 目录支持的文件类型：图片 + 视频（视频抽帧识别）
IMAGE_EXTENSIONS = (".jpg", ".jpeg", ".png")
VIDEO_EXTENSIONS = (".mp4", ".avi", ".mov")

logger = logging.getLogger("ai-watch")
_stop_event = threading.Event()
_attempts: dict[str, int] = {}


@asynccontextmanager
async def lifespan(_: FastAPI):
    _stop_event.clear()
    thread = threading.Thread(target=watch_loop, name="ai-watch-scanner", daemon=True)
    thread.start()
    print(f"[ai-watch] 监听目录：{WATCH_DIR}（轮询 {WATCH_INTERVAL}s）", flush=True)
    try:
        yield
    finally:
        _stop_event.set()
        thread.join(timeout=WATCH_INTERVAL + 2)


app = FastAPI(title="智能安防 AI 视觉分析服务", version="1.0.0", lifespan=lifespan)
_models: list[tuple[str, Any]] | None = None


class DetectRequest(BaseModel):
    url: HttpUrl
    confidence: float | None = None


BLOCKED_NETWORKS = tuple(
    ipaddress.ip_network(network)
    for network in (
        "10.0.0.0/8",
        "127.0.0.0/8",
        "169.254.0.0/16",
        "172.16.0.0/12",
        "192.168.0.0/16",
        "0.0.0.0/8",
        "::1/128",
        "fc00::/7",
        "fe80::/10",
    )
)


def ensure_yolov5_repository() -> Path:
    if (YOLOV5_REPOSITORY / "hubconf.py").exists():
        return YOLOV5_REPOSITORY
    if not YOLOV5_ARCHIVE.exists():
        raise RuntimeError(f"没有找到 YOLOv5 代码包：{YOLOV5_ARCHIVE}")
    YOLOV5_RUNTIME.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(YOLOV5_ARCHIVE) as archive:
        archive.extractall(YOLOV5_RUNTIME)
    return YOLOV5_REPOSITORY


def load_models() -> list[tuple[str, Any]]:
    global _models
    if _models is not None:
        return _models

    candidates = [("coco", COCO_MODEL_PATH), ("fire", FIRE_MODEL_PATH)]
    available = [(name, path) for name, path in candidates if path.exists()]
    if not available:
        raise RuntimeError("没有找到模型文件，请配置 YOLO_COCO_MODEL 或 YOLO_FIRE_MODEL")
    repository = ensure_yolov5_repository()
    sys.path.insert(0, str(repository))
    _models = [
        (
            name,
            torch.hub.load(
                str(repository),
                "custom",
                path=str(path),
                source="local",
                force_reload=False,
                _verbose=False,
            ),
        )
        for name, path in available
    ]
    return _models


def validate_public_url(url: str) -> None:
    parsed = urlparse(url)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        raise ValueError("只允许带主机名的 HTTP 或 HTTPS 图片地址")
    addresses = socket.getaddrinfo(parsed.hostname, parsed.port or 443, type=socket.SOCK_STREAM)
    for address in {item[4][0] for item in addresses}:
        ip = ipaddress.ip_address(address)
        if ip.is_multicast or ip.is_unspecified or any(ip in network for network in BLOCKED_NETWORKS):
            raise ValueError("不允许访问本机或私有网络地址")


def download_image(url: str) -> Image.Image:
    current_url = url
    for _ in range(4):
        validate_public_url(current_url)
        response = requests.get(current_url, timeout=(5, 30), stream=True, allow_redirects=False)
        if response.is_redirect or response.is_permanent_redirect:
            location = response.headers.get("location")
            if not location:
                raise ValueError("图片地址重定向缺少 Location")
            current_url = urljoin(current_url, location)
            continue
        response.raise_for_status()
        content_length = int(response.headers.get("content-length", "0") or 0)
        if content_length > 20 * 1024 * 1024:
            raise ValueError("图片不能超过 20MB")
        payload = bytearray()
        for chunk in response.iter_content(64 * 1024):
            payload.extend(chunk)
            if len(payload) > 20 * 1024 * 1024:
                raise ValueError("图片不能超过 20MB")
        return Image.open(BytesIO(payload)).convert("RGB")
    raise ValueError("图片地址重定向次数过多")


def detect_source(source: str | Path | Image.Image, confidence: float) -> dict:
    objects: list[dict] = []
    for model_name, model in load_models():
        model.conf = confidence
        model_source = source if isinstance(source, Image.Image) else str(source)
        result = model(model_source)
        for x1, y1, x2, y2, score, cls_id in result.xyxy[0].tolist():
            objects.append({
                "class": model.names[int(cls_id)],
                "conf": round(float(score), 6),
                "model": model_name,
                "bbox": [round(float(value), 2) for value in (x1, y1, x2, y2)],
            })
    objects.sort(key=lambda item: item["conf"], reverse=True)
    return {"objects": objects}


def draw_boxes(image: Image.Image, objects: list[dict]) -> Image.Image:
    """在原图上绘制检测框与标签，返回新图（不修改入参）。"""
    annotated = image.copy()
    draw = ImageDraw.Draw(annotated)
    for obj in objects:
        x1, y1, x2, y2 = obj["bbox"]
        label = f"{obj['class']} {obj['conf']:.2f}"
        draw.rectangle([x1, y1, x2, y2], outline="#FF3B30", width=3)
        draw.text((x1, max(0, y1 - 14)), label, fill="#FF3B30")
    return annotated


def to_base64_jpeg(image: Image.Image, max_width: int = 640) -> str:
    """等比缩到 max_width 宽以内后转 JPEG base64（用于接口返回前端展示）。"""
    if image.width > max_width:
        ratio = max_width / image.width
        image = image.resize((max_width, int(image.height * ratio)))
    buffer = BytesIO()
    image.save(buffer, format="JPEG", quality=80)
    return base64.b64encode(buffer.getvalue()).decode()


def composite_images(images: list[Image.Image]) -> Image.Image:
    """把多帧标注图横向拼接成一张长图（统一高度），返回 PIL 图。"""
    height = min(image.height for image in images)
    resized = [
        image.resize((round(image.width * height / image.height), height)) for image in images
    ]
    canvas = Image.new("RGB", (sum(image.width for image in resized), height), "white")
    offset = 0
    for image in resized:
        canvas.paste(image, (offset, 0))
        offset += image.width
    return canvas


def composite_frames(images: list[Image.Image], max_width: int = 1280) -> str:
    """把多帧标注图横向拼接成一张长图，返回 JPEG base64。"""
    return to_base64_jpeg(composite_images(images), max_width=max_width)


def detect_video_frames(
    path: Path, confidence: float, max_frames: int = 5
) -> tuple[list[dict], list[Image.Image], int]:
    """解码视频、均匀抽取最多 max_frames 帧逐帧推理。

    返回 (帧结果列表, 带框帧图列表, 视频总帧数)。帧结果含 frame/objects/annotated(base64)。
    """
    capture = cv2.VideoCapture(str(path))
    if not capture.isOpened():
        raise ValueError("无法解码视频文件（支持的格式：mp4/avi/mov 等）")
    try:
        total = int(capture.get(cv2.CAP_PROP_FRAME_COUNT) or 0)
        count = min(max_frames, total) if total > 0 else max_frames
        # 均匀抽帧：总帧数已知时按比例分布；未知时从头读 count 帧
        frame_indices = [round(total * i / count) for i in range(count)] if total > 0 else list(range(count))
        frames: list[dict] = []
        annotated_images: list[Image.Image] = []
        for index in frame_indices:
            if total > 0:
                capture.set(cv2.CAP_PROP_POS_FRAMES, index)
            ok, frame = capture.read()
            if not ok:
                continue
            image = Image.fromarray(cv2.cvtColor(frame, cv2.COLOR_BGR2RGB))
            result = detect_source(image, confidence)
            annotated = draw_boxes(image, result["objects"])
            annotated_images.append(annotated)
            frames.append({
                "frame": index,
                "objects": result["objects"],
                "annotated": to_base64_jpeg(annotated),
            })
        return frames, annotated_images, total
    finally:
        capture.release()


@app.get("/health")
def health() -> dict:
    return {
        "status": "ok",
        "models": [
            {"name": "coco", "path": str(COCO_MODEL_PATH), "exists": COCO_MODEL_PATH.exists()},
            {"name": "fire", "path": str(FIRE_MODEL_PATH), "exists": FIRE_MODEL_PATH.exists()},
        ],
    }


@app.get("/detect")
def detect_get(
    url: Annotated[HttpUrl, Query()],
    confidence: Annotated[float, Query(ge=0.01, le=1.0)] = CONFIDENCE,
) -> dict:
    try:
        image = download_image(str(url))
        result = detect_source(image, confidence)
        result["annotated"] = to_base64_jpeg(draw_boxes(image, result["objects"]))
        return result
    except Exception as exc:
        raise HTTPException(status_code=422, detail=f"识别失败：{exc}") from exc


@app.post("/detect")
def detect_post(request: DetectRequest) -> dict:
    confidence = request.confidence if request.confidence is not None else CONFIDENCE
    if not 0.01 <= confidence <= 1.0:
        raise HTTPException(status_code=400, detail="confidence 必须在 0.01 到 1.0 之间")
    try:
        image = download_image(str(request.url))
        result = detect_source(image, confidence)
        result["annotated"] = to_base64_jpeg(draw_boxes(image, result["objects"]))
        return result
    except Exception as exc:
        raise HTTPException(status_code=422, detail=f"识别失败：{exc}") from exc


@app.post("/detect/upload")
async def detect_upload(
    file: Annotated[UploadFile, File()],
    confidence: Annotated[float, Query(ge=0.01, le=1.0)] = CONFIDENCE,
) -> dict:
    suffix = Path(file.filename or "upload.jpg").suffix or ".jpg"
    path: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as temporary:
            temporary.write(await file.read())
            path = Path(temporary.name)
        image = Image.open(path).convert("RGB")
        result = detect_source(image, confidence)
        result["annotated"] = to_base64_jpeg(draw_boxes(image, result["objects"]))
        return result
    except Exception as exc:
        raise HTTPException(status_code=422, detail=f"识别失败：{exc}") from exc
    finally:
        if path is not None:
            path.unlink(missing_ok=True)


@app.post("/detect/video")
async def detect_video(
    file: Annotated[UploadFile, File()],
    max_frames: Annotated[int, Query(ge=1, le=10)] = 5,
    confidence: Annotated[float, Query(ge=0.01, le=1.0)] = CONFIDENCE,
) -> dict:
    """上传视频：均匀抽取最多 max_frames 帧逐帧推理，返回带框图的帧列表。"""
    suffix = Path(file.filename or "upload.mp4").suffix or ".mp4"
    path: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as temporary:
            temporary.write(await file.read())
            path = Path(temporary.name)
        frames, annotated_images, total = detect_video_frames(path, confidence, max_frames)
        # 所有帧拼接成一张长图，供告警卡片直接展示
        composite = composite_frames(annotated_images) if annotated_images else None
        return {"totalFrames": total, "frames": frames, "composite": composite}
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(status_code=422, detail=f"视频识别失败：{exc}") from exc
    finally:
        if path is not None:
            path.unlink(missing_ok=True)


def scan_watch_directory_once() -> None:
    """扫描一轮 ai-watch 目录：识别、推送平台、归档（或计入重试）。

    支持图片（jpg/jpeg/png）与视频（mp4/avi/mov）：视频均匀抽帧推理后，
    合并所有帧对象（同类去重保留最高置信度），用帧拼接长图作为标注图推送。
    """
    WATCH_DIR.mkdir(parents=True, exist_ok=True)
    PROCESSED_DIR.mkdir(parents=True, exist_ok=True)
    names = sorted(
        name
        for name in os.listdir(WATCH_DIR)
        if name.lower().endswith(IMAGE_EXTENSIONS + VIDEO_EXTENSIONS)
        and (WATCH_DIR / name).is_file()      # 只扫顶层文件，天然跳过 processed/ 子目录
    )
    for name in names:
        source = WATCH_DIR / name
        try:
            # 拷贝未完成的文件静置不到 WATCH_FILE_MIN_AGE 秒，下轮再试
            if time.time() - source.stat().st_mtime < WATCH_FILE_MIN_AGE:
                continue
            # 本地文件直接推理，不走 /detect 的 URL 下载路径（无 SSRF 面）
            if source.suffix.lower() in VIDEO_EXTENSIONS:
                frames, annotated_images, _ = detect_video_frames(source, CONFIDENCE)
                merged: dict[str, dict] = {}
                for frame in frames:
                    for obj in frame["objects"]:
                        key = f"{obj['class']}|{obj['model']}"
                        if key not in merged or obj["conf"] > merged[key]["conf"]:
                            merged[key] = obj
                objects = sorted(merged.values(), key=lambda item: item["conf"], reverse=True)
                annotated = composite_images(annotated_images) if annotated_images else None
            else:
                image = Image.open(source).convert("RGB")
                objects = detect_source(image, CONFIDENCE)["objects"]
                annotated = draw_boxes(image, objects)
            # 推送时带上标注图，平台入库时保存并展示在告警卡片中
            response = requests.post(
                f"{AI_PLATFORM_URL}/api/ai/event",
                json={"image": name, "objects": objects, "annotated": to_base64_jpeg(annotated, max_width=1280)},
                timeout=(5, 60),
            )
            response.raise_for_status()
            body = response.json()
            print(
                f"[ai-watch] {name}：新增 {len(body.get('alarms', []))} 条告警，"
                f"跳过 {body.get('skipped', [])}",
                flush=True,
            )
            shutil.move(str(source), str(PROCESSED_DIR / name))
            # 顺带保存带检测框的标注图（视频为帧拼接长图），便于回看"模型看到了什么"
            if annotated is not None:
                annotated.save(PROCESSED_DIR / f"annotated_{Path(name).stem}.jpg")
            _attempts.pop(str(source), None)
        except Exception as exc:
            # 瞬时故障（平台未启动、网络抖动、文件损坏）重试；
            # 连续失败达到上限则归档并告警，避免图片堆积和死循环。
            _attempts[str(source)] = _attempts.get(str(source), 0) + 1
            if _attempts[str(source)] >= WATCH_MAX_ATTEMPTS:
                print(
                    f"[ai-watch] {name} 连续失败 {WATCH_MAX_ATTEMPTS} 次，归档失败文件：{exc}",
                    flush=True,
                )
                try:
                    shutil.move(str(source), str(PROCESSED_DIR / name))
                finally:
                    _attempts.pop(str(source), None)
            else:
                print(
                    f"[ai-watch] {name} 处理失败，将在下轮重试"
                    f"（{_attempts[str(source)]}/{WATCH_MAX_ATTEMPTS}）：{exc}",
                    flush=True,
                )


def watch_loop() -> None:
    """常驻轮询线程：每 WATCH_INTERVAL 秒扫描一轮。"""
    while not _stop_event.wait(WATCH_INTERVAL):
        try:
            scan_watch_directory_once()
        except Exception as exc:
            # 顶层兜底：任何异常都不允许扫描线程退出
            logger.exception("扫描异常：%s", exc)


if __name__ == "__main__":
    import uvicorn

    # 0.0.0.0 绑定所有网卡：局域网内其他设备也能访问识别服务
    uvicorn.run(app, host=os.getenv("HOST", "0.0.0.0"), port=8000)
