"""密码散列与登录令牌（HMAC 签名，不依赖第三方库）。"""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import secrets
import time

from . import config

_PBKDF2_ITERATIONS = 200_000


# ---------------------------------------------------------------- 密码

def hash_password(password: str, salt: str | None = None) -> tuple[str, str]:
    """返回 (password_hash, salt)，均为十六进制字符串。"""
    salt = salt or secrets.token_hex(16)
    digest = hashlib.pbkdf2_hmac(
        "sha256", password.encode("utf-8"), bytes.fromhex(salt), _PBKDF2_ITERATIONS
    )
    return digest.hex(), salt


def verify_password(password: str, password_hash: str, salt: str) -> bool:
    digest, _ = hash_password(password, salt)
    return hmac.compare_digest(digest, password_hash)


# ---------------------------------------------------------------- 服务器密钥

def load_secret() -> bytes:
    """读取（首次自动生成）服务器签名密钥。"""
    config.ensure_dirs()
    if config.SECRET_PATH.exists():
        return config.SECRET_PATH.read_bytes()
    secret = secrets.token_bytes(32)
    config.SECRET_PATH.write_bytes(secret)
    try:
        os.chmod(config.SECRET_PATH, 0o600)
    except OSError:
        pass
    return secret


# ---------------------------------------------------------------- 令牌

def _b64e(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")


def _b64d(text: str) -> bytes:
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


def create_token(user_id: int, ttl_seconds: int | None = None) -> str:
    payload = {"uid": user_id, "exp": int(time.time()) + (ttl_seconds or config.TOKEN_TTL_SECONDS)}
    body = _b64e(json.dumps(payload, separators=(",", ":")).encode("utf-8"))
    sig = _b64e(hmac.new(load_secret(), body.encode("ascii"), hashlib.sha256).digest())
    return f"{body}.{sig}"


def verify_token(token: str) -> int | None:
    """校验令牌，成功返回 user_id，失败返回 None。"""
    try:
        body, sig = token.split(".", 1)
        expect = _b64e(hmac.new(load_secret(), body.encode("ascii"), hashlib.sha256).digest())
        if not hmac.compare_digest(sig, expect):
            return None
        payload = json.loads(_b64d(body))
        if int(payload["exp"]) < time.time():
            return None
        return int(payload["uid"])
    except (ValueError, KeyError, TypeError):
        return None
