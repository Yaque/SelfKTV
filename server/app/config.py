"""SelfKTV 服务端配置。

所有运行期数据（数据库、上传文件、密钥）都存放在 data/ 目录，
备份该目录即可完整迁移系统。
"""
from __future__ import annotations

import os
from pathlib import Path

# 项目根目录（server/）
BASE_DIR = Path(__file__).resolve().parent.parent

# 运行期数据目录，可用环境变量 SELFKTV_DATA 覆盖
DATA_DIR = Path(os.environ.get("SELFKTV_DATA", str(BASE_DIR / "data"))).resolve()
DB_PATH = DATA_DIR / "selfktv.db"
UPLOAD_DIR = DATA_DIR / "uploads"
SECRET_PATH = DATA_DIR / "secret.key"

SONG_UPLOAD_DIR = UPLOAD_DIR / "songs"
WORK_UPLOAD_DIR = UPLOAD_DIR / "works"

# 上传大小上限（单文件，MB）
MAX_UPLOAD_MB = int(os.environ.get("SELFKTV_MAX_UPLOAD_MB", "512"))

# 登录令牌有效期（秒），默认 30 天
TOKEN_TTL_SECONDS = int(os.environ.get("SELFKTV_TOKEN_TTL", str(60 * 60 * 24 * 30)))

# 允许的文件后缀
AUDIO_EXTS = {".mp3", ".wav", ".m4a", ".aac", ".flac", ".ogg", ".opus"}
COVER_EXTS = {".jpg", ".jpeg", ".png", ".webp"}
LYRIC_EXTS = {".lrc", ".txt"}

# 分块流式传输大小
CHUNK_SIZE = 64 * 1024


def ensure_dirs() -> None:
    """创建运行期目录。"""
    for d in (DATA_DIR, UPLOAD_DIR, SONG_UPLOAD_DIR, WORK_UPLOAD_DIR):
        d.mkdir(parents=True, exist_ok=True)
