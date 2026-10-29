"""公共依赖：登录校验、请求体模型。"""
from __future__ import annotations

from fastapi import Depends, HTTPException, Query, Request
from pydantic import BaseModel, Field

from .. import db, security


class LoginBody(BaseModel):
    username: str = Field(min_length=1, max_length=64)
    password: str = Field(min_length=1, max_length=256)


class InitBody(LoginBody):
    pass


class PasswordBody(BaseModel):
    old_password: str = Field(min_length=1, max_length=256)
    new_password: str = Field(min_length=6, max_length=256)


def extract_token(request: Request, token: str | None = Query(default=None)) -> str | None:
    """支持 Authorization: Bearer xxx 或 ?token=xxx（供网页 <audio> 使用）。"""
    auth = request.headers.get("authorization", "")
    if auth.lower().startswith("bearer "):
        return auth[7:].strip()
    return token


def current_user(request: Request, token: str | None = Query(default=None)) -> dict:
    raw = extract_token(request, token)
    if not raw:
        raise HTTPException(401, "未登录")
    uid = security.verify_token(raw)
    if uid is None:
        raise HTTPException(401, "登录已失效，请重新登录")
    row = db.fetch_one("SELECT id, username, is_admin FROM users WHERE id = ?", (uid,))
    if row is None:
        raise HTTPException(401, "账号不存在")
    return dict(row)


def admin_user(user: dict = Depends(current_user)) -> dict:
    if not user.get("is_admin"):
        raise HTTPException(403, "需要管理员账号")
    return user


def is_initialized() -> bool:
    return bool(db.scalar("SELECT COUNT(*) FROM users", default=0))
