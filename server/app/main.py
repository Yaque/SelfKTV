"""SelfKTV 服务端入口。"""
from __future__ import annotations

from pathlib import Path

from fastapi import FastAPI
from fastapi.responses import RedirectResponse
from fastapi.staticfiles import StaticFiles

from . import config, db
from .api import admin as admin_api
from .api import listen as listen_api
from .api import mobile as mobile_api
from .services import separate

STATIC_DIR = Path(__file__).resolve().parent / "static"

app = FastAPI(
    title="SelfKTV Server",
    description="开源个人 K 歌系统 · 服务端",
    version="1.0.0",
    docs_url="/api/docs",
    redoc_url=None,
)


@app.on_event("startup")
def on_startup() -> None:
    db.init_db()
    separate.resume_pending()        # 恢复上次未完成的分离任务
    separate.backfill_references()   # 存量歌曲缺参考旋律 → 自动补转录


@app.get("/", include_in_schema=False)
def index() -> RedirectResponse:
    return RedirectResponse("/admin/")


@app.get("/api/ping", include_in_schema=False)
def ping_root():
    return mobile_api.ping()


# 业务 API
app.include_router(mobile_api.router, prefix="/api")
app.include_router(admin_api.router, prefix="/api/admin")
app.include_router(listen_api.router, prefix="/api")

# 管理页面（静态 SPA）
app.mount("/admin", StaticFiles(directory=str(STATIC_DIR), html=True), name="admin")
