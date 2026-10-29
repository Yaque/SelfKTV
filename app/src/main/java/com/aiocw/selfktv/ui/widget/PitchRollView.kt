package com.aiocw.selfktv.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.aiocw.selfktv.net.RefNote
import kotlin.math.abs

/**
 * 块状音准提示器（回森风格）：
 *  - 静态离散短块：半透明白灰、极矮、小圆角、块间留缝；句内不滚动
 *  - 唱过 / 正在唱的块变纯白高亮，未唱保持暗淡
 *  - 纯白发光圆点 = 你的实时音高，随进度横向移动；唱对时正好压在块上
 */
class PitchRollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    /** 纯人声参考块（含置信度） */
    private class Seg(val startMs: Long, val endMs: Long, val midi: Float, val conf: Float)

    private var segments: List<Seg> = emptyList()
    private var minMidi = 55f
    private var maxMidi = 79f

    private var currentTimeMs = 0L
    private var currentMicHz = 0f

    // 圆点平滑跟随（绘制帧插值）
    private var dotX = Float.NaN
    private var dotY = Float.NaN
    private var dotTX = 0f
    private var dotTY = 0f

    private var phraseStartMs = -1L
    private var phraseEndMs = -1L

    private val density = context.resources.displayMetrics.density
    private val blockHeight = 8f * density          // 极矮短条
    private val corner = 2f * density               // 近直角微圆
    private val gapInset = 5f * density             // 块间缝隙
    private val minBlockWidth = 20f * density

    private val paintFuture = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8CFFFFFF")       // 未唱：半透明白灰
    }
    private val paintSung = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFFFF")       // 唱过/正在唱：纯白高亮
    }
    private val paintDynamic = Paint(Paint.ANTI_ALIAS_FLAG)   // 渐亮过渡用
    private val paintGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1FFFFFFF")       // 当前块光晕
    }
    private val paintDotGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33FFFFFF")       // 圆点光晕
    }
    private val paintDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFFFF")       // 纯白实心圆点
    }
    private val rect = RectF()

    /** 服务端转录的参考旋律（音符事件）→ 音准块。 */
    fun setNotes(notes: List<RefNote>) {
        segments = notes
            .filter { it.d * 1000f >= 60f }
            .map { Seg(it.startMs, it.endMs, it.midi, it.c.coerceIn(0f, 1f)) }
        if (segments.isNotEmpty()) {
            minMidi = segments.minOf { it.midi } - 2f
            maxMidi = segments.maxOf { it.midi } + 2f
        }
        invalidate()
    }

    /** 当前练习句窗口：句内块静态铺满，不滚动。 */
    fun setPhraseWindow(startMs: Long, endMs: Long) {
        if (startMs == phraseStartMs && endMs == phraseEndMs) return
        phraseStartMs = startMs
        phraseEndMs = endMs
        invalidate()
    }

    /** 每帧更新：时间推进 + 你的实时音高（圆点目标位置）。 */
    fun update(timeMs: Long, micHz: Float, hit: Boolean? = null) {
        currentTimeMs = timeMs
        currentMicHz = micHz
        invalidate()
    }

    fun resetTrail() {
        invalidate()
    }

    private fun midiOf(hz: Float): Float =
        (12.0 * kotlin.math.ln(hz / 440.0) / kotlin.math.ln(2.0) + 69.0).toFloat()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (segments.isEmpty()) return

        val usePhrase = phraseStartMs >= 0 && phraseEndMs > phraseStartMs
        val spanMs = if (usePhrase) (phraseEndMs - phraseStartMs) else 6000L
        val baseMs = if (usePhrase) {
            phraseStartMs
        } else {
            // 滚动模式（演唱页）：「当前时刻」位于左侧 1/4，块向左滚过
            currentTimeMs - (spanMs * 0.25f).toLong()
        }
        fun xOf(tMs: Long): Float =
            (tMs - baseMs).toFloat() / spanMs * w     // 静态映射：块不动

        val midiRange = (maxMidi - minMidi).coerceAtLeast(1f)
        fun yOf(midi: Float): Float = h - (midi - minMidi) * (h / midiRange)

        // 参考短块（离散、留缝；渐亮 → 纯白高亮，当前块带光晕）
        for (seg in segments) {
            if (usePhrase && (seg.endMs <= phraseStartMs || seg.startMs >= phraseEndMs)) continue
            val cx1 = xOf(seg.startMs) + gapInset
            val cx2 = xOf(seg.endMs) - gapInset
            if (cx2 < -10 || cx1 > w + 10) continue
            val width = (cx2 - cx1).coerceAtLeast(minBlockWidth)
            val cx = (cx1 + cx2) / 2f
            val cy = yOf(seg.midi)
            val inBlock = currentTimeMs >= seg.startMs && currentTimeMs <= seg.endMs
            // 亮度：已唱/正在唱 → 高亮；临近 → 渐亮；未唱 → 暗；低置信度块整体弱化
            val baseAlpha = when {
                inBlock || currentTimeMs > seg.endMs -> 200 + 55f * seg.conf
                seg.startMs - currentTimeMs < 400 -> {
                    val t = 1f - (seg.startMs - currentTimeMs) / 400f
                    val from = 120f + 70f * seg.conf   // 未唱亮度
                    val to = 200f + 55f * seg.conf     // 唱时亮度
                    from + (to - from) * t
                }
                else -> 120 + 70f * seg.conf
            }
            paintDynamic.color = (baseAlpha.toInt().coerceIn(0, 255) shl 24) or 0x00FFFFFF
            if (inBlock) {
                rect.set(cx - width / 2 - 6f, cy - blockHeight * 1.6f, cx + width / 2 + 6f, cy + blockHeight * 1.6f)
                canvas.drawRoundRect(rect, corner * 2, corner * 2, paintGlow)
            }
            rect.set(cx - width / 2, cy - blockHeight / 2, cx + width / 2, cy + blockHeight / 2)
            canvas.drawRoundRect(rect, corner, corner, paintDynamic)
        }

        // 纯白发光圆点 = 你的实时音高（逐帧插值滑向目标，60fps 跟手）
        if (currentMicHz > 0f && currentTimeMs > 0) {
            val midiRange2 = (maxMidi - minMidi).coerceAtLeast(1f)
            dotTX = xOf(currentTimeMs)
            dotTY = h - (midiOf(currentMicHz) - minMidi) * (h / midiRange2)
            if (dotX.isNaN()) {
                dotX = dotTX; dotY = dotTY
            }
            dotX += (dotTX - dotX) * 0.35f
            dotY += (dotTY - dotY) * 0.35f
            canvas.drawCircle(dotX, dotY, 11f * density, paintDotGlow)
            canvas.drawCircle(dotX, dotY, 6.5f * density, paintDot)
            if (abs(dotTX - dotX) > 0.5f || abs(dotTY - dotY) > 0.5f) {
                postInvalidateOnAnimation()   // 未到位则继续插值
            }
        } else {
            dotX = Float.NaN
        }
    }
}
