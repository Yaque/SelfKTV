package com.aiocw.selfktv.audio

/** LRC 歌词解析。 */
object LrcParser {

    data class Line(val timeMs: Long, val text: String)

    private val TAG = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,4}))?]")

    /** 解析 LRC 文本，返回按时间排序的歌词行。
     *  若歌词没有时间标签（纯文本），在给定 fallbackDurationMs 时按行均匀分配时间。 */
    fun parse(content: String?, fallbackDurationMs: Long = 0): List<Line> {
        if (content.isNullOrBlank()) return emptyList()
        // 兼容各种换行（\r\n / \r / \u2028 / 字面量 \n），避免整份歌词被当成一行
        val normalized = content
            .replace("\u2028", "\n").replace("\u2029", "\n")
            .replace("\\n", "\n")
        val result = ArrayList<Line>()
        for (raw in normalized.lineSequence()) {
            val tags = TAG.findAll(raw).toList()
            if (tags.isEmpty()) continue
            val text = TAG.replace(raw, "").trim()
            for (m in tags) {
                val min = m.groupValues[1].toLongOrNull() ?: continue
                val sec = m.groupValues[2].toLongOrNull() ?: continue
                val frac = m.groupValues[3]
                val ms = when {
                    frac.isEmpty() -> 0L
                    frac.length == 1 -> frac.toLong() * 100
                    frac.length == 2 -> frac.toLong() * 10
                    else -> frac.take(3).toLong()
                }
                result.add(Line(min * 60_000 + sec * 1000 + ms, text))
            }
        }
        if (result.isNotEmpty()) return result.sortedBy { it.timeMs }

        // 纯文本歌词（如音频内嵌歌词）：均匀分布到整首歌
        val plain = normalized.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (plain.isEmpty() || fallbackDurationMs <= 0) return emptyList()
        val gap = fallbackDurationMs / (plain.size + 1)
        return plain.mapIndexed { i, t -> Line(gap * (i + 1), t) }
    }

    /** 当前时间对应的歌词行下标（-1 表示还没开始）。 */
    fun indexAt(lines: List<Line>, timeMs: Long): Int {
        var idx = -1
        for (i in lines.indices) {
            if (lines[i].timeMs <= timeMs) idx = i else break
        }
        return idx
    }
}
