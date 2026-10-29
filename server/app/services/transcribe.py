"""参考旋律转录：人声 WAV → 结构化音符事件 JSON。

引擎优先级（自动探测，失败自动降级）：
  1. basic-pitch（Spotify，神经网络复音转录）→ 直接输出音符事件
  2. librosa-yin（FFT-YIN 基频跟踪 + 音符切分；比 pyin 快一个量级，整曲秒级）
  3. autocorr（内置自相关切分，零依赖兜底）

统一输出 schema（面向渲染与比对）：
    {
      "songId": "3", "version": 2, "engine": "pyin",
      "notes": [{"t": 12.34, "d": 0.48, "m": 64, "c": 0.82}, ...]
    }
    t=起始秒（相对伴奏零时刻）  d=时长秒  m=MIDI 音高  c=置信度 0~1

工程规则：
  - 最低置信度 0.3 兜底（气声/弱混伪音符剔除）
  - 最短音符 60ms（噪声碎片剔除）
  - 与伴奏做包络互相关对齐校正（保证 t 与伴奏共享时间原点）
  - 转录任务信号量串行（保护 CPU/GPU），结果持久化缓存，不重复推理
"""
from __future__ import annotations

import json
import threading
import time
import uuid
from pathlib import Path

import numpy as np

from . import pitch

SCHEMA_VERSION = 2
MIN_NOTE_MS = 60
MIN_CONF = 0.3
MERGE_GAP_MS = 60

# 引擎探测缓存
_engine_checked = False
_engine_name = "autocorr"
_last_error = ""


def last_error() -> str:
    return _last_error


def _set_error(msg: str) -> None:
    global _last_error
    _last_error = msg[:500]

# 异步任务（串行执行，避免多任务打爆资源）
_sem = threading.Semaphore(1)
_jobs: dict[str, dict] = {}


# ================================================================ 引擎

def get_engine() -> str:
    global _engine_checked, _engine_name
    if _engine_checked:
        return _engine_name
    _engine_checked = True
    try:
        import basic_pitch.inference  # noqa: F401

        _engine_name = "basic-pitch"
    except Exception:
        try:
            import librosa  # noqa: F401

            _engine_name = "librosa-yin"
        except Exception:
            _engine_name = "autocorr"
    return _engine_name


def _transcribe(path: Path) -> tuple[list[dict], str]:
    """按优先级尝试各引擎，返回 (notes, engine_name)。"""
    try:
        return _via_basic_pitch(path), "basic-pitch"
    except Exception:
        pass
    try:
        return _via_librosa(path), "librosa-yin"
    except Exception:
        pass
    return _via_autocorr(path), "autocorr"


def _via_basic_pitch(path: Path) -> list[dict]:
    from basic_pitch.inference import predict  # type: ignore

    _, midi_data, _ = predict(
        str(path),
        onset_threshold=0.4,
        frame_threshold=0.3,
        minimum_note_length=MIN_NOTE_MS,
        melodia_trick=True,
    )
    notes: list[dict] = []
    for inst in midi_data.instruments:
        for n in inst.notes:
            notes.append(
                {
                    "t": round(float(n.start), 3),
                    "d": round(float(n.end - n.start), 3),
                    "m": int(n.pitch),
                    "c": round(float(getattr(n, "velocity", 80)) / 127.0, 3) or 0.8,
                }
            )
    return notes


def _via_librosa(path: Path) -> list[dict]:
    """FFT-YIN（快） + 能量门限 + 音符切分；整曲秒级完成。"""
    import librosa  # type: ignore

    y, sr = librosa.load(str(path), sr=16000, mono=True)
    if len(y) < sr // 4:
        return []
    hop = 320                      # 20ms 帧
    frame = 1024
    f0 = librosa.yin(y, fmin=60.0, fmax=1000.0, sr=sr, hop_length=hop, frame_length=frame)
    rms = librosa.feature.rms(y=y, hop_length=hop, frame_length=frame)[0]
    n = min(len(f0), len(rms))
    peak = float(np.max(rms)) if n else 0.0
    gate = max(0.01, peak * 0.08)     # 能量门限：静音/气口不入音符
    frames = []
    for i in range(n):
        if rms[i] >= gate and np.isfinite(f0[i]) and f0[i] > 0:
            conf = min(1.0, 0.55 + 0.45 * float(rms[i]) / (peak + 1e-9))
            frames.append((i * hop / sr, _hz_to_midi(float(f0[i])), conf))
        else:
            frames.append((i * hop / sr, -1.0, 0.0))
    return _frames_to_notes(frames)


