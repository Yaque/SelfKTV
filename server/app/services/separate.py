"""人声/伴奏分离服务（开源方案，支持两种引擎）。

引擎选择（自动探测，优先质量更好的 Demucs）：
  1. demucs（PyTorch，htdemucs 模型，两轨输出 vocals / no_vocals）
  2. audio-separator（UVR-MDX-Net ONNX 模型，CPU 即可运行）

安装任一即可：
    pip install -r requirements-separation.txt   # 默认安装 demucs + audio-separator
    或单独：pip install demucs / pip install "audio-separator[cpu]"

分离在后台线程池（并发 1）执行，状态写回 songs.separate_status：
    queued → running → done | error
"""
from __future__ import annotations

import importlib.util
import os
import shutil
import subprocess
import sys
import threading
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from .. import config, db, storage

_executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="separate")
_lock = threading.Lock()

DEMUCS_MODEL = "htdemucs"
MDX_MODEL = "UVR-MDX-NET-Inst_HQ_2.onnx"


# ---------------------------------------------------------------- 引擎探测

def get_engine() -> str | None:
    """返回可用引擎名，无可用返回 None。"""
    if importlib.util.find_spec("demucs") is not None:
        return "demucs"
    if importlib.util.find_spec("audio_separator") is not None:
        return "audio-separator"
    if shutil.which("demucs") is not None:
        return "demucs-cli"
    if shutil.which("audio-separator") is not None:
        return "audio-separator-cli"
    return None


def model_ready() -> bool:
    """分离模型是否已就位（决定是否需要下载）。"""
    engine = get_engine()
    if engine in ("audio-separator", "audio-separator-cli"):
        return any((config.DATA_DIR / "models").glob("*.onnx"))
    if engine in ("demucs", "demucs-cli"):
        try:
            import torch.hub

            ckpt = Path(torch.hub.get_dir()) / "checkpoints"
        except Exception:
            ckpt = Path.home() / ".cache/torch/hub/checkpoints"
        return ckpt.exists() and any(ckpt.glob("*.th"))
    return False


def engine_hint() -> str:
    return get_engine() or "未安装分离引擎（pip install -r requirements-separation.txt）"


# ---------------------------------------------------------------- 分离实现

def _run_demucs_python(original: Path, out_dir: Path) -> tuple[Path, Path]:
    # demucs 4.x 优先查 HuggingFace；网络不通时改用官方 AWS 源（dl.fbaipublicfiles.com）
    os.environ.setdefault("HF_HUB_OFFLINE", "1")
    from demucs.api import Separator, save_audio  # type: ignore

    sep = Separator(model=DEMUCS_MODEL, device="cpu", progress=True)
    _origin, stems = sep.separate_audio_file(original)
    vocal = stems.get("vocals")
    if vocal is None:
        raise RuntimeError("demucs 未返回 vocals 分轨")
    accomp = None
    for name, tensor in stems.items():
        if name == "vocals":
            continue
        accomp = tensor if accomp is None else accomp + tensor
    if accomp is None:
        raise RuntimeError("demucs 未返回伴奏分轨")

    vocal_path = out_dir / "vocal.wav"
    accomp_path = out_dir / "accomp.wav"
    save_audio(vocal, vocal_path, samplerate=sep.samplerate)
    save_audio(accomp, accomp_path, samplerate=sep.samplerate)
    return vocal_path, accomp_path


def _run_demucs_cli(original: Path, out_dir: Path) -> tuple[Path, Path]:
    os.environ.setdefault("HF_HUB_OFFLINE", "1")
    work = out_dir / ".demucs_tmp"
    shutil.rmtree(work, ignore_errors=True)
    cmd = [sys.executable, "-m", "demucs", "--two-stems", "vocals", "-o", str(work), str(original)]
    exe = shutil.which("demucs")
    if exe:
        cmd = [exe, "--two-stems", "vocals", "-o", str(work), str(original)]
    proc = subprocess.run(cmd, capture_output=True, text=True, timeout=3600)
    if proc.returncode != 0:
        raise RuntimeError(f"demucs 失败: {proc.stderr[-500:]}")
    vocal_path, accomp_path = _pick_stems(work, "vocals", "no_vocals")
    vocal_path.replace(out_dir / "vocal.wav")
    accomp_path.replace(out_dir / "accomp.wav")
    shutil.rmtree(work, ignore_errors=True)
    return out_dir / "vocal.wav", out_dir / "accomp.wav"


