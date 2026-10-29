"""首次部署的「分离组件准备」安装器：安装 demucs 引擎 + 下载模型，带进度。

管理页在系统初始化后展示进度条，通过 /api/admin/setup/* 查询与触发。
所有操作仅限管理员。
"""
from __future__ import annotations

import os
import re
import subprocess
import sys
import threading

from .. import config
from . import separate

_lock = threading.Lock()
_state: dict = {
    "installing": False,
    "stage": "idle",       # idle | engine | model | verify | done | error
    "stage_text": "",
    "percent": 0,
    "error": "",
    "log": [],
}


def _log(line: str) -> None:
    _state["log"] = (_state["log"] + [line])[-40:]


def status() -> dict:
    """当前安装进度 + 引擎可用性。"""
    engine = separate.get_engine()
    model_ready = separate.model_ready()
    with _lock:
        data = dict(_state)
    data.update({"engine": engine, "model_ready": model_ready, "ready": bool(engine) and model_ready})
    return data


def start_install() -> dict:
    """开始（或重新开始）安装流程，立即返回当前状态。"""
    with _lock:
        if _state["installing"]:
            return status()
        _state.update(installing=True, stage="engine", stage_text="准备安装分离组件…",
                      percent=0, error="", log=[])
    threading.Thread(target=_install, name="setup-install", daemon=True).start()
    return status()


def _set(stage: str, text: str, percent: int) -> None:
    with _lock:
        _state.update(stage=stage, stage_text=text, percent=max(0, min(100, percent)))


def _finish(error: str = "") -> None:
    with _lock:
        _state.update(installing=False, error=error,
                      stage="error" if error else "done",
                      stage_text=f"安装失败：{error}" if error else "分离组件已就绪 🎉",
                      percent=100 if not error else _state["percent"])


def _run_pip_install() -> None:
    _set("engine", "正在安装 demucs 分离引擎（含 PyTorch 依赖）…", 2)
    proc = subprocess.Popen(
        [sys.executable, "-m", "pip", "install", "demucs"],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
        env={**os.environ, "PIP_DISABLE_PIP_VERSION_CHECK": "1"},
    )
    assert proc.stdout is not None
    for line in proc.stdout:
        line = line.strip()
        if line:
            _log(line)
        # 粗略进度：根据输出行数推进到 50%
        pct = min(48, 2 + len(_state["log"]) // 2)
        _set("engine", "正在安装 demucs 分离引擎…", pct)
    if proc.wait() != 0:
        raise RuntimeError("pip 安装 demucs 失败，请查看日志")


def _run_model_download() -> None:
    _set("model", "正在下载分离模型（约 80MB）…", 50)
    code = (
        "import os; os.environ['HF_HUB_OFFLINE']='1'; "
        "from demucs.pretrained import get_model; get_model('htdemucs'); print('MODEL_OK')"
    )
    env = {**os.environ, "HF_HUB_OFFLINE": "1"}
    proc = subprocess.Popen(
        [sys.executable, "-u", "-c", code],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, env=env,
    )
    assert proc.stdout is not None
    percent_re = re.compile(r"(\d{1,3})%")
    for line in proc.stdout:
        for m in percent_re.finditer(line):
            p = int(m.group(1))
            _set("model", "正在下载分离模型…", 50 + int(p * 0.42))
        if line.strip():
            _log(line.strip()[-120:])
    if proc.wait() != 0:
        raise RuntimeError("模型下载失败，请检查网络")


def _install() -> None:
    try:
        if separate.get_engine() is None:
            _run_pip_install()
        else:
            _log("分离引擎已安装，跳过")
        if not separate.model_ready():
            _run_model_download()
        else:
            _log("分离模型已就绪，跳过")
        _set("verify", "正在验证分离组件…", 96)
        if separate.get_engine() is None or not separate.model_ready():
            raise RuntimeError("验证失败：引擎或模型仍不可用")
        _set("done", "分离组件已就绪 🎉", 100)
        _finish()
    except Exception as exc:
        _log(f"ERROR: {exc}")
        _finish(str(exc)[:300])
