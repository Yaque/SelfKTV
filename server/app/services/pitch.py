"""参考音高（旋律轮廓）提取 —— 用于 K 歌时的音准器与打分。

对人声轨做逐帧基频检测（自相关法），输出 pitch.json：
    {"hop_ms": 46.4, "f0": [0, 220, 221, ...]}   # 0 表示该帧无声/未检出

客户端把麦克风实时音高与该轮廓逐帧对比，偏差小于阈值即记为命中，
命中率即「音准」得分（模仿全民K歌）。
"""
from __future__ import annotations

import json
import math
from pathlib import Path

import numpy as np

FRAME = 2048
HOP = 1024
F0_MIN = 60.0   # Hz
F0_MAX = 1000.0


def _load_mono(path: Path) -> tuple[int, np.ndarray]:
    """读取音频为单声道 float 数组，返回 (采样率, 数据)。"""
    try:
        import torchaudio

        wav, sr = torchaudio.load(str(path))
        return int(sr), wav.mean(dim=0).numpy().astype(np.float64)
    except Exception:
        pass
    try:
        import soundfile as sf  # type: ignore

        data, sr = sf.read(str(path), dtype="float64", always_2d=True)
        return int(sr), data.mean(axis=1)
    except Exception:
        pass
    import wave

    with wave.open(str(path), "rb") as w:
        sr = w.getframerate()
        n = w.getnframes()
        raw = w.readframes(n)
        width = w.getsampwidth()
        ch = w.getnchannels()
    if width == 2:
        x = np.frombuffer(raw, dtype="<i2").astype(np.float64) / 32768.0
    elif width == 4:
        x = np.frombuffer(raw, dtype="<i4").astype(np.float64) / 2147483648.0
    else:
        raise ValueError(f"不支持的采样位宽: {width}")
    return sr, x.reshape(-1, ch).mean(axis=1)


def detect_frame(frame: np.ndarray, sr: int) -> float:
    """自相关基频检测，返回 Hz（0 = 无声/未检出）。"""
    if np.max(np.abs(frame)) < 0.01:
        return 0.0
    x = frame - frame.mean()
    w = np.hanning(len(x))
    x = x * w

    n = 1
    while n < 2 * len(x):
        n *= 2
    spec = np.fft.rfft(x, n)
    ac = np.fft.irfft(spec * np.conj(spec))[: len(x)]
    if ac[0] <= 0:
        return 0.0
    ac = ac / ac[0]

    lag_min = max(2, int(sr / F0_MAX))
    lag_max = min(len(ac) - 2, int(sr / F0_MIN))
    if lag_max <= lag_min:
        return 0.0

    # 找第一个显著峰值（归一化自相关 > 0.45，抬高门槛剔除伴奏/分离残留）
    best_lag, best_val = 0, 0.45
    for lag in range(lag_min, lag_max):
        if ac[lag] > best_val and ac[lag] >= ac[lag - 1] and ac[lag] >= ac[lag + 1]:
            best_lag, best_val = lag, ac[lag]
            break
    if best_lag == 0:
        return 0.0

    # 抛物线插值细化
    y0, y1, y2 = ac[best_lag - 1], ac[best_lag], ac[best_lag + 1]
    denom = y0 - 2 * y1 + y2
    shift = 0.5 * (y0 - y2) / denom if denom != 0 else 0.0
    lag = best_lag + float(np.clip(shift, -1, 1))
    return float(sr / lag)


def extract_pitch(audio_path: Path, out_path: Path) -> dict:
    """提取整曲音高轮廓并写入 pitch.json，返回结果字典。

    只保留有旋律意义的长片段：孤立短帧（气口、分离残留、伴奏闪现）置 0。
    """
    sr, x = _load_mono(audio_path)
    hop_ms = HOP / sr * 1000.0
    f0_list: list[int] = []
    if len(x) < FRAME:
        f0_list = [0]
    else:
        for start in range(0, len(x) - FRAME + 1, HOP):
            f0 = detect_frame(x[start : start + FRAME], sr)
            f0_list.append(int(round(f0)))

    # 剔除 < 150ms 的短促发声段（约 6 帧）：多半是伴奏/噪声，不是纯人声旋律
    min_run = max(3, int(150 / hop_ms))
    i = 0
    while i < len(f0_list):
        if f0_list[i] <= 0:
            i += 1
            continue
        j = i
        while j < len(f0_list) and f0_list[j] > 0:
            j += 1
        if j - i < min_run:
            for k in range(i, j):
                f0_list[k] = 0
        i = j

    result = {"hop_ms": round(hop_ms, 2), "f0": f0_list, "sr": sr, "version": 2}
    out_path.write_text(json.dumps(result), encoding="utf-8")
    return result


def load_pitch(path: Path) -> dict | None:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return None


def cents_diff(mic_hz: float, ref_hz: float) -> float:
    """两个音高之间的音分差（100 cents = 半音）。"""
    if mic_hz <= 0 or ref_hz <= 0:
        return math.inf
    return 1200.0 * math.log2(mic_hz / ref_hz)