def _pick_stems(work: Path, vocal_key: str, accomp_key: str) -> tuple[Path, Path]:
    files = [p for p in work.rglob("*") if p.is_file() and p.suffix.lower() in config.AUDIO_EXTS]
    vocal = accomp = None
    for p in files:
        name = p.name.lower()
        if vocal_key in name:
            vocal = p
        elif accomp_key in name or "instrumental" in name:
            accomp = p
    if vocal is None or accomp is None:
        raise RuntimeError(f"分离输出不完整: {[p.name for p in files]}")
    return vocal, accomp


def _run_audio_separator(original: Path, out_dir: Path) -> tuple[Path, Path]:
    from audio_separator.separator import Separator  # type: ignore

    models_dir = config.DATA_DIR / "models"
    models_dir.mkdir(parents=True, exist_ok=True)
    work = out_dir / ".separator_tmp"
    shutil.rmtree(work, ignore_errors=True)
    work.mkdir(parents=True, exist_ok=True)

    sep = Separator(model_file_dir=str(models_dir), output_dir=str(work))
    sep.load_model(model_filename=MDX_MODEL)
    outputs = sep.separate(str(original))
    vocal_path, accomp_path = _pick_stems(work, "vocal", "instrumental")
    vocal_path.replace(out_dir / "vocal.wav")
    accomp_path.replace(out_dir / "accomp.wav")
    shutil.rmtree(work, ignore_errors=True)
    return out_dir / "vocal.wav", out_dir / "accomp.wav"


def _run_audio_separator_cli(original: Path, out_dir: Path) -> tuple[Path, Path]:
    work = out_dir / ".separator_tmp"
    shutil.rmtree(work, ignore_errors=True)
    work.mkdir(parents=True, exist_ok=True)
    exe = shutil.which("audio-separator") or None
    cmd = [sys.executable, "-m", "audio_separator", str(original), "-o", str(work),
           "--model_filename", MDX_MODEL]
    if exe:
        cmd = [exe, str(original), "-o", str(work), "--model_filename", MDX_MODEL]
    proc = subprocess.run(cmd, capture_output=True, text=True, timeout=3600)
    if proc.returncode != 0:
        raise RuntimeError(f"audio-separator 失败: {proc.stderr[-500:]}")
    vocal_path, accomp_path = _pick_stems(work, "vocal", "instrumental")
    vocal_path.replace(out_dir / "vocal.wav")
    accomp_path.replace(out_dir / "accomp.wav")
    shutil.rmtree(work, ignore_errors=True)
    return out_dir / "vocal.wav", out_dir / "accomp.wav"


_ENGINES = {
    "demucs": _run_demucs_python,
    "demucs-cli": _run_demucs_cli,
    "audio-separator": _run_audio_separator,
    "audio-separator-cli": _run_audio_separator_cli,
}


def separate_file(original: Path, out_dir: Path) -> tuple[Path, Path]:
    """把原曲分离为 vocal.wav / accomp.wav，返回两个文件路径。"""
    engine = get_engine()
    if engine is None:
        raise RuntimeError("未安装分离引擎，请执行 pip install -r requirements-separation.txt")
    out_dir.mkdir(parents=True, exist_ok=True)
    return _ENGINES[engine](original, out_dir)


# ---------------------------------------------------------------- 任务队列

def enqueue(song_id: int) -> None:
    """把歌曲的分离任务加入后台队列（并发 1，避免打满 CPU）。"""
    db.execute(
        "UPDATE songs SET separate_status = 'queued', separate_error = '' WHERE id = ?",
        (song_id,),
    )
    _executor.submit(_run_job, song_id)


def requeue(song_id: int) -> bool:
    """手动重新分离（需有原曲文件）。"""
    row = db.fetch_one("SELECT * FROM songs WHERE id = ?", (song_id,))
    if row is None or not row["original_file"]:
        return False
    enqueue(song_id)
    return True


