"""管理端 API：账号初始化、登录、歌曲管理（含自动分离）、作品管理、搜歌导入。"""
from __future__ import annotations

import asyncio
import logging
import shutil
import tempfile
import urllib.request
from pathlib import Path

from fastapi import APIRouter, Body, Depends, File, Form, HTTPException, Query, UploadFile
from starlette.requests import Request as StarletteRequest

from .. import config, db, security, storage
from ..api.common import InitBody, LoginBody, PasswordBody, admin_user, current_user, is_initialized
from ..api.serializers import song_to_admin_dict, work_to_dict
from ..services import music_source, separate, setup, transcribe
from ..services.lyrics_extract import extract_embedded_lyrics

logger = logging.getLogger(__name__)

router = APIRouter(tags=["admin"])


# ---------------------------------------------------------------- 账号

@router.get("/status")
def status() -> dict:
    """系统状态：是否已完成账号初始化、分离引擎可用性。"""
    return {
        "initialized": is_initialized(),
        "app": "SelfKTV",
        "version": "1.1",
        "separation_engine": separate.get_engine(),
        "transcribe_engine": transcribe.get_engine(),
        "transcribe_error": transcribe.last_error(),
    }


@router.post("/init")
def init_account(body: InitBody) -> dict:
    """部署后的首次账号初始化，只允许执行一次。"""
    if is_initialized():
        raise HTTPException(403, "系统已完成初始化，请直接登录")
    username = body.username.strip()
    if not username:
        raise HTTPException(422, "用户名不能为空")
    password_hash, salt = security.hash_password(body.password)
    try:
        user_id = db.execute(
            "INSERT INTO users (username, password_hash, salt, is_admin) VALUES (?, ?, ?, 1)",
            (username, password_hash, salt),
        )
    except Exception as exc:  # UNIQUE 冲突等
        raise HTTPException(409, f"创建账号失败：{exc}") from exc
    return {
        "token": security.create_token(user_id),
        "user_id": user_id,
        "username": username,
    }


@router.post("/login")
def login(body: LoginBody) -> dict:
    row = db.fetch_one("SELECT * FROM users WHERE username = ?", (body.username.strip(),))
    if row is None or not security.verify_password(body.password, row["password_hash"], row["salt"]):
        raise HTTPException(401, "用户名或密码错误")
    return {
        "token": security.create_token(row["id"]),
        "user_id": row["id"],
        "username": row["username"],
    }


@router.get("/me")
def me(user: dict = Depends(current_user)) -> dict:
    return {"id": user["id"], "username": user["username"], "is_admin": bool(user["is_admin"])}


@router.post("/password")
def change_password(body: PasswordBody, user: dict = Depends(current_user)) -> dict:
    row = db.fetch_one("SELECT * FROM users WHERE id = ?", (user["id"],))
    if row is None or not security.verify_password(body.old_password, row["password_hash"], row["salt"]):
        raise HTTPException(403, "原密码错误")
    password_hash, salt = security.hash_password(body.new_password)
    db.execute("UPDATE users SET password_hash = ?, salt = ? WHERE id = ?", (password_hash, salt, user["id"]))
    return {"ok": True}


# ---------------------------------------------------------------- 分离组件准备

@router.get("/setup/status")
def setup_status(user: dict = Depends(admin_user)) -> dict:
    """分离组件准备进度（管理页初始化向导展示进度条用）。"""
    return setup.status()


@router.post("/setup/install")
def setup_install(user: dict = Depends(admin_user)) -> dict:
    """开始安装分离引擎 + 下载模型（幂等，后台执行）。"""
    return setup.start_install()


# ---------------------------------------------------------------- 元数据探测

