"""听歌模块：播放队列（服务端存储，网页/App 双端同步）、收藏、自建歌单。

约定：
  - 队列/歌单内歌曲唯一（重复添加为幂等无操作），顺序由 position 维护；
  - 队列为「整份替换」同步语义（last-write-wins），客户端改完 PUT 全量 songIds 即可；
  - 删除歌曲时由管理端清理关联行，列表读取自动跳过已删除歌曲。
"""
from __future__ import annotations

from fastapi import APIRouter, Body, Depends, HTTPException

from .. import db
from .common import current_user
from .serializers import song_to_dict

router = APIRouter(tags=["listen"])


# ---------------------------------------------------------------- 工具

def _clean_ids(raw) -> list[int]:
    """规范化 ID 列表：整型、去重、保序。"""
    out: list[int] = []
    seen: set[int] = set()
    for v in raw or []:
        try:
            sid = int(v)
        except (TypeError, ValueError):
            continue
        if sid > 0 and sid not in seen:
            seen.add(sid)
            out.append(sid)
    return out


def _alive_ids(ids: list[int]) -> list[int]:
    """过滤掉已删除/不存在的歌曲 ID。"""
    if not ids:
        return []
    marks = ",".join("?" * len(ids))
    rows = db.fetch_all(f"SELECT id FROM songs WHERE id IN ({marks})", tuple(ids))
    alive = {r["id"] for r in rows}
    return [i for i in ids if i in alive]


def _require_song(song_id: int) -> None:
    if db.fetch_one("SELECT id FROM songs WHERE id = ?", (song_id,)) is None:
        raise HTTPException(404, "歌曲不存在")


def _load_songs(ids: list[int]) -> list[dict]:
    if not ids:
        return []
    marks = ",".join("?" * len(ids))
    rows = db.fetch_all(f"SELECT * FROM songs WHERE id IN ({marks})", tuple(ids))
    by_id = {row["id"]: song_to_dict(row) for row in rows}
    return [by_id[i] for i in ids if i in by_id]


def _playlist_meta(row) -> dict:
    count = db.scalar(
        "SELECT COUNT(*) FROM playlist_songs WHERE playlist_id = ?", (row["id"],), 0
    )
    duration = db.scalar(
        """SELECT COALESCE(SUM(s.duration), 0) FROM playlist_songs ps
           JOIN songs s ON s.id = ps.song_id WHERE ps.playlist_id = ?""",
        (row["id"],),
        0,
    )
    return {
        "id": row["id"],
        "name": row["name"],
        "count": count,
        "duration": round(float(duration or 0), 1),
        "created_at": row["created_at"],
        "updated_at": row["updated_at"],
    }


def _get_playlist_or_404(playlist_id: int, user: dict):
    row = db.fetch_one("SELECT * FROM playlists WHERE id = ?", (playlist_id,))
    if row is None or row["user_id"] != user["id"]:
        raise HTTPException(404, "歌单不存在")
    return row


# ---------------------------------------------------------------- 播放队列

@router.get("/listen/queue")
def get_queue(user: dict = Depends(current_user)) -> dict:
    rows = db.fetch_all(
        "SELECT song_id FROM play_queue WHERE user_id = ? ORDER BY position", (user["id"],)
    )
    ids = [r["song_id"] for r in rows]
    return {"songs": _load_songs(ids), "songIds": ids}


@router.put("/listen/queue")
def replace_queue(payload: dict = Body(...), user: dict = Depends(current_user)) -> dict:
    """整份替换队列（同步语义：后写覆盖）。"""
    ids = _alive_ids(_clean_ids(payload.get("songIds")))
    db.execute("DELETE FROM play_queue WHERE user_id = ?", (user["id"],))
    for pos, sid in enumerate(ids):
        db.execute(
            "INSERT OR IGNORE INTO play_queue (user_id, song_id, position) VALUES (?, ?, ?)",
            (user["id"], sid, pos),
        )
    return get_queue(user)