def _via_autocorr(path: Path) -> list[dict]:
    sr, x = pitch._load_mono(path)
    hop = 1024
    frames = []
    for start in range(0, max(0, len(x) - 2048 + 1), hop):
        hz, conf = _detect_conf(x[start : start + 2048], sr)
        t = start / sr
        frames.append((t, _hz_to_midi(hz) if hz > 0 else -1.0, conf))
    return _frames_to_notes(frames)


def _detect_conf(frame: np.ndarray, sr: int) -> tuple[float, float]:
    """自相关基频 + 置信度（归一化峰值强度）。"""
    if np.max(np.abs(frame)) < 0.01:
        return 0.0, 0.0
    x = frame - frame.mean()
    x = x * np.hanning(len(x))
    n = 1
    while n < 2 * len(x):
        n *= 2
    spec = np.fft.rfft(x, n)
    ac = np.fft.irfft(spec * np.conj(spec))[: len(x)]
    if ac[0] <= 0:
        return 0.0, 0.0
    ac = ac / ac[0]
    lag_min = max(2, int(sr / 1000.0))
    lag_max = min(len(ac) - 2, int(sr / 60.0))
    best_lag, best_val = 0, 0.3
    for lag in range(lag_min, lag_max):
        if ac[lag] > best_val and ac[lag] >= ac[lag - 1] and ac[lag] >= ac[lag + 1]:
            best_lag, best_val = lag, float(ac[lag])
            break
    if best_lag == 0:
        return 0.0, 0.0
    return float(sr / best_lag), min(1.0, best_val)


def _hz_to_midi(hz: float) -> float:
    return float(12.0 * np.log2(hz / 440.0) + 69.0)


# ================================================================ 帧 → 音符

def _frames_to_notes(frames: list[tuple[float, float, float]]) -> list[dict]:
    """帧序列 (t, midi|-1, conf) → 音符事件（半音量化 + 合并 + 过滤）。"""
    notes: list[dict] = []
    cur: dict | None = None
    for t, midi, conf in frames:
        if midi < 0:
            if cur is not None:
                notes.append(cur)
                cur = None
            continue
        quant = round(midi)
        if cur is None:
            cur = {"t": t, "end": t, "m": quant, "conf_sum": conf, "n": 1}
        elif abs(quant - cur["m"]) <= 0 and t - cur["end"] <= MERGE_GAP_MS / 1000.0:
            cur["end"] = t
            cur["conf_sum"] += conf
            cur["n"] += 1
        else:
            notes.append(cur)
            cur = {"t": t, "end": t, "m": quant, "conf_sum": conf, "n": 1}
    if cur is not None:
        notes.append(cur)

    out = []
    for n in notes:
        d = n["end"] - n["t"] + 0.02
        c = n["conf_sum"] / max(1, n["n"])
        if d * 1000 < MIN_NOTE_MS or c < MIN_CONF:
            continue
        out.append({"t": round(n["t"], 3), "d": round(d, 3), "m": int(n["m"]), "c": round(min(c, 1.0), 3)})
    out.sort(key=lambda n: n["t"])
    return out


# ================================================================ 时间原点对齐

