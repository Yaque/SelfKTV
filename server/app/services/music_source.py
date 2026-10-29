"""GD音乐台 音乐源（Meting 协议）。

官方开发者 API（零签名）：https://music-api.gdstudio.xyz/api.php
  types=search  name/count/pages/source(+_album)
  types=url     id/source/br(128|192|320|740|999)
  types=lyric   id/source      -> {lyric, tlyric}
  types=pic     id/source/size(300|500)

网页镜像 music.gdstudio.org/api.php 带防爬（s=crc32 参数），仅作兜底尝试。
频率限制：5 分钟内不超过 50 次请求（客户端主动限速）。

使用本模块即应注明数据出处：GD音乐台(music.gdstudio.xyz)。
仅供个人学习使用，请勿商用。
"""
from __future__ import annotations

import binascii
import json
import os
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/126.0 Safari/537.36"
)

# 多节点回退：国内节点优先，官方主节点次之，网页镜像兜底
HOSTS = [
    "https://music-api-cn.gdstudio.xyz/api.php",
    "https://music-api.gdstudio.xyz/api.php",
    "https://music-api-hk.gdstudio.xyz/api.php",
    "https://music-api-us.gdstudio.xyz/api.php",
    "https://music.gdstudio.org/api.php",
]

MIRROR_HOST = "music.gdstudio.org"

AVAILABLE_SOURCES = ["netease", "joox", "bilibili"]  # 动态开放源，可按站点公告调整
BR_CHOICES = [128, 192, 320, 740, 999]

# 测试用离线 mock（MOCK_MUSIC_SOURCE=1 开启，生产环境勿设）
MOCK = os.environ.get("MOCK_MUSIC_SOURCE") == "1"

# ---- 客户端限速：滑动窗口，5 分钟 45 次（给站点频率限制留余量） ----
_RATE_LOCK = threading.Lock()
_RATE_HIST: list[float] = []
_RATE_WINDOW = 300.0
_RATE_MAX = 45


class MusicSourceError(RuntimeError):
    pass


def _throttle() -> None:
    with _RATE_LOCK:
        now = time.time()
        while _RATE_HIST and now - _RATE_HIST[0] > _RATE_WINDOW:
            _RATE_HIST.pop(0)
        if len(_RATE_HIST) >= _RATE_MAX:
            wait = _RATE_WINDOW - (now - _RATE_HIST[0])
            raise MusicSourceError(f"音乐源请求过于频繁，请 {int(wait) + 1} 秒后再试（5 分钟上限 {_RATE_MAX} 次）")
        _RATE_HIST.append(now)


def _crc32(s: str) -> int:
    return binascii.crc32(s.encode("utf-8")) & 0xFFFFFFFF


def _http_get_json(url: str, timeout: float = 10.0):
    req = urllib.request.Request(
        url,
        headers={"User-Agent": UA, "Referer": "https://music.gdstudio.org/"},
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        raw = resp.read()
    return json.loads(raw.decode("utf-8", "replace"))


def _get(params: dict):
    """按 HOSTS 顺序回退请求；返回 (data, host)。"""
    qs = urllib.parse.urlencode(params, quote_via=urllib.parse.quote)
    last_err: Exception | None = None
    for host in HOSTS:
        url = f"{host}?{qs}"
        if MIRROR_HOST in host:
            # 网页镜像兜底：附带 s=crc32 参数（网页前端的签名方式）
            for key in ("name", "id"):
                if params.get(key):
                    url += f"&s={_crc32(str(params[key]))}"
                    break
        _throttle()
        try:
            data = _http_get_json(url)
        except urllib.error.HTTPError as e:
            last_err = MusicSourceError(f"{host} 返回 {e.code}")
            continue
        except Exception as e:  # noqa: BLE001 网络类异常统一回退
            last_err = e
            continue
        if data is None or data == [] or data == {}:
            return data, host
        return data, host
    raise MusicSourceError(f"所有音乐源节点均不可用：{last_err}")


def search(keyword: str, source: str = "netease", count: int = 20, pages: int = 1) -> list[dict]:
    """搜索歌曲，返回 [{id,name,artist,album,pic_id,lyric_id,source}]。"""
    if not keyword.strip():
        return []
    if MOCK:
        return [{
            "id": "mock1", "name": f"{keyword.strip()}(Mock)", "artist": ["测试歌手"],
            "album": "测试专辑", "pic_id": "mockpic", "lyric_id": "mock1", "source": source,
        }]
    data, _ = _get(
        {
            "types": "search",
            "name": keyword.strip(),
            "count": count,
            "pages": pages,
            "source": source,
        }
    )
    if not isinstance(data, list):
        return []
    return [
        {
            "id": str(x.get("id", "")),
            "name": x.get("name", ""),
            "artist": x.get("artist", []),
            "album": x.get("album", ""),
            "pic_id": str(x.get("pic_id", "")),
            "lyric_id": str(x.get("lyric_id", "") or x.get("id", "")),
            "source": x.get("source", source),
        }
        for x in data
    ]


def song_url(source: str, track_id: str, br: int = 320) -> dict:
    """获取音频直链：{url, br, size}。"""
    if br not in BR_CHOICES:
        br = 320
    if MOCK:
        return {"url": "mock://audio", "br": br, "size": 1000}
    data, _ = _get({"types": "url", "source": source, "id": track_id, "br": br})
    if not isinstance(data, dict) or not data.get("url"):
        raise MusicSourceError("该歌曲暂无可用音源")
    return data


def lyric(source: str, lyric_id: str) -> tuple[str, str]:
    """获取歌词，返回 (原语种 LRC, 翻译 LRC 或空串)。"""
    if MOCK:
        return "[00:01.00]测试歌词第一句\n[00:03.00]测试歌词第二句", ""
    data, _ = _get({"types": "lyric", "source": source, "id": lyric_id})
    if not isinstance(data, dict):
        return "", ""
    return str(data.get("lyric") or ""), str(data.get("tlyric") or "")


def pic_url(source: str, pic_id: str, size: int = 500) -> str | None:
    """获取专辑图直链。"""
    if not pic_id:
        return None
    if MOCK:
        return "mock://pic"
    data, _ = _get({"types": "pic", "source": source, "id": pic_id, "size": size})
    if isinstance(data, dict):
        return data.get("url") or None
    return None


def merge_lyric(original: str, translation: str) -> str:
    """把翻译歌词合并进原语种 LRC（时间轴相同则插入译文行）。"""
    if not translation.strip():
        return original
    import re

    def parse(text: str) -> dict[str, list[str]]:
        out: dict[str, list[str]] = {}
        for line in text.splitlines():
            stamps = re.findall(r"\[(\d+):(\d+(?:\.\d+)?)\]", line)
            content = re.sub(r"\[[^\]]*\]", "", line).strip()
            for m, s in stamps:
                key = f"{int(m):02d}:{float(s):06.3f}"
                out.setdefault(key, []).append(content)
        return out

    tmap = parse(translation)
    if not tmap:
        return original
    merged: list[str] = []
    for line in original.splitlines():
        merged.append(line)
        stamps = re.findall(r"\[(\d+):(\d+(?:\.\d+)?)\]", line)
        for m, s in stamps:
            key = f"{int(m):02d}:{float(s):06.3f}"
            for tr in tmap.get(key, []):
                if tr:
                    merged.append(f"[{key}]{tr}")
    return "\n".join(merged)
