"""数据库行 → API 输出结构。"""
from __future__ import annotations

from typing import Any

from .. import config


def song_to_dict(row: Any) -> dict:
    """歌曲信息（含资源 URL，客户端拼接服务器地址即可使用）。"""
    sid = row["id"]
    has_vocal = bool(row["vocal_file"])
    has_accomp = bool(row["accomp_file"])
    has_original = bool(row["original_file"]) if "original_file" in row.keys() else False
    separating = (row["separate_status"] if "separate_status" in row.keys() else "done") in ("queued", "running")
    return {
        "id": sid,
        "title": row["title"],
        "artist": row["artist"],
        "duration": row["duration"],
        "created_at": row["created_at"],
        "updated_at": row["updated_at"],
        "has_vocal": has_vocal,
        "has_accomp": has_accomp,
        "has_original": has_original,
        "has_lyric": bool(row["lyric_file"]),
        "has_cover": bool(row["cover_file"]),
        "separating": separating,
        "has_pitch": any(
            (config.SONG_UPLOAD_DIR / str(sid) / name).is_file()
            for name in ("notes.json", "pitch.json")
        ),
        "separate_status": row["separate_status"] if "separate_status" in row.keys() else "done",
        "separate_error": row["separate_error"] if "separate_error" in row.keys() else "",
        # 智能模式需要同时具备原唱与伴奏两条音轨
        "smart_available": has_vocal and has_accomp,
        "cover_url": f"/api/songs/{sid}/cover" if row["cover_file"] else None,
        "lyric_url": f"/api/songs/{sid}/lyrics" if row["lyric_file"] else None,
        "tracks": {
            "vocal": f"/api/songs/{sid}/stream?track=vocal" if has_vocal else None,
            "accomp": f"/api/songs/{sid}/stream?track=accomp" if has_accomp else None,
            "original": f"/api/songs/{sid}/stream?track=original" if has_original else None,
        },
    }


def song_to_admin_dict(row: Any) -> dict:
    data = song_to_dict(row)
    data.update(
        {
            "cover_file": row["cover_file"],
            "vocal_file": row["vocal_file"],
            "accomp_file": row["accomp_file"],
            "lyric_file": row["lyric_file"],
            "original_file": row["original_file"] if "original_file" in row.keys() else None,
            "separate_status": row["separate_status"] if "separate_status" in row.keys() else "done",
            "separate_error": row["separate_error"] if "separate_error" in row.keys() else "",
            "ref_status": row["ref_status"] if "ref_status" in row.keys() else "",
            "ref_error": row["ref_error"] if "ref_error" in row.keys() else "",
        }
    )
    return data


def work_to_dict(row: Any) -> dict:
    wid = row["id"]
    return {
        "id": wid,
        "song_id": row["song_id"],
        "song_title": row["song_title"],
        "title": row["title"],
        "mode": row["mode"],
        "duration": row["duration"],
        "created_at": row["created_at"],
        "username": row["username"] if "username" in row.keys() else None,
        "audio_url": f"/api/works/{wid}/stream",
    }
