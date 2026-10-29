"""作品混音导出：人声作品 + 伴奏 → 混合 WAV（服务端离线混合）。"""
from __future__ import annotations

import wave
from pathlib import Path

import numpy as np

from . import pitch


def _resample(x: np.ndarray, sr_from: int, sr_to: int) -> np.ndarray:
    if sr_from == sr_to or len(x) == 0:
        return x
    duration = len(x) / sr_from
    t_out = np.arange(int(duration * sr_to)) / sr_to
    t_in = np.arange(len(x)) / sr_from
    return np.interp(t_out, t_in, x).astype(np.float64)


def mix_work(
    work_path: Path,
    accomp_path: Path | None,
    out_path: Path,
    vocal_gain: float = 1.0,
    accomp_gain: float = 0.85,
) -> Path:
    """把作品（人声录音）与伴奏混合并写出 44.1kHz 单声道 PCM16 WAV。

    两轨均以各自文件 0 点为时间原点；不足部分补静音；峰值超限自动归一化防爆音。
    """
    sr, vocal = pitch._load_mono(work_path)
    vocal = vocal * float(vocal_gain)

    if accomp_path is not None and accomp_path.is_file():
        sr2, accomp = pitch._load_mono(accomp_path)
        accomp = _resample(accomp, sr2, sr)
        accomp = accomp * float(accomp_gain)
    else:
        accomp = np.zeros(0)

    n = max(len(vocal), len(accomp))
    mix = np.zeros(n, dtype=np.float64)
    mix[: len(vocal)] += vocal
    mix[: len(accomp)] += accomp

    peak = float(np.max(np.abs(mix))) if n else 0.0
    if peak > 0.98:
        mix *= 0.98 / peak

    pcm = (mix * 32767).astype("<i2")
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(out_path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sr)
        w.writeframes(pcm.tobytes())
    return out_path
