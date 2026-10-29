package com.aiocw.selfktv.audio

import com.aiocw.selfktv.net.RefNote
import kotlin.math.abs

/**
 * 音准打分器（音符事件版）：
 *  - 输入为服务端转录的参考旋律 notes（t/d/m/c）
 *  - 局部容差匹配（DTW-lite）：±200ms 窗口内取音高最接近的参考音符，
 *    吸收「唱对了但略拖拍/抢拍」的时间偏差
 *  - 低置信度（c 低）的参考音符自动放宽容差
 */
class PitchScore(private val notes: List<RefNote>) {

    companion object {
        private const val TIME_TOL_MS = 200L
    }

    private var compared = 0
    private var hits = 0

    /** 当前得分 0..100 */
    val score: Int
        get() = if (compared == 0) 0 else (hits * 100f / compared).toInt().coerceIn(0, 100)

    val frames: Int get() = compared

    val grade: String
        get() = when {
            compared == 0 -> "-"
            score >= 92 -> "S"
            score >= 84 -> "A"
            score >= 72 -> "B"
            score >= 60 -> "C"
            else -> "D"
        }

    /** 该时刻的参考音符（容差窗口内最近者）。 */
    fun noteAt(timeMs: Long): RefNote? {
        var best: RefNote? = null
        var bestDist = Long.MAX_VALUE
        for (n in notes) {
            val dist = when {
                timeMs < n.startMs -> n.startMs - timeMs
                timeMs > n.endMs -> timeMs - n.endMs
                else -> 0L
            }
            if (dist <= TIME_TOL_MS && dist < bestDist) {
                best = n
                bestDist = dist
            }
        }
        return best
    }

    /** 参考音高（Hz，0 = 此处无旋律），供音准条使用。 */
    fun refAt(timeMs: Long): Float {
        val n = noteAt(timeMs) ?: return 0f
        return (440.0 * Math.pow(2.0, (n.m - 69) / 12.0)).toFloat()
    }

    /** 喂入一帧检测结果；返回是否命中（null = 不计入）。 */
    fun feed(timeMs: Long, micHz: Float): Boolean? {
        if (micHz <= 0f) return null
        val micMidi = midiOf(micHz)
        // DTW-lite：容差窗口内取「音高最接近 + 时间最近」的参考音符
        var best: RefNote? = null
        var bestCost = Float.MAX_VALUE
        for (n in notes) {
            val timeDist = when {
                timeMs < n.startMs -> n.startMs - timeMs
                timeMs > n.endMs -> timeMs - n.endMs
                else -> 0L
            }
            if (timeDist > TIME_TOL_MS) continue
            val cost = abs(n.midi - micMidi) + timeDist / 400f
            if (cost < bestCost) {
                bestCost = cost
                best = n
            }
        }
        val note = best ?: return null
        val diff = abs(note.midi - micMidi)
        if (diff > 1.5f) return null            // 差太远视为没在跟唱，不计入
        val tol = if (note.c < 0.5f) 1.0f else 0.7f   // 低置信度参考放宽容差
        compared++
        val hit = diff <= tol
        if (hit) hits++
        return hit
    }

    private fun midiOf(hz: Float): Float =
        (12.0 * kotlin.math.ln(hz / 440.0) / kotlin.math.ln(2.0) + 69.0).toFloat()
}