@router.post("/probe")
async def probe_audio(
    file: UploadFile = File(..., description="音频文件（仅读取标签，不保存）"),
    user: dict = Depends(admin_user),
) -> dict:
    """读取音频元数据：自动填充歌曲名 / 歌手 / 时长。"""
    tmp_dir = config.UPLOAD_DIR / ".probe_tmp"
    tmp_dir.mkdir(parents=True, exist_ok=True)
    ext = storage.safe_ext(file.filename, config.AUDIO_EXTS, ".mp3")
    tmp = tmp_dir / f"probe{ext}"
    try:
        await storage.save_upload(file, tmp)
        title, artist = "", ""
        try:
            import mutagen

            media = mutagen.File(str(tmp), easy=True)
            if media is not None and media.tags is not None:
                def first(key: str) -> str:
                    val = media.tags.get(key)
                    if isinstance(val, list):
                        return str(val[0]) if val else ""
                    return str(val or "")

                title = first("title")
                artist = first("artist") or first("albumartist")
        except Exception:
            pass
        # 兜底：直接读 ID3 帧（纯标签文件 / 非标准文件）
        if not title and not artist:
            try:
                from mutagen.id3 import ID3

                tags = ID3(str(tmp))
                t = tags.get("TIT2")
                a = tags.get("TPE1")
                title = str(t) if t else ""
                artist = str(a) if a else ""
            except Exception:
                pass
        duration = storage.probe_duration(tmp)
        return {
            "title": title.strip(),
            "artist": artist.strip(),
            "duration": duration,
            "filename": file.filename or "",
        }
    finally:
        tmp.unlink(missing_ok=True)


# ---------------------------------------------------------------- 歌曲

@router.get("/songs")
def list_songs(q: str = Query("", max_length=64), user: dict = Depends(admin_user)) -> dict:
    if q.strip():
        like = f"%{q.strip()}%"
        rows = db.fetch_all(
            "SELECT * FROM songs WHERE title LIKE ? OR artist LIKE ? ORDER BY updated_at DESC",
            (like, like),
        )
    else:
        rows = db.fetch_all("SELECT * FROM songs ORDER BY updated_at DESC")
    return {
        "total": len(rows),
        "songs": [song_to_admin_dict(r) for r in rows],
        "separation_engine": separate.get_engine(),
    }


async def _save_song_files(
    song_id: int,
    original: UploadFile | None,
    vocal: UploadFile | None,
    accomp: UploadFile | None,
    lyric: UploadFile | None,
    cover: UploadFile | None,
    replace: bool,
) -> None:
    """保存歌曲附属文件；replace=False 时仅补充缺失文件。"""
    song_dir = config.SONG_UPLOAD_DIR / str(song_id)
    row = db.fetch_one("SELECT * FROM songs WHERE id = ?", (song_id,))
    updates: dict[str, str] = {}

    async def save(upload: UploadFile, field: str, allowed: set[str], default: str, fixed_name: str | None = None):
        if upload is None or (not replace and row[field]):
            return
        ext = storage.safe_ext(upload.filename, allowed, default)
        name = fixed_name or f"{field.replace('_file', '')}{ext}"
        await storage.save_upload(upload, song_dir / name)
        updates[field] = name

    await save(original, "original_file", config.AUDIO_EXTS, ".mp3")
    await save(vocal, "vocal_file", config.AUDIO_EXTS, ".mp3")
    await save(accomp, "accomp_file", config.AUDIO_EXTS, ".mp3")
    await save(lyric, "lyric_file", config.LYRIC_EXTS, ".lrc", fixed_name="lyric.lrc")
    await save(cover, "cover_file", config.COVER_EXTS, ".jpg")

    for field, value in updates.items():
        db.execute(f"UPDATE songs SET {field} = ?, updated_at = datetime('now') WHERE id = ?", (value, song_id))


def _auto_lyrics(song_id: int) -> bool:
    """没有上传歌词时，尝试从音频元数据提取内嵌歌词。"""
    row = db.fetch_one("SELECT * FROM songs WHERE id = ?", (song_id,))
    if row is None or row["lyric_file"]:
        return False
    song_dir = config.SONG_UPLOAD_DIR / str(song_id)
    for field in ("original_file", "vocal_file", "accomp_file"):
        name = row[field]
        if not name:
            continue
        extracted = extract_embedded_lyrics(song_dir / name)
        if extracted:
            text, _is_lrc = extracted
            (song_dir / "lyric.lrc").write_text(text, encoding="utf-8")
            db.execute("UPDATE songs SET lyric_file = 'lyric.lrc' WHERE id = ?", (song_id,))
            return True
    return False


