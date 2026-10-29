package com.aiocw.selfktv.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.aiocw.selfktv.R
import com.aiocw.selfktv.net.ApiClient
import com.aiocw.selfktv.net.Song

/**
 * 点歌页：封面 + 演唱模式选择（原唱 / 人声 / 伴奏 / 智能）。
 */
class SongDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SONG_ID = "song_id"

        fun intentFor(context: Context, song: Song, mode: String, record: Boolean): Intent =
            Intent(context, KaraokeActivity::class.java)
                .putExtra(KaraokeActivity.EXTRA_SONG_JSON, songJson(song))
                .putExtra(KaraokeActivity.EXTRA_MODE, mode)
                .putExtra(KaraokeActivity.EXTRA_RECORD, record)

        /** Song 序列化为 JSON，跨页面传递。 */
        fun songJson(song: Song): String = org.json.JSONObject().apply {
            put("id", song.id)
            put("title", song.title)
            put("artist", song.artist)
            put("duration", song.duration)
            put("has_vocal", song.hasVocal)
            put("has_accomp", song.hasAccomp)
            put("has_original", song.hasOriginal)
            put("has_lyric", song.hasLyric)
            put("has_cover", song.hasCover)
            put("smart_available", song.smartAvailable)
            put("separating", song.separating)
            put("has_pitch", song.hasPitch)
            put("cover_url", song.coverUrl ?: "")
            put("lyric_url", song.lyricUrl ?: "")
            put("tracks", org.json.JSONObject().apply {
                put("vocal", song.vocalUrl ?: "")
                put("accomp", song.accompUrl ?: "")
                put("original", song.originalUrl ?: "")
            })
        }.toString()
    }

    private var song: Song? = null
    private var selectedMode = Song.MODE_MIX

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_song_detail)
        UiKit.applySystemBars(this)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }

        val songId = intent.getIntExtra(EXTRA_SONG_ID, -1)
        if (songId <= 0) {
            Toast.makeText(this, "歌曲不存在", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        UiKit.bg {
            try {
                val s = ApiClient.song(songId)
                UiKit.ui {
                    song = s
                    bind(s)
                }
            } catch (e: Exception) {
                UiKit.ui {
                    Toast.makeText(this, "加载失败：${e.message}", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }

    private fun bind(s: Song) {
        findViewById<TextView>(R.id.tvTitle).text = s.title
        findViewById<TextView>(R.id.tvArtist).text = s.artist.ifEmpty { "未知歌手" }
        findViewById<TextView>(R.id.tvDuration).text = UiKit.fmtDuration(s.duration)
        findViewById<TextView>(R.id.tvScoreTag).visibility = if (s.hasPitch) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.tvStatusHint).text = s.statusHint()
        UiKit.loadCover(s.coverUrl, findViewById(R.id.ivCover), R.drawable.bg_cover_placeholder)

        val map = mapOf(
            Song.MODE_MIX to R.id.cardMix,
            Song.MODE_VOCAL to R.id.cardVocal,
            Song.MODE_ACCOMP to R.id.cardAccomp,
            Song.MODE_SMART to R.id.cardSmart
        )
        for ((m, id) in map) {
            val card = findViewById<LinearLayout>(id)
            val ok = s.supportsMode(m)
            card.isEnabled = ok
            card.alpha = if (ok) 1f else 0.35f
            card.setOnClickListener { selectMode(m) }
        }

        selectedMode = when {
            s.supportsMode(Song.MODE_SMART) -> Song.MODE_SMART
            s.supportsMode(Song.MODE_MIX) -> Song.MODE_MIX
            s.supportsMode(Song.MODE_VOCAL) -> Song.MODE_VOCAL
            else -> Song.MODE_ACCOMP
        }
        selectMode(selectedMode)

        findViewById<Button>(R.id.btnSing).setOnClickListener { startKaraoke(record = true) }
        findViewById<Button>(R.id.btnPractice).setOnClickListener { startPractice() }
    }

    private fun selectMode(mode: String) {
        val s = song ?: return
        if (!s.supportsMode(mode)) return
        selectedMode = mode
        val map = mapOf(
            Song.MODE_MIX to R.id.cardMix,
            Song.MODE_VOCAL to R.id.cardVocal,
            Song.MODE_ACCOMP to R.id.cardAccomp,
            Song.MODE_SMART to R.id.cardSmart
        )
        for ((m, id) in map) {
            findViewById<LinearLayout>(id).setBackgroundResource(
                if (m == mode) R.drawable.bg_mode_card_selected else R.drawable.bg_mode_card
            )
        }
        findViewById<TextView>(R.id.tvModeDesc).text = when (mode) {
            Song.MODE_MIX -> "🎤 原唱模式：人声 + 伴奏完整效果，适合跟唱学习"
            Song.MODE_VOCAL -> "🎙 人声模式：只有人声，听清唱细节"
            Song.MODE_ACCOMP -> "🎧 伴奏模式：只有伴奏，你的声音就是主角"
            else -> "✨ 智能模式：开口唱自动消原唱，停下时原唱接上（演唱中可随时手动开关人声/伴奏）"
        }
    }

    private fun startKaraoke(record: Boolean) {
        val s = song ?: return
        startActivity(intentFor(this, s, selectedMode, record))
    }

    /** 练唱：块状音准 + 逐句跟唱（系统唱一句 → 倒回伴奏你跟唱）。 */
    private fun startPractice() {
        val s = song ?: return
        startActivity(
            Intent(this, PracticeActivity::class.java)
                .putExtra(PracticeActivity.EXTRA_SONG_JSON, songJson(s))
        )
    }
}
