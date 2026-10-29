"""文件保存与 HTTP Range 流式传输。"""
from __future__ import annotations

import re
import shutil
from pathlib import Path
from typing import AsyncIterator

from fastapi import HTTPException, UploadFile
from fastapi.responses import StreamingResponse
from starlette.requests import Request

from . import config


def safe_ext(filename: str | None, allowed: set[str], default: str) -> str:
    """校验并归一化文件后缀，非法后缀一律降级为 default。"""
    ext = Path(filename or "").suffix.lower()
    return ext if ext in allowed else default


async def save_upload(upload: UploadFile, dest: Path, max_mb: int | None = None) -> Path:
    """流式保存上传文件，超出大小限制直接 413。"""
    limit = (max_mb or config.MAX_UPLOAD_MB) * 1024 * 1024
    dest.parent.mkdir(parents=True, exist_ok=True)
    size = 0
    with dest.open("wb") as f:
        while chunk := await upload.read(config.CHUNK_SIZE):
            size += len(chunk)
            if size > limit:
                f.close()
                dest.unlink(missing_ok=True)
                raise HTTPException(413, f"文件超过大小上限 {limit // 1024 // 1024}MB")
            f.write(chunk)
    return dest


def remove_dir(path: Path) -> None:
    shutil.rmtree(path, ignore_errors=True)


_RANGE_RE = re.compile(r"bytes=(\d*)-(\d*)$")


def ranged_file_response(request: Request, path: Path, content_type: str | None = None) -> StreamingResponse:
    """支持 HTTP Range 的文件流式响应（音频拖动进度条必需）。"""
    if not path.is_file():
        raise HTTPException(404, "文件不存在")
    file_size = path.stat().st_size
    start, end = 0, file_size - 1
    status = 200

    range_header = request.headers.get("range")
    if range_header:
        m = _RANGE_RE.match(range_header.strip())
        if not m:
            raise HTTPException(416, "无法解析 Range 请求", headers={"Content-Range": f"bytes */{file_size}"})
        s, e = m.group(1), m.group(2)
        if s == "":  # bytes=-N：最后 N 字节
            length = int(e or 0)
            start = max(file_size - length, 0)
        else:
            start = int(s)
            end = int(e) if e else file_size - 1
        end = min(end, file_size - 1)
        if start > end or start >= file_size:
            raise HTTPException(416, "Range 越界", headers={"Content-Range": f"bytes */{file_size}"})
        status = 206

    length = end - start + 1

    async def iterator() -> AsyncIterator[bytes]:
        with path.open("rb") as f:
            f.seek(start)
            remaining = length
            while remaining > 0:
                chunk = f.read(min(config.CHUNK_SIZE, remaining))
                if not chunk:
                    break
                remaining -= len(chunk)
                yield chunk

    headers = {
        "Content-Length": str(length),
        "Accept-Ranges": "bytes",
        "Cache-Control": "no-cache",
    }
    if status == 206:
        headers["Content-Range"] = f"bytes {start}-{end}/{file_size}"
    return StreamingResponse(iterator(), status_code=status, headers=headers, media_type=content_type)


def audio_content_type(path: Path) -> str:
    return {
        ".mp3": "audio/mpeg",
        ".wav": "audio/wav",
        ".m4a": "audio/mp4",
        ".aac": "audio/aac",
        ".flac": "audio/flac",
        ".ogg": "audio/ogg",
        ".opus": "audio/opus",
    }.get(path.suffix.lower(), "application/octet-stream")


def image_content_type(path: Path) -> str:
    return {
        ".jpg": "image/jpeg",
        ".jpeg": "image/jpeg",
        ".png": "image/png",
        ".webp": "image/webp",
    }.get(path.suffix.lower(), "application/octet-stream")


def probe_duration(path: Path) -> float:
    """用 mutagen 读取音频时长（秒），失败返回 0。"""
    try:
        from mutagen import File as MutagenFile

        media = MutagenFile(str(path), easy=True)
        if media is not None and media.info is not None:
            return round(float(media.info.length or 0), 2)
    except Exception:
        pass
    return 0.0