def _schedule_separation(song_id: int) -> None:
    """有原曲且缺少任一分轨 → 自动进入分离队列。"""
    row = db.fetch_one("SELECT * FROM songs WHERE id = ?", (song_id,))
    if row is None or not row["original_file"]:
        return
    if row["vocal_file"] and row["accomp_file"]:
        db.execute("UPDATE songs SET separate_status = 'done', separate_error = '' WHERE id = ?", (song_id,))
        return
    separate.enqueue(song_id)


def _probe_duration(song_id: int) -> float:
    row = db.fetch_one("SELECT * FROM songs WHERE id = ?", (song_id,))
    if row is None:
        return 0.0
    song_dir = config.SONG_UPLOAD_DIR / str(song_id)
    for field in ("vocal_file", "accomp_file", "original_file"):
        if row[field]:
            duration = storage.probe_duration(song_dir / row[field])
            if duration:
                return duration
    return 0.0


@router.post("/songs")
async def create_song(
    title: str = Form(..., min_length=1, max_length=128),
    artist: str = Form("", max_length=128),
    duration: float = Form(0),
    kind: str = Form("auto", pattern="^(auto|original|vocal|accomp)$"),
    audio: UploadFile | None = File(None, description="主音频文件（含义由 kind 决定）"),
    vocal: UploadFile | None = File(None, description="原唱音轨（可选，手动上传）"),
    accomp: UploadFile | None = File(None, description="伴奏音轨（可选，手动上传）"),
    lyric: UploadFile | None = File(None, description="LRC 歌词（可选，默认读取音频内嵌歌词）"),
    cover: UploadFile | None = File(None, description="封面图（可选）"),
    user: dict = Depends(admin_user),
) -> dict:
    """上传歌曲。

    kind 决定主音频 audio 的用途：
      - auto/original：原曲，自动分离出人声/伴奏
      - vocal：直接作为人声音轨（不分离）
      - accomp：直接作为伴奏音轨（不分离）
    """
    orig = audio if kind in ("auto", "original") else None
    voc = vocal or (audio if kind == "vocal" else None)
    acc = accomp or (audio if kind == "accomp" else None)
    if orig is None and voc is None and acc is None:
        raise HTTPException(422, "请上传音频文件（原曲自动分离，或手动指定人声/伴奏）")
    song_id = db.execute(
        "INSERT INTO songs (title, artist, duration) VALUES (?, ?, ?)",
        (title.strip(), artist.strip(), duration),
    )
    await _save_song_files(song_id, orig, voc, acc, lyric, cover, replace=True)
    _auto_lyrics(song_id)
    if not duration:
        db.execute("UPDATE songs SET duration = ? WHERE id = ?", (_probe_duration(song_id), song_id))
    row = db.fetch_one("SELECT * FROM songs WHERE id = ?", (song_id,))
    if row["original_file"] and not (row["vocal_file"] and row["accomp_file"]):
        separate.enqueue(song_id)          # 自动分离
    elif row["vocal_file"]:
        separate.enqueue_pitch(song_id)    # 手动人声：只生成参考音高
    row = db.fetch_one("SELECT * FROM songs WHERE id = ?", (song_id,))
    return song_to_admin_dict(row)


