package com.aiocw.selfktv.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.aiocw.selfktv.audio.LrcParser
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 滚动歌词控件（模仿全民K歌：当前行高亮居中，上下行淡显，随时间平滑滚动）。
 * 浅色卡片背景：当前行墨绿色加粗，其余行深灰（多行可见），避免白底白字只显示一行。
 */
class LyricView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var lines: List<LrcParser.Line> = emptyList()
    private var currentIndex = -1
    private var offset = 0f // 0..1 当前行到下一行的滚动进度

    /** 点击某行歌词（用于跳转进度）。 */
    var onLineTap: ((LrcParser.Line) -> Unit)? = null

    private val density = context.resources.displayMetrics.density
    private val lineGap = 16f * density
    private val currentSize = 18f * density
    private val otherSize = 14f * density

    /**
     * 行距自适应：空间不足时压缩行距，保证任何屏幕上都至少能看到多行歌词，
     * 避免歌词区被挤成一行。
     */
    private fun stepPx(): Float =
        max(currentSize * 1.35f, min(lineGap + currentSize, height / 3.5f))

    private val paintCurrent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (nightMode()) Color.parseColor("#3CE8A0") else Color.parseColor("#0E8F5E")
        textSize = currentSize
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val paintOther = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (nightMode()) Color.parseColor("#A6B0BE") else Color.parseColor("#5B6472")
        textSize = otherSize
        textAlign = Paint.Align.CENTER
    }
    private val paintHint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (nightMode()) Color.parseColor("#6B7686") else Color.parseColor("#8A93A3")
        textSize = 16f * density
        textAlign = Paint.Align.CENTER
    }

    /** 夜间模式下用亮色歌词（深色卡片背景）。 */
    private fun nightMode(): Boolean =
        (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    private val bounds = Rect()

    fun setLyrics(newLines: List<LrcParser.Line>) {
        lines = newLines
        currentIndex = -1
        offset = 0f
        invalidate()
    }

    /** 传入当前播放进度（毫秒），驱动歌词滚动。 */
    fun update(timeMs: Long) {
        if (lines.isEmpty()) return
        val idx = LrcParser.indexAt(lines, timeMs)
        val start = if (idx >= 0) lines[idx].timeMs else 0
        val next = if (idx + 1 < lines.size) lines[idx + 1].timeMs else start + 4000
        val span = (next - start).coerceAtLeast(1)
        offset = ((timeMs - start).toFloat() / span).coerceIn(0f, 1f)
        if (idx != currentIndex) {
            currentIndex = idx
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (lines.isEmpty()) {
            canvas.drawText("暂无歌词", width / 2f, height / 2f, paintHint)
            return
        }
        val centerY = height / 2f
        val step = stepPx()
        val scrollY = offset * step

        for (i in lines.indices) {
            val isCurrent = i == currentIndex
            val paint = if (isCurrent) paintCurrent else paintOther
            val y = centerY + (i - currentIndex) * step - scrollY
            if (y < -step || y > height + step) continue
            val text = lines[i].text.ifEmpty { "···" }
            paint.getTextBounds(text, 0, text.length, bounds)
            canvas.drawText(text, width / 2f, y + bounds.height() / 2f, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP && lines.isNotEmpty()) {
            val centerY = height / 2f
            val step = stepPx()
            val scrollY = offset * step
            val tapped = currentIndex + ((centerY - event.y + scrollY) / step).toInt()
            if (tapped in lines.indices && abs(event.y - centerY) < height) {
                onLineTap?.invoke(lines[tapped])
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()
}