def enqueue_pitch(song_id: int) -> None:
    """只为现有音轨生成参考旋律（手动上传人声时用）。"""
    db.execute(
        "UPDATE songs SET ref_status = 'queued', ref_error = '' WHERE id = ?",
        (song_id,),
    )
    _executor.submit(_run_pitch_job, song_id)


def _run_pitch_job(song_id: int) -> None:
    with _lock:
        row = db.fetch_one("SELECT * FROM songs WHERE id = ?", (song_id,))
        if row is None:
            return
        song_dir = config.SONG_UPLOAD_DIR / str(song_id)
        # 只以纯人声音轨为参考；没有分离人声时不生成（避免伴奏混入参考块）
        if row["vocal_file"] and (song_dir / row["vocal_file"]).is_file():
            _extract_reference(song_dir / row["vocal_file"], song_dir)


def _extract_reference(source: Path, song_dir: Path, accomp: Path | None = None) -> None:
    """人声 → 音符事件 JSON；状态写入 songs.ref_status，失败不吞错。"""
    song_id = song_dir.name
    db.execute("UPDATE songs SET ref_status = 'running', ref_error = '' WHERE id = ?", (song_id,))
    try:
        from . import transcribe

        transcribe.transcribe_file(
            source, song_dir, song_id=song_id, accomp_path=accomp
        )
        db.execute("UPDATE songs SET ref_status = 'done' WHERE id = ?", (song_id,))
        print(f"[selfktv] 歌曲 {song_id} 参考旋律转录完成")
    except Exception as exc:
        msg = str(exc)[:300]
        db.execute(
            "UPDATE songs SET ref_status = 'error', ref_error = ? WHERE id = ?",
            (msg, song_id),
        )
        print(f"[selfktv] 歌曲 {song_id} 参考旋律转录失败: {msg}")


def resume_pending() -> None:
    """服务重启后恢复未完成的分离任务。"""
    rows = db.fetch_all(
        "SELECT id FROM songs WHERE original_file IS NOT NULL AND original_file != '' "
        "AND separate_status IN ('queued', 'running')"
    )
    for row in rows:
        enqueue(row["id"])


def backfill_references() -> None:
    """存量歌曲自愈：有人声但缺参考旋律的，自动补转录。"""
    rows = db.fetch_all(
        "SELECT id FROM songs WHERE vocal_file IS NOT NULL AND vocal_file != ''"
    )
    queued = 0
    for row in rows:
        song_dir = config.SONG_UPLOAD_DIR / str(row["id"])
        if not (song_dir / "notes.json").is_file():
            enqueue_pitch(row["id"])
            queued += 1
    if queued:
        print(f"[selfktv] 发现 {queued} 首歌曲缺参考旋律，已加入转录队列")


def _run_job(song_id: int) -> None:
    with _lock:  # 保证同时只有一个分离任务在跑
        row = db.fetch_one("SELECT * FROM songs WHERE id = ?", (song_id,))
        if row is None or not row["original_file"]:
            return
        song_dir = config.SONG_UPLOAD_DIR / str(song_id)
        original = song_dir / row["original_file"]
        if not original.is_file():
            db.execute(
                "UPDATE songs SET separate_status = 'error', separate_error = '原曲文件缺失' WHERE id = ?",
                (song_id,),
            )
            return

        db.execute("UPDATE songs SET separate_status = 'running', separate_error = '' WHERE id = ?", (song_id,))
        try:
            vocal, accomp = separate_file(original, song_dir)
            duration = storage.probe_duration(vocal) or storage.probe_duration(original) or row["duration"]
            db.execute(
                "UPDATE songs SET vocal_file = ?, accomp_file = ?, duration = ?, "
                "separate_status = 'done', separate_error = '', updated_at = datetime('now') WHERE id = ?",
                (vocal.name, accomp.name, duration, song_id),
            )
            _extract_reference(vocal, song_dir, accomp)  # 转录参考旋律（音符事件）
        except Exception as exc:  # 分离失败不阻塞歌曲使用（仍可播放原曲）
            db.execute(
                "UPDATE songs SET separate_status = 'error', separate_error = ? WHERE id = ?",
                (str(exc)[:500], song_id),
            )