@router.put("/songs/{song_id}")
async def update_song(
    song_id: int,
    title: str | None = Form(None, max_length=128),
    artist: str | None = Form(None, max_length=128),
    duration: float | None = Form(None),
    kind: str = Form("auto", pattern="^(auto|original|vocal|accomp)$"),
    audio: UploadFile | None = File(None),
    vocal: UploadFile | None = File(None),
    accomp: UploadFile | None = File(None),
    lyric: UploadFile | None = File(None),
    cover: UploadFile | None = File(None),
    user: dict = Depends(admin_user),
) -> dict:
    row = db.fetch_one("SELECT * FROM songs WHERE id = ?", (song_id,))
    if row is None:
        raise HTTPException(404, "歌曲不存在")
    if title is not None and title.strip():
        db.execute("UPDATE songs SET title = ? WHERE id = ?", (title.strip(), song_id))
    if artist is not None:
        db.execute("UPDATE songs SET artist = ? WHERE id = ?", (artist.strip(), song_id))
    if duration is not None:
        db.execute("UPDATE songs SET duration = ? WHERE id = ?", (duration, song_id))
    orig = audio if kind in ("auto", "original") else None
    voc = vocal or (audio if kind == "vocal" else None)
    acc = accomp or (audio if kind == "accomp" else None)
    await _save_song_files(song_id, orig, voc, acc, lyric, cover, replace=True)
    if lyric is None:
        _auto_lyrics(song_id)
    row = db.fetch_one("SELECT * FROM songs WHERE id = ?", (song_id,))
    if orig is not None and not (row["vocal_file"] and row["accomp_file"]):
        separate.enqueue(song_id)
    elif voc is not None:
        separate.enqueue_pitch(song_id)
    else:
        db.execute("UPDATE songs SET updated_at = datetime('now') WHERE id = ?", (song_id,))
    row = db.fetch_one("SELECT * FROM songs WHERE id = ?", (song_id,))
    return song_to_admin_dict(row)


@router.post("/songs/{song_id}/separate")
def separate_song(song_id: int, user: dict = Depends(admin_user)) -> dict:
    """手动触发：有原曲→重新分离；只有人声→重新生成参考音高。"""
    row = db.fetch_one("SELECT * FROM songs WHERE id = ?", (song_id,))
    if row is None:
        raise HTTPException(404, "歌曲不存在")
    if row["original_file"]:
        if separate.get_engine() is None:
            raise HTTPException(503, "未安装分离引擎：pip install -r requirements-separation.txt")
        separate.enqueue(song_id)
        return {"ok": True, "engine": separate.get_engine(), "status": "queued"}
    if row["vocal_file"]:
        separate.enqueue_pitch(song_id)
        return {"ok": True, "engine": separate.get_engine(), "status": "queued_pitch"}
    raise HTTPException(422, "该歌曲没有原曲/人声文件，无法生成参考旋律")


@router.post("/songs/{song_id}/transcribe", status_code=202)
def transcribe_song(song_id: int, user: dict = Depends(admin_user)) -> dict:
    """异步转录：人声 → 参考旋律（音符事件）。返回 jobId，用 /jobs/{id} 轮询。"""
    row = db.fetch_one("SELECT * FROM songs WHERE id = ?", (song_id,))
    if row is None:
        raise HTTPException(404, "歌曲不存在")
    if not row["vocal_file"]:
        raise HTTPException(422, "需先分离出人声（参考旋律只以纯人声为输入）")
    song_dir = config.SONG_UPLOAD_DIR / str(song_id)
    vocal = song_dir / row["vocal_file"]
    accomp = song_dir / row["accomp_file"] if row["accomp_file"] else None

    def job():
        data = transcribe.transcribe_file(vocal, song_dir, song_id=str(song_id), accomp_path=accomp)
        return data

    job_id = transcribe.submit_job(song_id, job)
    return {"jobId": job_id, "status": "queued"}


@router.get("/jobs/{job_id}")
def job_status(job_id: str, user: dict = Depends(admin_user)) -> dict:
    """转录任务状态/结果：queued | running | done | error。"""
    job = transcribe.get_job(job_id)
    if job is None:
        raise HTTPException(404, "任务不存在")
    return job


@router.delete("/songs/{song_id}")
def delete_song(song_id: int, user: dict = Depends(admin_user)) -> dict:
    row = db.fetch_one("SELECT * FROM songs WHERE id = ?", (song_id,))
    if row is None:
        raise HTTPException(404, "歌曲不存在")
    storage.remove_dir(config.SONG_UPLOAD_DIR / str(song_id))
    db.execute("DELETE FROM songs WHERE id = ?", (song_id,))
    # 听歌模块关联清理：收藏 / 歌单 / 播放队列
    db.execute("DELETE FROM favorites WHERE song_id = ?", (song_id,))
    db.execute("DELETE FROM playlist_songs WHERE song_id = ?", (song_id,))
    db.execute("DELETE FROM play_queue WHERE song_id = ?", (song_id,))
    return {"ok": True}


