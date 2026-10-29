package com.aiocw.selfktv.ui

import android.content.ContentValues
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Button
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.aiocw.selfktv.R
import com.aiocw.selfktv.audio.LrcParser
import com.aiocw.selfktv.net.ApiClient
import com.aiocw.selfktv.net.Song
import com.aiocw.selfktv.net.Work
import com.aiocw.selfktv.ui.widget.LyricView
import org.json.JSONObject
import kotlin.math.abs

/**
 * 作品管理页：
 *  - 作品（人声）+ 伴奏 混合播放：两轨独立开关、伴奏音量调节、随时切换
 *  - 歌词滚动 + 可拖动进度条
 *  - 混合导出：服务端离线混音 → 下载到「下载/SelfKTV」并可分享
 */
class WorkDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_WORK_JSON = "work_json"

        fun intentFor(context: android.content.Context, work: Work): Intent =
            Intent(context, WorkDetailActivity::class.java)
                .putExtra(
                    EXTRA_WORK_JSON, JSONObject().apply {
                        put("id", work.id)
                        put("title", work.title)
                        put("song_id", work.songId ?: JSONObject.NULL)
                        put("song_title", work.songTitle)
                        put("mode", work.mode)
                        put("duration", work.duration)
                    }.toString()
                )
    }

    private var work: Work? = null
    private var song: Song? = null

    private var voicePlayer: MediaPlayer? = null
    private var accompPlayer: MediaPlayer? = null
    private var preparedCount = 0
    private var totalPlayers = 0
    private var playing = false
    private var finished = false

    private var voiceOn = true
    private var accompOn = true

    private var estPos = 0.0
    private var lastTickReal = 0L

    private lateinit var lyricView: LyricView
    private lateinit var seekBar: SeekBar
    private lateinit var tvTimeCurrent: TextView
    private lateinit var tvTimeTotal: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnPlayPause: ImageView
    private lateinit var sbAccomp: SeekBar

    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            if (!playing || finished) return
            val pos = smoothPos()
            if (!seekBar.isPressed) seekBar.progress = pos.toInt()
            tvTimeCurrent.text = UiKit.fmtTime(pos)
            lyricView.update(pos)
            syncPlayers()
            handler.postDelayed(this, 80)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_work_detail)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val json = intent.getStringExtra(EXTRA_WORK_JSON) ?: "{}"
        val o = JSONObject(json)
        work = Work(
            id = o.optInt("id"),
            songId = if (o.isNull("song_id")) null else o.optInt("song_id"),
            songTitle = o.optString("song_title", ""),
            title = o.optString("title", "我的作品"),
            mode = o.optString("mode", "accomp"),
            duration = o.optDouble("duration", 0.0),
            createdAt = "",
            audioUrl = "/api/works/${o.optInt("id")}/stream"
        )
        bindViews()
        loadSongAndPlay()
    }

    // ------------------------------------------------------------ 初始化

    private fun bindViews() {
        lyricView = findViewById(R.id.lyricView)
        seekBar = findViewById(R.id.seekBar)
        tvTimeCurrent = findViewById(R.id.tvTimeCurrent)
        tvTimeTotal = findViewById(R.id.tvTimeTotal)
        tvStatus = findViewById(R.id.tvStatus)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        sbAccomp = findViewById(R.id.sbAccomp)

        val w = work ?: return
        findViewById<TextView>(R.id.tvWorkTitle).text = w.title
        findViewById<TextView>(R.id.tvSongTitle).text = w.songTitle.ifEmpty { "自由演唱" }
        findViewById<TextView>(R.id.tvModeBadge).text = w.modeLabel
        tvTimeTotal.text = UiKit.fmtDuration(w.duration)
        seekBar.max = (w.duration * 1000).toInt().coerceAtLeast(1)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        btnPlayPause.setOnClickListener { togglePlay() }
        findViewById<Button>(R.id.btnExport).setOnClickListener { exportMix() }

        findViewById<TextView>(R.id.btnToggleVoice).setOnClickListener {
            voiceOn = !voiceOn
            applyVolumes()
            refreshToggles()
        }
        findViewById<TextView>(R.id.btnToggleAccomp).setOnClickListener {
            accompOn = !accompOn
            applyVolumes()
            refreshToggles()
        }
        sbAccomp.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) applyVolumes()
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) tvTimeCurrent.text = UiKit.fmtTime(progress.toLong())
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                val pos = seekBar.progress
                voicePlayer?.seekTo(pos)
                accompPlayer?.seekTo(pos)
                lyricView.update(pos.toLong())
                resetSmoothPos(pos.toLong())
            }
        })
        refreshToggles()
    }

    /** 加载歌曲信息（伴奏轨 + 歌词），然后准备播放器。 */
    private fun loadSongAndPlay() {
        UiKit.bg {
            val s = work?.songId?.let {
                try {
                    ApiClient.song(it)
                } catch (_: Exception) {
                    null
                }
            }
            val lyrics = UiKit.run { s?.let { ApiClient.getLyric(it.lyricUrl) } }
            UiKit.ui {
                song = s
                if (lyrics != null) {
                    val fallbackMs = ((work?.duration ?: 0.0) * 1000).toLong().coerceAtLeast(60_000)
                    lyricView.setLyrics(LrcParser.parse(lyrics, fallbackMs))
                }
                setupPlayers()
            }
        }
    }

    private fun setupPlayers() {
        val w = work ?: return
        totalPlayers = 1 + (if (song != null) 1 else 0)
        voicePlayer = makePlayer(ApiClient.absolute(w.audioUrl))
        val accompUrl = song?.accompUrl ?: song?.vocalUrl ?: song?.originalUrl
        accompPlayer = accompUrl?.let { makePlayer(ApiClient.absolute(it)) }
        if (accompPlayer == null) totalPlayers = 1
    }

    private fun makePlayer(url: String): MediaPlayer? = try {
        MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            setDataSource(this@WorkDetailActivity, Uri.parse(url), ApiClient.mediaHeaders())
            setOnPreparedListener { onPlayerPrepared(it) }
            setOnErrorListener { _, what, extra ->
                Toast.makeText(this@WorkDetailActivity, "播放出错 ($what/$extra)", Toast.LENGTH_LONG).show()
                finish()
                true
            }
            prepareAsync()
        }
    } catch (e: Exception) {
        Toast.makeText(this, "无法播放：${e.message}", Toast.LENGTH_LONG).show()
        finish()
        null
    }

    private fun onPlayerPrepared(player: MediaPlayer) {
        preparedCount++
        if (preparedCount >= totalPlayers) {
            val dur = voicePlayer?.duration ?: (work?.duration ?: 0.0).toInt()
            seekBar.max = dur.coerceAtLeast(1)
            tvTimeTotal.text = UiKit.fmtTime(dur.toLong())
            applyVolumes()
            startPlayback()
        }
    }

    private fun startPlayback() {
        if (playing || finished) return
        try {
            voicePlayer?.start()
            accompPlayer?.start()
        } catch (_: Exception) {
        }
        playing = true
        btnPlayPause.setImageResource(R.drawable.ic_pause)
        resetSmoothPos(0)
        handler.post(ticker)
        refreshStatus()
    }

    private fun togglePlay() {
        if (finished) return
        if (playing) {
            playing = false
            handler.removeCallbacks(ticker)
            voicePlayer?.pause()
            accompPlayer?.pause()
            btnPlayPause.setImageResource(R.drawable.ic_play)
        } else {
            voicePlayer?.start()
            accompPlayer?.start()
            playing = true
            btnPlayPause.setImageResource(R.drawable.ic_pause)
            resetSmoothPos((voicePlayer?.currentPosition ?: 0).toLong())
            handler.post(ticker)
        }
        refreshStatus()
    }

    private fun applyVolumes() {
        voicePlayer?.setVolume(if (voiceOn) 1f else 0f, if (voiceOn) 1f else 0f)
        val a = if (accompOn) sbAccomp.progress / 100f else 0f
        accompPlayer?.setVolume(a, a)
    }

    private fun refreshToggles() {
        findViewById<TextView>(R.id.btnToggleVoice).isSelected = voiceOn
        findViewById<TextView>(R.id.btnToggleAccomp).isSelected = accompOn
        findViewById<TextView>(R.id.btnToggleAccomp).alpha = if (song != null) 1f else 0.35f
    }

    private fun refreshStatus() {
        val parts = mutableListOf<String>()
        parts.add(if (playing) "▶ 播放中" else "❚❚ 已暂停")
        if (!voiceOn) parts.add("人声关")
        if (!accompOn) parts.add("伴奏关")
        if (song == null) parts.add("（该作品无伴奏轨）")
        tvStatus.text = parts.joinToString("　")
    }

    private fun syncPlayers() {
        val v = voicePlayer ?: return
        val a = accompPlayer ?: return
        val drift = v.currentPosition - a.currentPosition
        if (abs(drift) > 250) {
            try {
                a.seekTo(v.currentPosition)
            } catch (_: Exception) {
            }
        }
    }

    private fun smoothPos(): Long {
        val reported = voicePlayer?.currentPosition ?: 0
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastTickReal == 0L) {
            lastTickReal = now
            estPos = reported.toDouble()
            return reported.toLong()
        }
        val dt = (now - lastTickReal).coerceIn(0L, 500L)
        lastTickReal = now
        estPos += dt
        if (abs(estPos - reported) > 2500) estPos = reported.toDouble()
        return estPos.coerceAtLeast(0.0).toLong()
    }

    private fun resetSmoothPos(ms: Long) {
        estPos = ms.toDouble()
        lastTickReal = 0L
    }

    // ------------------------------------------------------------ 混合导出

    private fun exportMix() {
        val w = work ?: return
        if (song == null) {
            Toast.makeText(this, "该作品无伴奏轨，仅导出人声", Toast.LENGTH_SHORT).show()
        }
        val dialog = AlertDialog.Builder(this)
            .setMessage("正在服务端混合并导出…")
            .setCancelable(false)
            .show()
        val accompGain = sbAccomp.progress / 100f
        UiKit.bg {
            val bytes = ApiClient.downloadBytes(
                "/api/works/${w.id}/export?vocal=1.0&accomp=$accompGain"
            )
            UiKit.ui {
                dialog.dismiss()
                if (bytes == null) {
                    Toast.makeText(this, "导出失败，请稍后重试", Toast.LENGTH_LONG).show()
                } else {
                    saveAndShare(bytes, w)
                }
            }
        }
    }

    /** 保存到「下载/SelfKTV」并通过系统分享。 */
    private fun saveAndShare(bytes: ByteArray, w: Work) {
        val name = "SelfKTV_${w.title.filter { it.isLetterOrDigit() || it == '_' }}_mix.wav"
        var uri: Uri? = null
        try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "audio/wav")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SelfKTV")
            }
            uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                contentResolver.openOutputStream(it)?.use { os -> os.write(bytes) }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "保存失败：${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "已导出到 下载/SelfKTV/$name", Toast.LENGTH_LONG).show()
        if (uri != null) {
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "audio/wav"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                startActivity(Intent.createChooser(share, "分享混合音频"))
            } catch (_: Exception) {
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(ticker)
        voicePlayer?.release()
        accompPlayer?.release()
        voicePlayer = null
        accompPlayer = null
    }
}