@router.post("/listen/queue")
def append_queue(payload: dict = Body(...), user: dict = Depends(current_user)) -> dict:
    sid = int(payload.get("songId") or 0)
    _require_song(sid)
    if db.fetch_one(
        "SELECT 1 FROM play_queue WHERE user_id = ? AND song_id = ?", (user["id"], sid)
    ) is None:
        pos = db.scalar(
            "SELECT COALESCE(MAX(position), -1) + 1 FROM play_queue WHERE user_id = ?",
            (user["id"],),
            0,
        )
        db.execute(
            "INSERT INTO play_queue (user_id, song_id, position) VALUES (?, ?, ?)",
            (user["id"], sid, pos),
        )
    return get_queue(user)


@router.delete("/listen/queue/{song_id}")
def remove_queue(song_id: int, user: dict = Depends(current_user)) -> dict:
    db.execute(
        "DELETE FROM play_queue WHERE user_id = ? AND song_id = ?", (user["id"], song_id)
    )
    return get_queue(user)


@router.delete("/listen/queue")
def clear_queue(user: dict = Depends(current_user)) -> dict:
    db.execute("DELETE FROM play_queue WHERE user_id = ?", (user["id"],))
    return {"songs": [], "songIds": []}


# ---------------------------------------------------------------- 收藏

@router.get("/listen/favorites")
def list_favorites(user: dict = Depends(current_user)) -> dict:
    rows = db.fetch_all(
        """SELECT song_id FROM favorites WHERE user_id = ?
           ORDER BY created_at DESC, song_id DESC""",
        (user["id"],),
    )
    ids = [r["song_id"] for r in rows]
    return {"songs": _load_songs(ids), "songIds": ids}


@router.post("/listen/favorites/{song_id}")
def add_favorite(song_id: int, user: dict = Depends(current_user)) -> dict:
    _require_song(song_id)
    db.execute(
        "INSERT OR IGNORE INTO favorites (user_id, song_id) VALUES (?, ?)",
        (user["id"], song_id),
    )
    return {"ok": True}


@router.delete("/listen/favorites/{song_id}")
def remove_favorite(song_id: int, user: dict = Depends(current_user)) -> dict:
    db.execute(
        "DELETE FROM favorites WHERE user_id = ? AND song_id = ?", (user["id"], song_id)
    )
    return {"ok": True}


# ---------------------------------------------------------------- 歌单

@router.get("/listen/playlists")
def list_playlists(user: dict = Depends(current_user)) -> dict:
    rows = db.fetch_all(
        "SELECT * FROM playlists WHERE user_id = ? ORDER BY created_at DESC, id DESC",
        (user["id"],),
    )
    return {"playlists": [_playlist_meta(r) for r in rows]}


@router.post("/listen/playlists")
def create_playlist(payload: dict = Body(...), user: dict = Depends(current_user)) -> dict:
    name = str(payload.get("name", "")).strip()
    if not name:
        raise HTTPException(422, "歌单名不能为空")
    if len(name) > 64:
        raise HTTPException(422, "歌单名过长")
    cur = db.execute(
        "INSERT INTO playlists (user_id, name) VALUES (?, ?)", (user["id"], name)
    )
    row = db.fetch_one("SELECT * FROM playlists WHERE id = ?", (cur,))
    return {"playlist": _playlist_meta(row)}


@router.put("/listen/playlists/{playlist_id}")
def rename_playlist(
    playlist_id: int, payload: dict = Body(...), user: dict = Depends(current_user)
) -> dict:
    row = _get_playlist_or_404(playlist_id, user)
    name = str(payload.get("name", "")).strip()
    if not name:
        raise HTTPException(422, "歌单名不能为空")
    db.execute(
        "UPDATE playlists SET name = ?, updated_at = datetime('now') WHERE id = ?",
        (name, row["id"]),
    )
    row = db.fetch_one("SELECT * FROM playlists WHERE id = ?", (playlist_id,))
    return {"playlist": _playlist_meta(row)}