# ---------------------------------------------------------------- 作品

@router.get("/works")
def list_works(user: dict = Depends(admin_user)) -> dict:
    rows = db.fetch_all(
        "SELECT w.*, u.username FROM works w LEFT JOIN users u ON u.id = w.user_id "
        "ORDER BY w.created_at DESC"
    )
    return {"total": len(rows), "works": [work_to_dict(r) for r in rows]}


@router.delete("/works/{work_id}")
def delete_work(work_id: int, user: dict = Depends(admin_user)) -> dict:
    row = db.fetch_one("SELECT * FROM works WHERE id = ?", (work_id,))
    if row is None:
        raise HTTPException(404, "作品不存在")
    if row["audio_file"]:
        (config.WORK_UPLOAD_DIR / row["audio_file"]).unlink(missing_ok=True)
    # 混音导出缓存一并清理，避免磁盘泄漏
    for mix_file in config.WORK_UPLOAD_DIR.glob(f"{work_id}_mix_*.wav"):
        mix_file.unlink(missing_ok=True)
    db.execute("DELETE FROM works WHERE id = ?", (work_id,))
    return {"ok": True}


@router.get("/works/{work_id}/stream")
def stream_work(work_id: int, request: StarletteRequest, user: dict = Depends(admin_user)):
    row = db.fetch_one("SELECT * FROM works WHERE id = ?", (work_id,))
    if row is None or not row["audio_file"]:
        raise HTTPException(404, "作品不存在")
    path = config.WORK_UPLOAD_DIR / row["audio_file"]
    return storage.ranged_file_response(request, path, storage.audio_content_type(path))


# ------------------------------------------------------------- 搜歌导入（GD音乐台）

IMPORTS: dict[str, dict] = {}
_IMPORT_SEQ = 0
_IMPORT_LOCK = asyncio.Lock()  # 导入串行执行：一首下载完再下下一首，稳过限频
_PREVIEW_CACHE: dict[str, tuple[float, dict]] = {}  # 试听缓存：(ts, data)，避免重复打接口
_PREVIEW_TTL = 1800.0


@router.get("/music/search")
def music_search(
    keyword: str,
    source: str = Query("netease"),
    page: int = Query(1, ge=1),
    user: dict = Depends(admin_user),
) -> dict:
    """搜索 GD音乐台曲库。数据来源：GD音乐台(music.gdstudio.xyz)，仅供个人学习使用。"""
    try:
        items = music_source.search(keyword, source=source, count=20, pages=page)
    except music_source.MusicSourceError as e:
        raise HTTPException(502, str(e))
    return {
        "items": items,
        "source": source,
        "page": page,
        "sources": music_source.AVAILABLE_SOURCES,
        "br_choices": music_source.BR_CHOICES,
        "attribution": "数据来源：GD音乐台(music.gdstudio.xyz) · 仅供个人学习使用",
    }


