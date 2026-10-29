"""从音频文件的元数据标签中提取内嵌歌词。

支持：
  - MP3 / ID3v2：SYLT（带时间戳歌词 → 直接转 LRC）、USLT（整段歌词）
  - MP4 / M4A：©lyr 原子
  - FLAC / OGG / Opus（Vorbis Comment）：LYRICS / UNSYNCEDLYRICS / SYNCEDLYRICS

返回 (text, is_lrc)；没有歌词返回 None。
"""
from __future__ import annotations

import re
from pathlib import Path

# Vorbis/MP4 中可能出现的歌词键
_LYRIC_KEYS = (
    "lyrics",
    "unsyncedlyrics",
    "unsynced lyrics",
    "syncedlyrics",
    "synced lyrics",
    "©lyr",
    "\xa9lyr",
)

_LRC_TAG = re.compile(r"\[\d{1,3}:\d{2}(?:[.:]\d{1,3})?]")


def _looks_like_lrc(text: str) -> bool:
    return bool(_LRC_TAG.search(text))


def _from_sylt(entries) -> str | None:
    """SYLT 时间戳歌词 → LRC 文本。entries: [(text, time_ms), ...]"""
    lines = []
    for text, time_ms in entries:
        text = (text or "").strip()
        if not text:
            continue
        total_ms = int(time_ms or 0)
        minutes, seconds = divmod(total_ms // 1000, 60)
        centis = (total_ms % 1000) // 10
        lines.append(f"[{minutes:02d}:{seconds:02d}.{centis:02d}]{text}")
    return "\n".join(lines) if lines else None


def _from_id3(tags) -> tuple[str, bool] | None:
    """处理 ID3 标签：优先 SYLT（带时间戳），其次 USLT。"""
    try:
        sylts = tags.getall("SYLT")
    except Exception:
        sylts = []
    for frame in sylts:
        try:
            text = _from_sylt(list(frame.text))
            if text:
                return text, True
        except Exception:
            continue

    try:
        uslts = tags.getall("USLT")
    except Exception:
        uslts = []
    # 优先选择内容最长的非空 USLT（跳过封面/注释类短文本）
    best = ""
    for frame in uslts:
        try:
            content = str(frame.text or "").strip()
        except Exception:
            continue
        if len(content) > len(best):
            best = content
    if best:
        return best, _looks_like_lrc(best)
    return None


def _from_mapping(tags) -> tuple[str, bool] | None:
    """处理 Vorbis Comment / MP4 标签映射。"""
    try:
        items = list(tags.items())
    except Exception:
        return None
    lowered = [(str(k).lower(), str(v)) for k, v in items]
    for key in _LYRIC_KEYS:
        for k, v in lowered:
            if k == key and v.strip():
                return v.strip(), _looks_like_lrc(v)
    # 兜底：任何名字里带 lyric 的标签
    for k, v in lowered:
        if "lyric" in k and v.strip():
            return v.strip(), _looks_like_lrc(v)
    return None


def extract_embedded_lyrics(path: Path) -> tuple[str, bool] | None:
    """提取内嵌歌词，返回 (text, is_lrc) 或 None。"""
    if not path.is_file():
        return None
    try:
        import mutagen

        media = mutagen.File(str(path))
    except Exception:
        media = None

    if media is not None and getattr(media, "tags", None) is not None:
        # ID3（MP3）
        if hasattr(media.tags, "getall"):
            result = _from_id3(media.tags)
            if result:
                return result
        # Vorbis / MP4 映射
        result = _from_mapping(media.tags)
        if result:
            return result

    # mutagen.File 识别失败时，尝试直接读 ID3v2 头（文件可能损坏/非标准）
    try:
        from mutagen.id3 import ID3

        result = _from_id3(ID3(str(path)))
        if result:
            return result
    except Exception:
        pass

    # MP4 没识别出来时再试 mutagen.mp4 直读
    if path.suffix.lower() in {".m4a", ".mp4", ".m4b"}:
        try:
            from mutagen.mp4 import MP4

            media = MP4(str(path))
            if media.tags is not None:
                result = _from_mapping(media.tags)
                if result:
                    return result
        except Exception:
            pass

    return None
