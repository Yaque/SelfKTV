"""客户端（Android App）API。"""
from __future__ import annotations

import json

from fastapi import APIRouter, Depends, File, Form, HTTPException, Query, Request, UploadFile
from fastapi.responses import PlainTextResponse
from starlette.requests import Request as StarletteRequest

from .. import config, db, security, storage
from ..api.common import LoginBody, current_user, is_initialized
from ..api.serializers import song_to_dict, work_to_dict

router = APIRouter(tags=["mobile"])


# ---------------------------------------------------------------- 基础

@router.get("/ping")
def ping() -> dict:
    return {"ok": True, "app": "SelfKTV", "initialized": is_initialized()}


@router.post("/auth/login")
def login(body: LoginBody) -> dict:
    row = db.fetch_one("SELECT * FROM users WHERE username = ?", (body.username,))
    if row is None or not security.verify_password(body.password, row["password_hash"], row["salt"]):
        raise HTTPException(401, "用户名或密码错误")
    return {
        "token": security.create_token(row["id"]),
        "user_id": row["id"],
        "username": row["username"],
    }


# ---------------------------------------------------------------- 歌曲

@router.get("/songs")
def list_songs(q: str = Query("", max_length=64), user: dict = Depends(current_user)) -> dict:
    if q.strip():
        like = f"%{q.strip()}%"
        rows = db.fetch_all(
            "SELECT * FROM songs WHERE title LIKE ? OR artist LIKE ? ORDER BY updated_at DESC",
            (like, like),
        )
    else:
        rows = db.fetch_all("SELECT * FROM songs ORDER BY updated_at DESC")
    songs = [song_to_dict(r) for r in rows]
    return {"total": len(songs), "songs": songs}


@router.get("/songs/{song_id}")
def song_detail(song_id: int, user: dict = Depends(current_user)) -> dict:
    row = db.fetch_one("SELECT * FROM songs WHERE id = ?", (song_id,))
    if row is None:
        raise HTTPException(404, "歌曲不存在")
    return song_to_dict(row)


def _song_file(song_id: int, field: str):
    row = db.fetch_one("SELECT * FROM songs WHERE id = ?", (song_id,))
    if row is None:
        raise HTTPException(404, "歌曲不存在")
    name = row[field]
    if not name:
        raise HTTPException(404, "该资源不存在")
    return row, config.SONG_UPLOAD_DIR / str(song_id) / name


@router.get("/songs/{song_id}/stream")
def stream_song(
    song_id: int,
    request: StarletteRequest,
    track: str = Query("auto", pattern="^(auto|vocal|accomp|original)$"),
    user: dict = Depends(current_user),
):
    row = db.fetch_one("SELECT * FROM songs WHERE id = ?", (song_id,))
    if row is None:
        raise HTTPException(404, "歌曲不存在")
    if track == "auto":
        # 优先伴奏，其次原唱，最后原曲（分离中时仍可先听起来）
        field = next(
            (f for f in ("accomp_file", "vocal_file", "original_file") if row[f]),
            None,
        )
    else:
        field = {
            "vocal": "vocal_file",
            "accomp": "accomp_file",
            "original": "original_file",
        }[track]
    name = row[field] if field else None
    if not name:
        raise HTTPException(404, "该音轨不存在（伴奏可能还在分离中）")
    path = config.SONG_UPLOAD_DIR / str(song_id) / name
    return storage.ranged_file_response(request, path, storage.audio_content_type(path))


@router.get("/songs/{song_id}/cover")
def song_cover(song_id: int, request: StarletteRequest, user: dict = Depends(current_user)):
    _, path = _song_file(song_id, "cover_file")
    return storage.ranged_file_response(request, path, storage.image_content_type(path))


@router.get("/songs/{song_id}/lyrics", response_class=PlainTextResponse)
def song_lyrics(song_id: int, user: dict = Depends(current_user)) -> str:
    _, path = _song_file(song_id, "lyric_file")
    return path.read_text(encoding="utf-8", errors="replace")


@router.get("/songs/{song_id}/pitch")
def song_pitch(song_id: int, user: dict = Depends(current_user)) -> dict:
    """参考音高轮廓（旧版兼容）。"""
    row = db.fetch_one("SELECT * FROM songs WHERE id = ?", (song_id,))
    if row is None:
        raise HTTPException(404, "歌曲不存在")
    path = config.SONG_UPLOAD_DIR / str(song_id) / "pitch.json"
    if not path.is_file():
        raise HTTPException(404, "该歌曲暂无音高数据（等待分离完成或暂不支持）")
    return json.loads(path.read_text(encoding="utf-8"))


@router.get("/songs/{song_id}/notes")
def song_notes(song_id: int, user: dict = Depends(current_user)) -> dict:
    """参考旋律（音符事件序列）：{songId, version, notes:[{t,d,m,c}]}。

    客户端按 version 做本地缓存；重新转录后 version 提升，拉取覆盖。
    """
    row = db.fetch_one("SELECT * FROM songs WHERE id = ?", (song_id,))
    if row is None:
        raise HTTPException(404, "歌曲不存在")
    path = config.SONG_UPLOAD_DIR / str(song_id) / "notes.json"
    if not path.is_file():
        raise HTTPException(404, "该歌曲暂无参考旋律（等待转录完成）")
    return json.loads(path.read_text(encoding="utf-8"))