@router.post("/music/import")
async def music_import(payload: dict = Body(...), user: dict = Depends(admin_user)) -> dict:
    """一键导入：下载音频/歌词/封面 → 建歌 → 自动分离 → 自动转录。"""
    global _IMPORT_SEQ
    track_id = str(payload.get("id", "")).strip()
    if not track_id:
        raise HTTPException(422, "缺少曲目 ID")
    artist = payload.get("artist") or []
    if isinstance(artist, list):
        artist = "、".join(str(a) for a in artist)
    try:
        br = int(payload.get("br") or 320)
    except (TypeError, ValueError):
        br = 320
    _IMPORT_SEQ += 1
    job = {
        "id": f"imp_{_IMPORT_SEQ:04d}",
        "name": f"{payload.get('name', '')} - {artist}".strip(" -"),
        "status": "running",
        "step": "排队中",
        "steps": [
            {"label": "歌曲", "state": "wait"},
            {"label": "歌词", "state": "wait"},
            {"label": "封面", "state": "wait"},
            {"label": "入库", "state": "wait"},
        ],
        "song_id": None,
        "error": None,
    }
    IMPORTS[job["id"]] = job
    args = {
        "id": track_id,
        "source": str(payload.get("source") or "netease"),
        "br": br if br in music_source.BR_CHOICES else 320,
        "name": str(payload.get("name", ""))[:128],
        "artist": str(artist)[:128],
        "lyric_id": str(payload.get("lyric_id") or track_id),
        "pic_id": str(payload.get("pic_id") or ""),
    }
    asyncio.create_task(_run_music_import(job, args, user))
    return {"job_id": job["id"]}


@router.get("/music/imports")
def music_imports(user: dict = Depends(admin_user)) -> dict:
    return {"jobs": sorted(IMPORTS.values(), key=lambda j: j["id"], reverse=True)[:20]}


@router.get("/music/preview")
def music_preview(
    source: str,
    id: str,
    br: int = 320,
    user: dict = Depends(admin_user),
) -> dict:
    """试听：返回音频直链 + LRC 歌词（短 TTL 缓存）。"""
    key = f"{source}:{id}:{br}"
    import time as _time

    now = _time.time()
    hit = _PREVIEW_CACHE.get(key)
    if hit and now - hit[0] < _PREVIEW_TTL:
        return hit[1]
    try:
        url_info = music_source.song_url(source, id, br)
    except music_source.MusicSourceError as e:
        raise HTTPException(502, f"获取试听地址失败：{e}")
    lyric_text = ""
    try:
        orig, trans = music_source.lyric(source, id)
        lyric_text = music_source.merge_lyric(orig, trans)
    except Exception:  # noqa: BLE001 歌词失败不影响试听
        pass
    data = {
        "url": url_info.get("url"),
        "br": url_info.get("br"),
        "size": url_info.get("size"),
        "lyric": lyric_text,
    }
    _PREVIEW_CACHE[key] = (now, data)
    return data


@router.get("/music/pic")
def music_pic(
    request: StarletteRequest,
    source: str,
    pic_id: str,
    size: int = Query(500),
    user: dict = Depends(admin_user),
):
    """封面代理（磁盘缓存，只在首次解析时打音乐源一次）。"""
    from fastapi.responses import FileResponse

    cache_dir = config.DATA_DIR / "cache" / "pics"
    path = cache_dir / f"{source}_{pic_id}_{size}.jpg"
    if not path.is_file():
        try:
            url = music_source.pic_url(source, pic_id, size)
        except music_source.MusicSourceError as e:
            raise HTTPException(502, f"封面解析失败：{e}")
        if not url:
            raise HTTPException(404, "无封面")
        try:
            img = _download_bytes(str(url), timeout=30.0)
        except Exception as e:  # noqa: BLE001
            raise HTTPException(502, f"封面下载失败：{e}")
        cache_dir.mkdir(parents=True, exist_ok=True)
        tmp = path.with_suffix(".tmp")
        tmp.write_bytes(img)
        tmp.replace(path)
    return FileResponse(
        path,
        media_type="image/jpeg",
        headers={"Cache-Control": "public, max-age=604800"},
    )


