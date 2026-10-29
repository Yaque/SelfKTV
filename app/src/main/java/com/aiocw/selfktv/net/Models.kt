package com.aiocw.selfktv.net

import org.json.JSONObject

/**
 * 歌曲（服务端 /api/songs 返回结构）。
 *
 * 演唱模式（mode）：
 *  - mix    原唱：人声 + 伴奏 一起播放（等同原曲）
 *  - vocal  人声：只听人声
 *  - accomp 伴奏：只听伴奏
 *  - smart  智能：开口唱自动消人声，停唱人声接上
 */
data class Song(
    val id: Int,
    val title: String,
    val artist: String,
    val duration: Double,
    val hasVocal: Boolean,
    val hasAccomp: Boolean,
    val hasOriginal: Boolean,
    val hasLyric: Boolean,
    val hasCover: Boolean,
    val smartAvailable: Boolean,
    val separating: Boolean,
    val hasPitch: Boolean,
    val coverUrl: String?,
    val lyricUrl: String?,
    val vocalUrl: String?,
    val accompUrl: String?,
    val originalUrl: String?
) {
    companion object {
        const val MODE_MIX = "mix"
        const val MODE_VOCAL = "vocal"
        const val MODE_ACCOMP = "accomp"
        const val MODE_SMART = "smart"

        fun fromJson(o: JSONObject): Song {
            val tracks = o.optJSONObject("tracks") ?: JSONObject()
            return Song(
                id = o.optInt("id"),
                title = o.optString("title", "未知歌曲"),
                artist = o.optString("artist", ""),
                duration = o.optDouble("duration", 0.0),
                hasVocal = o.optBoolean("has_vocal"),
                hasAccomp = o.optBoolean("has_accomp"),
                hasOriginal = o.optBoolean("has_original"),
                hasLyric = o.optBoolean("has_lyric"),
                hasCover = o.optBoolean("has_cover"),
                smartAvailable = o.optBoolean("smart_available"),
                separating = o.optBoolean("separating"),
                hasPitch = o.optBoolean("has_pitch"),
                coverUrl = o.optString("cover_url", "").ifEmpty { null },
                lyricUrl = o.optString("lyric_url", "").ifEmpty { null },
                vocalUrl = tracks.optString("vocal", "").ifEmpty { null },
                accompUrl = tracks.optString("accomp", "").ifEmpty { null },
                originalUrl = tracks.optString("original", "").ifEmpty { null }
            )
        }
    }

    /** 该模式是否可用。 */
    fun supportsMode(mode: String): Boolean = when (mode) {
        MODE_MIX -> (hasVocal && hasAccomp) || hasOriginal
        MODE_VOCAL -> hasVocal
        MODE_ACCOMP -> hasAccomp
        MODE_SMART -> smartAvailable
        else -> false
    }

    /** 可用模式的文字提示（含分离中状态）。 */
    fun statusHint(): String = when {
        separating -> "⏳ 正在自动分离人声/伴奏，完成后解锁人声/伴奏/智能模式"
        smartAvailable -> "已就绪：原唱 / 人声 / 伴奏 / 智能 四种模式可用"
        hasOriginal || hasVocal -> "缺少伴奏音轨，上传原曲可自动分离"
        else -> "缺少音轨"
    }

    val modeNames: Map<String, String>
        get() = mapOf(
            MODE_MIX to "原唱",
            MODE_VOCAL to "人声",
            MODE_ACCOMP to "伴奏",
            MODE_SMART to "智能"
        )
}

/** 听歌歌单（服务端 /api/listen/playlists 返回结构）。 */
data class ListenPlaylist(
    val id: Int,
    val name: String,
    val count: Int,
    val duration: Double,
    val createdAt: String,
    val updatedAt: String
) {
    companion object {
        fun fromJson(o: JSONObject): ListenPlaylist = ListenPlaylist(
            id = o.optInt("id"),
            name = o.optString("name", "歌单"),
            count = o.optInt("count"),
            duration = o.optDouble("duration", 0.0),
            createdAt = o.optString("created_at", ""),
            updatedAt = o.optString("updated_at", "")
        )
    }
}

/** 参考旋律音符事件（服务端 /api/songs/{id}/notes）。 */
data class RefNote(
    val t: Float,   // 起始秒（相对伴奏零时刻）
    val d: Float,   // 时长秒
    val m: Int,     // MIDI 音高
    val c: Float    // 置信度 0~1
) {
    val startMs: Long get() = (t * 1000f).toLong()
    val endMs: Long get() = ((t + d) * 1000f).toLong()
    val midi: Float get() = m.toFloat()

    companion object {
        /** 解析 {songId, version, notes:[{t,d,m,c}]} → (version, notes)。 */
        fun parse(payload: JSONObject): Pair<Int, List<RefNote>> {
            val version = payload.optInt("version", 1)
            val arr = payload.optJSONArray("notes") ?: org.json.JSONArray()
            val notes = (0 until arr.length()).map { i ->
                val n = arr.getJSONObject(i)
                RefNote(
                    t = n.optDouble("t", 0.0).toFloat(),
                    d = n.optDouble("d", 0.0).toFloat(),
                    m = n.optInt("m", 60),
                    c = n.optDouble("c", 1.0).toFloat()
                )
            }
            return version to notes
        }
    }
}

/** 录音作品（服务端 /api/works 返回结构）。 */
data class Work(
    val id: Int,
    val songId: Int?,
    val songTitle: String,
    val title: String,
    val mode: String,
    val duration: Double,
    val createdAt: String,
    val audioUrl: String
) {
    companion object {
        fun fromJson(o: JSONObject): Work = Work(
            id = o.optInt("id"),
            songId = if (o.isNull("song_id")) null else o.optInt("song_id"),
            songTitle = o.optString("song_title", ""),
            title = o.optString("title", "我的作品"),
            mode = o.optString("mode", "accomp"),
            duration = o.optDouble("duration", 0.0),
            createdAt = o.optString("created_at", ""),
            audioUrl = o.optString("audio_url", "")
        )
    }

    val modeLabel: String
        get() = when (mode) {
            "mix" -> "原唱"
            "vocal" -> "人声"
            "accomp" -> "伴奏"
            "smart" -> "智能"
            else -> mode
        }
}