# ---------------------------------------------------------------- 作品（录音）

@router.post("/works")
async def upload_work(
    request: Request,
    file: UploadFile = File(..., description="录音文件(wav/m4a/mp3)"),
    song_id: int | None = Form(None),
    mode: str = Form("accomp", pattern="^(mix|vocal|accomp|smart)$"),
    title: str = Form("", max_length=128),
    duration: float = Form(0),
    user: dict = Depends(current_user),
) -> dict:
    song_title = ""
    if song_id is not None:
        song = db.fetch_one("SELECT title FROM songs WHERE id = ?", (song_id,))
        if song is None:
            raise HTTPException(404, "歌曲不存在")
        song_title = song["title"]

    ext = storage.safe_ext(file.filename, config.AUDIO_EXTS, ".wav")
    work_id = db.execute(
        "INSERT INTO works (user_id, song_id, song_title, title, mode, audio_file, duration) "
        "VALUES (?, ?, ?, ?, ?, ?, ?)",
        (user["id"], song_id, song_title, title.strip() or (song_title or "我的作品"), mode, "", duration),
    )
    dest = config.WORK_UPLOAD_DIR / f"{work_id}{ext}"
    try:
        await storage.save_upload(file, dest)
    except Exception:
        db.execute("DELETE FROM works WHERE id = ?", (work_id,))
        raise
    if not duration:
        duration = storage.probe_duration(dest)
    db.execute("UPDATE works SET audio_file = ?, duration = ? WHERE id = ?", (dest.name, duration, work_id))

    row = db.fetch_one("SELECT * FROM works WHERE id = ?", (work_id,))
    return work_to_dict(row)


@router.get("/works")
def list_works(user: dict = Depends(current_user)) -> dict:
    rows = db.fetch_all(
        "SELECT w.*, u.username FROM works w LEFT JOIN users u ON u.id = w.user_id "
        "WHERE w.user_id = ? ORDER BY w.created_at DESC",
        (user["id"],),
    )
    return {"total": len(rows), "works": [work_to_dict(r) for r in rows]}


@router.get("/works/{work_id}/stream")
def stream_work(work_id: int, request: StarletteRequest, user: dict = Depends(current_user)):
    row = db.fetch_one("SELECT * FROM works WHERE id = ?", (work_id,))
    if row is None or (row["user_id"] != user["id"] and not user.get("is_admin")):
        raise HTTPException(404, "作品不存在")
    if not row["audio_file"]:
        raise HTTPException(404, "音频缺失")
    path = config.WORK_UPLOAD_DIR / row["audio_file"]
    return storage.ranged_file_response(request, path, storage.audio_content_type(path))


@router.get("/works/{work_id}/export")
def export_work(
    work_id: int,
    request: StarletteRequest,
    vocal: float = Query(1.0, ge=0.0, le=2.0),
    accomp: float = Query(0.85, ge=0.0, le=2.0),
    user: dict = Depends(current_user),
):
    """混合导出：作品（人声）+ 伴奏 → WAV（支持 Range，可直接下载/试听）。"""
    row = db.fetch_one("SELECT * FROM works WHERE id = ?", (work_id,))
    if row is None or (row["user_id"] != user["id"] and not user.get("is_admin")):
        raise HTTPException(404, "作品不存在")
    if not row["audio_file"]:
        raise HTTPException(404, "音频缺失")

    work_file = config.WORK_UPLOAD_DIR / row["audio_file"]
    if not work_file.is_file():
        raise HTTPException(404, "音频缺失")

    # 伴奏来源：歌曲的伴奏轨（缺失时退化为原唱/原曲）
    accomp_path = None
    if row["song_id"]:
        song = db.fetch_one("SELECT * FROM songs WHERE id = ?", (row["song_id"],))
        if song is not None:
            song_dir = config.SONG_UPLOAD_DIR / str(row["song_id"])
            for field in ("accomp_file", "vocal_file", "original_file"):
                if song[field] and (song_dir / song[field]).is_file():
                    accomp_path = song_dir / song[field]
                    break

    tag = f"{vocal:.2f}_{accomp:.2f}"
    out = config.WORK_UPLOAD_DIR / f"{work_id}_mix_{tag}.wav"
    if not out.is_file():
        from ..services import mix

        mix.mix_work(work_file, accomp_path, out, vocal_gain=vocal, accomp_gain=accomp)
    return storage.ranged_file_response(request, out, "audio/wav")


@router.delete("/works/{work_id}")
def delete_work(work_id: int, user: dict = Depends(current_user)) -> dict:
    row = db.fetch_one("SELECT * FROM works WHERE id = ?", (work_id,))
    if row is None or (row["user_id"] != user["id"] and not user.get("is_admin")):
        raise HTTPException(404, "作品不存在")
    if row["audio_file"]:
        (config.WORK_UPLOAD_DIR / row["audio_file"]).unlink(missing_ok=True)
    # 混音导出缓存一并清理，避免磁盘泄漏
    for mix_file in config.WORK_UPLOAD_DIR.glob(f"{work_id}_mix_*.wav"):
        mix_file.unlink(missing_ok=True)
    db.execute("DELETE FROM works WHERE id = ?", (work_id,))
    return {"ok": True}