def _download_bytes(url: str, timeout: float = 120.0) -> bytes:
    if url.startswith("mock://"):  # 测试 mock 通道
        if "pic" in url:
            from PIL import Image

            img = Image.new("RGB", (300, 300), (200, 60, 60))
            buf = __import__("io").BytesIO()
            img.save(buf, "JPEG")
            return buf.getvalue()
        import numpy as np
        import wave

        t = np.arange(int(44100 * 2)) / 44100
        y = (0.5 * np.sin(2 * np.pi * 440 * t) * 32767).astype("<i2")
        buf = __import__("io").BytesIO()
        with wave.open(buf, "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(44100)
            w.writeframes(y.tobytes())
        return buf.getvalue()
    req = urllib.request.Request(url, headers={"User-Agent": music_source.UA})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.read()


def _make_upload(path: Path, filename: str, content_type: str) -> UploadFile:
    return UploadFile(
        file=path.open("rb"),
        filename=filename,
        headers={"content-type": content_type},
    )


async def _run_music_import(job: dict, args: dict, user: dict) -> None:
    """搜歌导入流水线：下载音频/歌词/封面 → 创建歌曲（自动分离→自动转录）。"""
    async with _IMPORT_LOCK:  # 串行：下载完成后建歌，再进下一首
        await _run_music_import_locked(job, args, user)


async def _run_music_import_locked(job: dict, args: dict, user: dict) -> None:
    tmp_dir = Path(tempfile.mkdtemp(prefix="selfktv_import_"))

    _step_icon = {"run": "…", "done": "✓", "fail": "✗"}

    def step(label: str, state: str) -> None:
        for s in job["steps"]:
            if s["label"] == label:
                s["state"] = state
        job["step"] = " · ".join(s["label"] + _step_icon.get(s["state"], "") for s in job["steps"])

    try:
        step("歌曲", "run")
        info = await asyncio.to_thread(music_source.song_url, args["source"], args["id"], args["br"])

        job["step"] = "下载歌曲…"
        audio_bytes = await asyncio.to_thread(_download_bytes, str(info["url"]))
        suffix = ".flac" if args["br"] >= 740 else ".mp3"
        audio_path = tmp_dir / f"audio{suffix}"
        audio_path.write_bytes(audio_bytes)
        step("歌曲", "done")

        step("歌词", "run")
        lyric_up = None
        try:
            orig, trans = await asyncio.to_thread(music_source.lyric, args["source"], args["lyric_id"])
            text = music_source.merge_lyric(orig, trans)
            if text.strip():
                lrc_path = tmp_dir / "lyric.lrc"
                lrc_path.write_text(text, encoding="utf-8")
                lyric_up = _make_upload(lrc_path, "lyric.lrc", "text/plain")
                step("歌词", "done")
            else:
                step("歌词", "fail")
        except Exception:  # noqa: BLE001 歌词缺失不阻断导入
            step("歌词", "fail")
            logger.info("歌曲 %s 无歌词或下载失败，跳过", args["name"])

        step("封面", "run")
        cover_up = None
        try:
            pic = await asyncio.to_thread(music_source.pic_url, args["source"], args["pic_id"])
            if pic:
                img_bytes = await asyncio.to_thread(_download_bytes, str(pic))
                img_path = tmp_dir / "cover.jpg"
                img_path.write_bytes(img_bytes)
                cover_up = _make_upload(img_path, "cover.jpg", "image/jpeg")
                step("封面", "done")
            else:
                step("封面", "fail")
        except Exception:  # noqa: BLE001 封面缺失不阻断导入
            step("封面", "fail")
            logger.info("歌曲 %s 封面下载失败，跳过", args["name"])

        step("入库", "run")
        row = await create_song(
            title=args["name"] or "未命名",
            artist=args["artist"],
            duration=0,
            kind="original",
            audio=_make_upload(audio_path, f"audio{suffix}", "application/octet-stream"),
            vocal=None,
            accomp=None,
            lyric=lyric_up,
            cover=cover_up,
            user=user,
        )
        job["song_id"] = row["id"]
        step("入库", "done")
        job["status"] = "done"
        job["step"] = "完成"
        logger.info("搜歌导入完成: %s -> 歌曲 #%s", job["name"], row["id"])
    except music_source.MusicSourceError as e:
        job["status"] = "error"
        job["step"] = "失败"
        job["error"] = str(e)
    except Exception as e:  # noqa: BLE001
        job["status"] = "error"
        job["step"] = "失败"
        job["error"] = str(e)
        logger.error("搜歌导入失败 %s: %s", job["name"], e, exc_info=True)
    finally:
        shutil.rmtree(tmp_dir, ignore_errors=True)