@router.delete("/listen/playlists/{playlist_id}")
def delete_playlist(playlist_id: int, user: dict = Depends(current_user)) -> dict:
    row = _get_playlist_or_404(playlist_id, user)
    db.execute("DELETE FROM playlist_songs WHERE playlist_id = ?", (row["id"],))
    db.execute("DELETE FROM playlists WHERE id = ?", (row["id"],))
    return {"ok": True}


@router.get("/listen/playlists/{playlist_id}")
def playlist_detail(playlist_id: int, user: dict = Depends(current_user)) -> dict:
    row = _get_playlist_or_404(playlist_id, user)
    rows = db.fetch_all(
        "SELECT song_id FROM playlist_songs WHERE playlist_id = ? ORDER BY position",
        (row["id"],),
    )
    ids = [r["song_id"] for r in rows]
    return {"playlist": _playlist_meta(row), "songs": _load_songs(ids), "songIds": ids}


@router.post("/listen/playlists/{playlist_id}/songs")
def add_playlist_song(
    playlist_id: int, payload: dict = Body(...), user: dict = Depends(current_user)
) -> dict:
    row = _get_playlist_or_404(playlist_id, user)
    sid = int(payload.get("songId") or 0)
    _require_song(sid)
    if db.fetch_one(
        "SELECT 1 FROM playlist_songs WHERE playlist_id = ? AND song_id = ?",
        (row["id"], sid),
    ) is None:
        pos = db.scalar(
            "SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_songs WHERE playlist_id = ?",
            (row["id"],),
            0,
        )
        db.execute(
            "INSERT INTO playlist_songs (playlist_id, song_id, position) VALUES (?, ?, ?)",
            (row["id"], sid, pos),
        )
        db.execute(
            "UPDATE playlists SET updated_at = datetime('now') WHERE id = ?", (row["id"],)
        )
    return {"ok": True, "count": db.scalar(
        "SELECT COUNT(*) FROM playlist_songs WHERE playlist_id = ?", (row["id"],), 0
    )}


@router.delete("/listen/playlists/{playlist_id}/songs/{song_id}")
def remove_playlist_song(
    playlist_id: int, song_id: int, user: dict = Depends(current_user)
) -> dict:
    row = _get_playlist_or_404(playlist_id, user)
    db.execute(
        "DELETE FROM playlist_songs WHERE playlist_id = ? AND song_id = ?",
        (row["id"], song_id),
    )
    _reindex_playlist(row["id"])
    db.execute(
        "UPDATE playlists SET updated_at = datetime('now') WHERE id = ?", (row["id"],)
    )
    return {"ok": True}


@router.put("/listen/playlists/{playlist_id}/songs")
def replace_playlist_songs(
    playlist_id: int, payload: dict = Body(...), user: dict = Depends(current_user)
) -> dict:
    """整份替换歌单曲目与顺序（拖拽排序/批量添加都走这里）。"""
    row = _get_playlist_or_404(playlist_id, user)
    ids = _alive_ids(_clean_ids(payload.get("songIds")))
    db.execute("DELETE FROM playlist_songs WHERE playlist_id = ?", (row["id"],))
    for pos, sid in enumerate(ids):
        db.execute(
            "INSERT OR IGNORE INTO playlist_songs (playlist_id, song_id, position) VALUES (?, ?, ?)",
            (row["id"], sid, pos),
        )
    db.execute(
        "UPDATE playlists SET updated_at = datetime('now') WHERE id = ?", (row["id"],)
    )
    return playlist_detail(playlist_id, user)


def _reindex_playlist(playlist_id: int) -> None:
    rows = db.fetch_all(
        "SELECT song_id FROM playlist_songs WHERE playlist_id = ? ORDER BY position",
        (playlist_id,),
    )
    for pos, r in enumerate(rows):
        db.execute(
            "UPDATE playlist_songs SET position = ? WHERE playlist_id = ? AND song_id = ?",
            (pos, playlist_id, r["song_id"]),
        )