def _align_offset(vocal_path: Path, accomp_path: Path | None) -> float:
    """人声/伴奏包络互相关，返回人声相对伴奏的时间偏移秒（用于校正 t）。

    两轨若由同一原曲分离而来通常为 0；手动上传错位时才起作用。
    仅在相关峰足够强且偏移明显时才校正。
    """
    if accomp_path is None or not accomp_path.is_file():
        return 0.0
    try:
        sr1, v = pitch._load_mono(vocal_path)
        sr2, a = pitch._load_mono(accomp_path)
        rate = 100  # 包络采样率 100Hz
        hop1 = max(1, sr1 // rate)
        hop2 = max(1, sr2 // rate)
        ve = np.abs(v[: (len(v) // hop1) * hop1].reshape(-1, hop1)).mean(axis=1)
        ae = np.abs(a[: (len(a) // hop2) * hop2].reshape(-1, hop2)).mean(axis=1)
        n = min(len(ve), len(ae), rate * 30)  # 最多取 30 秒
        if n < rate:
            return 0.0
        ve = ve[:n] - ve[:n].mean()
        ae = ae[:n] - ae[:n].mean()
        corr = np.correlate(ve, ae, mode="full")
        lags = np.arange(-n + 1, n)
        mask = np.abs(lags) <= rate // 4  # ±250ms
        best = int(np.argmax(corr[mask]))
        lag = int(lags[mask][best])
        strength = float(corr[mask][best] / (np.linalg.norm(ve) * np.linalg.norm(ae) + 1e-9))
        if strength >= 0.3 and abs(lag) >= 3:  # ≥30ms 才校正
            return -lag / rate
    except Exception:
        pass
    return 0.0


# ================================================================ 对外接口

def transcribe_file(
    vocal_path: Path,
    out_dir: Path,
    song_id: str = "",
    accomp_path: Path | None = None,
) -> dict:
    """转录人声 → notes.json（并生成旧版 pitch.json 以兼容）。"""
    global _last_error
    try:
        notes, engine = _transcribe(vocal_path)
    except Exception as exc:
        _set_error(f"转录失败: {exc}")
        raise
    _last_error = ""
    offset = _align_offset(vocal_path, accomp_path)
    if offset:
        for n in notes:
            n["t"] = round(max(0.0, n["t"] + offset), 3)
    notes = [n for n in notes if n["c"] >= MIN_CONF and n["d"] * 1000 >= MIN_NOTE_MS]

    data = {
        "songId": str(song_id),
        "version": SCHEMA_VERSION,
        "engine": engine,
        "notes": notes,
    }
    (out_dir / "notes.json").write_text(json.dumps(data), encoding="utf-8")

    # 兼容旧客户端：按固定栅格展开为 f0 序列
    _write_legacy_f0(notes, out_dir / "pitch.json")
    return data


def load_notes(path: Path) -> dict | None:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return None


def _write_legacy_f0(notes: list[dict], out_path: Path) -> None:
    hop_ms = 23.22
    if not notes:
        out_path.write_text(json.dumps({"hop_ms": hop_ms, "f0": [], "version": SCHEMA_VERSION}))
        return
    total_ms = max(n["t"] * 1000 + n["d"] * 1000 for n in notes) + 500
    frames = int(total_ms / hop_ms) + 1
    f0 = [0] * frames
    for n in notes:
        a = int(n["t"] * 1000 / hop_ms)
        b = int((n["t"] + n["d"]) * 1000 / hop_ms)
        hz = round(440.0 * 2 ** ((n["m"] - 69) / 12.0))
        for i in range(a, min(b + 1, frames)):
            f0[i] = hz
    out_path.write_text(json.dumps({"hop_ms": hop_ms, "f0": f0, "version": SCHEMA_VERSION}))


# ================================================================ 异步任务（POST /transcribe → jobId）

def submit_job(song_id: int, fn) -> str:
    job_id = "job_" + uuid.uuid4().hex[:12]
    _jobs[job_id] = {"jobId": job_id, "songId": song_id, "status": "queued", "result": None, "error": ""}

    def runner():
        with _sem:
            _jobs[job_id]["status"] = "running"
            try:
                _jobs[job_id]["result"] = fn()
                _jobs[job_id]["status"] = "done"
            except Exception as exc:
                _set_error(str(exc))
                _jobs[job_id]["status"] = "error"
                _jobs[job_id]["error"] = str(exc)[:300]

    threading.Thread(target=runner, name=f"transcribe-{job_id}", daemon=True).start()
    return job_id


def get_job(job_id: str) -> dict | None:
    return _jobs.get(job_id)
