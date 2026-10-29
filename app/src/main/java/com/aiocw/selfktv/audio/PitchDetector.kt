package com.aiocw.selfktv.audio

import kotlin.math.abs

/**
 * 麦克风实时基频检测（归一化自相关法，与服务端参考音高同算法）。
 * 输入为 AudioRecord 采集的 PCM16 帧，输出 Hz（0 = 未检出/无声）。
 */
object PitchDetector {

    private const val F0_MIN = 60.0
    private const val F0_MAX = 1000.0

    /** 检测一帧的基频。frame 为 PCM16，n 为有效样本数。 */
    fun detect(frame: ShortArray, n: Int, sampleRate: Int): Float {
        if (n < 256) return 0f

        // 2 倍抽取降算量（等效 22.05kHz）
        val m = n / 2
        val x = FloatArray(m)
        var energy = 0f
        for (i in 0 until m) {
            val v = (frame[2 * i].toFloat() + frame[2 * i + 1].toFloat()) * 0.5f
            x[i] = v
            energy += v * v
        }
        if (energy / m < 25f) return 0f  // 约 -50dBFS 以下视为静音

        var mean = 0f
        for (v in x) mean += v
        mean /= m
        for (i in 0 until m) x[i] -= mean

        val sr = sampleRate / 2
        val lagMin = (sr / F0_MAX).toInt().coerceAtLeast(2)
        val lagMax = (sr / F0_MIN).toInt().coerceAtMost(m - 2)
        if (lagMax <= lagMin) return 0f

        // 归一化自相关 r[lag]
        var r0 = 0f
        for (v in x) r0 += v * v
        if (r0 <= 0f) return 0f

        var bestLag = 0
        var bestVal = 0.30f
        var prev = Float.MAX_VALUE
        for (lag in lagMin..lagMax) {
            var r = 0f
            for (i in 0 until m - lag) r += x[i] * x[i + lag]
            r /= r0
            if (r > bestVal && r >= prev) {
                // 简单峰值判断：下一个值也小才算峰
                var next = -1f
                if (lag + 1 <= lagMax) {
                    var rn = 0f
                    for (i in 0 until m - lag - 1) rn += x[i] * x[i + lag + 1]
                    next = rn / r0
                }
                if (next <= r) {
                    bestLag = lag
                    bestVal = r
                    break
                }
            }
            prev = r
        }
        if (bestLag == 0) return 0f

        val f0 = sr.toFloat() / bestLag
        return if (f0 in F0_MIN.toFloat()..F0_MAX.toFloat()) f0 else 0f
    }

    /** 音分差：100 cents = 一个半音。 */
    fun centsBetween(micHz: Float, refHz: Float): Float {
        if (micHz <= 0f || refHz <= 0f) return Float.MAX_VALUE
        return (1200.0 * (kotlin.math.ln(micHz / refHz) / kotlin.math.ln(2.0))).toFloat()
    }

    /** 音名（如 A3、C#4）。 */
    fun noteName(hz: Float): String {
        if (hz <= 0f) return "-"
        val names = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        val midi = (12.0 * kotlin.math.ln(hz / 440.0) / kotlin.math.ln(2.0) + 69.0).toInt()
        val octave = midi / 12 - 1
        return "${names[((midi % 12) + 12) % 12]}$octave"
    }

    /** 是否接近（±toleranceCents）。 */
    fun isHit(micHz: Float, refHz: Float, toleranceCents: Float = 80f): Boolean =
        abs(centsBetween(micHz, refHz)) <= toleranceCents
}
