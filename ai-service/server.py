from __future__ import annotations

import os
import ipaddress
import socket
import sys
import tempfile
import zipfile
from io import BytesIO
from pathlib import Path
from typing import Annotated, Any
from urllib.parse import urljoin, urlparse

from fastapi import FastAPI, File, HTTPException, Query, UploadFile
from PIL import Image
from pydantic import BaseModel, HttpUrl
import requests
import torch


ROOT = Path(__file__).resolve().parent.parent
RUNTIME_ROOT = Path(__file__).resolve().parent / ".runtime"
ULTRALYTICS_CONFIG = RUNTIME_ROOT / "ultralytics"
MATPLOTLIB_CONFIG = RUNTIME_ROOT / "matplotlib"
TORCH_CACHE = RUNTIME_ROOT / "torch"
for runtime_directory in (ULTRALYTICS_CONFIG, MATPLOTLIB_CONFIG, TORCH_CACHE):
    runtime_directory.mkdir(parents=True, exist_ok=True)
os.environ.setdefault("YOLO_CONFIG_DIR", str(ULTRALYTICS_CONFIG))
os.environ.setdefault("MPLCONFIGDIR", str(MATPLOTLIB_CONFIG))
os.environ.setdefault("TORCH_HOME", str(TORCH_CACHE))
COCO_MODEL_PATH = Path(os.getenv("YOLO_COCO_MODEL", ROOT / "doc" / "yolov5s-coco.pt"))
FIRE_MODEL_PATH = Path(os.getenv("YOLO_FIRE_MODEL", ROOT / "doc" / "yolov5s-dfire.pt"))
CONFIDENCE = float(os.getenv("YOLO_CONFIDENCE", "0.45"))
YOLOV5_ARCHIVE = ROOT / "doc" / "yolov5-master.zip"
YOLOV5_RUNTIME = RUNTIME_ROOT
YOLOV5_REPOSITORY = YOLOV5_RUNTIME / "yolov5-master"

app = FastAPI(title="智能安防 AI 视觉分析服务", version="1.0.0")
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
        return detect_source(download_image(str(url)), confidence)
    except Exception as exc:
        raise HTTPException(status_code=422, detail=f"识别失败：{exc}") from exc


@app.post("/detect")
def detect_post(request: DetectRequest) -> dict:
    confidence = request.confidence if request.confidence is not None else CONFIDENCE
    if not 0.01 <= confidence <= 1.0:
        raise HTTPException(status_code=400, detail="confidence 必须在 0.01 到 1.0 之间")
    try:
        return detect_source(download_image(str(request.url)), confidence)
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
        return detect_source(path, confidence)
    except Exception as exc:
        raise HTTPException(status_code=422, detail=f"识别失败：{exc}") from exc
    finally:
        if path is not None:
            path.unlink(missing_ok=True)


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="127.0.0.1", port=8000)
