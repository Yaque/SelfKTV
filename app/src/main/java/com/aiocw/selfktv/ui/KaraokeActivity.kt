package com.aiocw.selfktv.ui

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.aiocw.selfktv.R
import com.aiocw.selfktv.audio.LrcParser
import com.aiocw.selfktv.audio.NotesRepo
import com.aiocw.selfktv.audio.PitchDetector
import com.aiocw.selfktv.audio.PitchScore
import com.aiocw.selfktv.audio.WavRecorder
import com.aiocw.selfktv.data.Prefs
import com.aiocw.selfktv.net.ApiClient
import com.aiocw.selfktv.net.Song
import com.aiocw.selfktv.ui.widget.LyricView
import com.aiocw.selfktv.ui.widget.PitchRollView
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * K 歌界面（模仿全民K歌）：
 *  - 四种模式随时切换：原唱(人声+伴奏) / 人声 / 伴奏 / 智能(开口自动消人声)
 *  - 人声、伴奏可随时独立开关（双音轨常驻播放，切音量即时生效）
 *  - 音准器 + 实时打分（对照分离人声提取的参考旋律）
 *  - 本地实时耳返（可开关，建议戴耳机）
 *  - 滚动歌词 / 旋转唱片 / 录音上传作品
 */
class KaraokeActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SONG_JSON = "song_json"
        const val EXTRA_MODE = "mode"
        const val EXTRA_RECORD = "record"
    }

    private lateinit var song: Song
    private var mode = Song.MODE_MIX
    private var vocalOn = true
    private var accompOn = true
    private var record = true
    private var earbackOn = false

    private var vocalPlayer: MediaPlayer? = null
    private var accompPlayer: MediaPlayer? = null
    private var master: MediaPlayer? = null
    private var preparedCount = 0
    private var totalPlayers = 0
    private var playing = false
    private var finished = false

    private var recorder: WavRecorder? = null
    private var micGranted = false
    private var playbackSpeed = 1.0f

    // 消音 / 音准状态
    @Volatile
    private var micLevel = 0f

    @Volatile
    private var micF0 = 0f
    private var lastLoudAt = 0L
    private var vocalCurrent = 1f
    private var pitchScore: PitchScore? = null

    // 歌词平滑滚动：基于时钟外推的单调播放位置（消除变速时的卡顿/回跳）
    private var estPos = 0.0
    private var lastTickReal = 0L

    private lateinit var lyricView: LyricView
    private lateinit var ivCoverThumb: ImageView
    private lateinit var pitchRoll: PitchRollView
    private lateinit var seekBar: SeekBar
    private lateinit var tvTimeCurrent: TextView
    private lateinit var tvTimeTotal: TextView
    private lateinit var tvScore: TextView
    private lateinit var btnPlayPause: ImageView
    private lateinit var sbVocalVolume: SeekBar
    private lateinit var tvSpeed: TextView
    private lateinit var pbMic: ProgressBar
    private lateinit var tvStatus: TextView

    private lateinit var chipMix: TextView
    private lateinit var chipVocal: TextView
    private lateinit var chipAccomp: TextView
    private lateinit var chipSmart: TextView
    private lateinit var btnToggleVocal: TextView
    private lateinit var btnToggleAccomp: TextView
    private lateinit var btnToggleRecord: TextView
    private lateinit var btnEarback: TextView

    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            if (!playing || finished) return
            val pos = smoothPos()
            if (!seekBar.isPressed) seekBar.progress = pos.toInt()
            tvTimeCurrent.text = UiKit.fmtTime(pos)
            lyricView.update(pos)
            syncPlayers()
            applySmartDucking()
            updatePitchUi(pos.toInt())
            handler.postDelayed(this, 50)
        }
    }

    /** 平滑播放位置：时钟匀速外推 + 大偏差才硬同步，只在用户 seek 时重置。 */
    private fun smoothPos(): Long {
        val reported = master?.currentPosition ?: 0
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastTickReal == 0L) {
            lastTickReal = now
            estPos = reported.toDouble()
            return reported.toLong()
        }
        val dt = (now - lastTickReal).coerceIn(0L, 500L)
        lastTickReal = now
        estPos += dt * playbackSpeed
        if (abs(estPos - reported) > 2500) estPos = reported.toDouble()
        return estPos.coerceAtLeast(0.0).toLong()
    }

    private fun resetSmoothPos(ms: Long) {
        estPos = ms.toDouble()
        lastTickReal = 0L
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            micGranted = granted
            if (!granted) {
                Toast.makeText(this, "未授予麦克风权限：打分/智能/耳返不可用", Toast.LENGTH_LONG).show()
            } else if (playing && recorder == null) {
                startRecorder()
            }
            refreshStatus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_karaoke)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        song = Song.fromJson(JSONObject(intent.getStringExtra(EXTRA_SONG_JSON) ?: "{}"))
        mode = intent.getStringExtra(EXTRA_MODE) ?: Song.MODE_MIX
        record = intent.getBooleanExtra(EXTRA_RECORD, true)

        bindViews()
        applyModePreset(mode)
        ensureMicPermission()
        loadLyrics()
        loadPitch()
        setupPlayers()
    }

    // ------------------------------------------------------------ 初始化

    private fun bindViews() {
        lyricView = findViewById(R.id.lyricView)
        ivCoverThumb = findViewById(R.id.ivCoverThumb)
        pitchRoll = findViewById(R.id.pitchRoll)
        seekBar = findViewById(R.id.seekBar)
        tvTimeCurrent = findViewById(R.id.tvTimeCurrent)
        tvTimeTotal = findViewById(R.id.tvTimeTotal)
        tvScore = findViewById(R.id.tvScore)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        sbVocalVolume = findViewById(R.id.sbVocalVolume)
        pbMic = findViewById(R.id.pbMic)
        tvStatus = findViewById(R.id.tvStatus)

        chipMix = findViewById(R.id.chipMix)
        chipVocal = findViewById(R.id.chipVocal)
        chipAccomp = findViewById(R.id.chipAccomp)
        chipSmart = findViewById(R.id.chipSmart)
        btnToggleVocal = findViewById(R.id.btnToggleVocal)
        btnToggleAccomp = findViewById(R.id.btnToggleAccomp)
        btnToggleRecord = findViewById(R.id.btnToggleRecord)
        btnEarback = findViewById(R.id.btnEarback)
        tvSpeed = findViewById(R.id.tvSpeed)
        findViewById<TextView>(R.id.btnSpeedMinus).setOnClickListener { changeSpeed(-0.05f) }
        findViewById<TextView>(R.id.btnSpeedPlus).setOnClickListener { changeSpeed(0.05f) }
        tvSpeed.setOnClickListener {
            playbackSpeed = 1.0f
            applySpeed()
            updateSpeedLabel()
        }

        findViewById<TextView>(R.id.tvSongTitle).text = song.title
        findViewById<TextView>(R.id.tvSongArtist).text = song.artist.ifEmpty { "未知歌手" }
        tvTimeTotal.text = UiKit.fmtDuration(song.duration)
        seekBar.max = (song.duration * 1000).toInt().coerceAtLeast(1)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { confirmExit() }
        findViewById<Button>(R.id.btnFinish).setOnClickListener { finishSession() }
        findViewById<Button>(R.id.btnRestart).setOnClickListener { restart() }
        btnPlayPause.setOnClickListener { togglePlay() }

        // 模式切换（随时）
        chipMix.setOnClickListener { if (song.supportsMode(Song.MODE_MIX)) applyModePreset(Song.MODE_MIX) else toastModeUnavailable() }
        chipVocal.setOnClickListener { if (song.supportsMode(Song.MODE_VOCAL)) applyModePreset(Song.MODE_VOCAL) else toastModeUnavailable() }
        chipAccomp.setOnClickListener { if (song.supportsMode(Song.MODE_ACCOMP)) applyModePreset(Song.MODE_ACCOMP) else toastModeUnavailable() }
        chipSmart.setOnClickListener { if (song.supportsMode(Song.MODE_SMART)) applyModePreset(Song.MODE_SMART) else toastModeUnavailable() }

        // 音轨开关（随时）
        btnToggleVocal.setOnClickListener {
            vocalOn = !vocalOn
            applyVolumes()
            refreshToggles()
            refreshStatus()
        }
        btnToggleAccomp.setOnClickListener {
            accompOn = !accompOn
            applyVolumes()
            refreshToggles()
            refreshStatus()
        }
        btnToggleRecord.setOnClickListener {
            record = !record
            refreshToggles()
            refreshStatus()
            Toast.makeText(this, if (record) "结束时将保存作品" else "本次不保存作品", Toast.LENGTH_SHORT).show()
        }
        btnEarback.setOnClickListener {
            earbackOn = !earbackOn
            recorder?.earbackEnabled = earbackOn
            refreshToggles()
            if (earbackOn) {
                Toast.makeText(this, "耳返已开启（建议戴耳机，外放会啸叫）", Toast.LENGTH_LONG).show()
            }
        }

        sbVocalVolume.progress = Prefs.vocalVolume
        sbVocalVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    Prefs.vocalVolume = progress
                    applyVolumes()
                }
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
                vocalPlayer?.seekTo(pos)
                accompPlayer?.seekTo(pos)
                lyricView.update(pos.toLong())
                resetSmoothPos(pos.toLong())
            }
        })

        lyricView.onLineTap = { line ->
            vocalPlayer?.seekTo(line.timeMs.toInt())
            accompPlayer?.seekTo(line.timeMs.toInt())
            lyricView.update(line.timeMs)
            resetSmoothPos(line.timeMs)
        }

        UiKit.bg {
            if (song.hasCover) {
                val bmp = ApiClient.getBitmap(song.coverUrl)
                UiKit.ui {
                    if (bmp != null) {
                        ivCoverThumb.visibility = View.VISIBLE
                        ivCoverThumb.setImageBitmap(UiKit.circleCrop(bmp))
                    }
                }
            }
        }
    }

    private fun toastModeUnavailable() {
        Toast.makeText(this, "该模式需要分离完成（人声/伴奏双音轨）", Toast.LENGTH_SHORT).show()
    }

    /** 应用模式预设（随时可调用，立即生效）。 */
    private fun applyModePreset(newMode: String) {
        mode = newMode
        when (newMode) {
            Song.MODE_MIX -> {
                vocalOn = true; accompOn = true
            }
            Song.MODE_VOCAL -> {
                vocalOn = true; accompOn = false
            }
            Song.MODE_ACCOMP -> {
                vocalOn = false; accompOn = true
            }
            Song.MODE_SMART -> {
                vocalOn = true; accompOn = true
                lastLoudAt = 0L
            }
        }
        applyVolumes()
        refreshToggles()
        refreshStatus()
    }

    /** 当前开关组合对应的模式（用于高亮）。 */
    private fun presetMode(): String = when {
        mode == Song.MODE_SMART && vocalOn && accompOn -> Song.MODE_SMART
        vocalOn && accompOn -> Song.MODE_MIX
        vocalOn && !accompOn -> Song.MODE_VOCAL
        !vocalOn && accompOn -> Song.MODE_ACCOMP
        else -> ""
    }

    private fun refreshToggles() {
        chipMix.isSelected = presetMode() == Song.MODE_MIX
        chipVocal.isSelected = presetMode() == Song.MODE_VOCAL
        chipAccomp.isSelected = presetMode() == Song.MODE_ACCOMP
        chipSmart.isSelected = presetMode() == Song.MODE_SMART

        chipMix.alpha = if (song.supportsMode(Song.MODE_MIX)) 1f else 0.35f
        chipVocal.alpha = if (song.supportsMode(Song.MODE_VOCAL)) 1f else 0.35f
        chipAccomp.alpha = if (song.supportsMode(Song.MODE_ACCOMP)) 1f else 0.35f
        chipSmart.alpha = if (song.supportsMode(Song.MODE_SMART)) 1f else 0.35f

        btnToggleVocal.isSelected = vocalOn
        btnToggleAccomp.isSelected = accompOn
        btnToggleRecord.isSelected = record
        btnEarback.isSelected = earbackOn

        btnToggleVocal.alpha = if (vocalPlayer != null) 1f else 0.35f
        btnToggleAccomp.alpha = if (accompPlayer != null) 1f else 0.35f
    }

    private fun applyVolumes() {
        accompPlayer?.setVolume(if (accompOn) 1f else 0f, if (accompOn) 1f else 0f)
        val v = if (vocalOn) Prefs.vocalVolume / 100f else 0f
        vocalCurrent = v
        vocalPlayer?.setVolume(v, v)
    }

    // ------------------------------------------------------------ 播放速度（0.05x 步进）

    private fun changeSpeed(delta: Float) {
        var s = (playbackSpeed + delta).coerceIn(0.5f, 2.0f)
        s = Math.round(s * 20f) / 20f          // 对齐 0.05 倍阶梯
        playbackSpeed = s
        applySpeed()
        updateSpeedLabel()
    }

    private fun updateSpeedLabel() {
        tvSpeed.text = String.format(Locale.US, "%.2fx", playbackSpeed)
    }

    /** 双轨同步变速（时值伸缩，尽量保持音高）。 */
    private fun applySpeed() {
        for (p in listOfNotNull(vocalPlayer, accompPlayer)) {
            try {
                val params: PlaybackParams = p.playbackParams
                params.speed = playbackSpeed
                params.pitch = 1.0f
                p.playbackParams = params
            } catch (_: Exception) {
            }
        }
    }

    private fun ensureMicPermission() {
        micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!micGranted) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun loadLyrics() {
        UiKit.bg {
            val text = ApiClient.getLyric(song.lyricUrl)
            val fallbackMs = (song.duration * 1000).toLong().coerceAtLeast(120_000)
            val lines = LrcParser.parse(text, fallbackMs)
            UiKit.ui { lyricView.setLyrics(lines) }
        }
    }

    /** 参考旋律（打分/音准器）：服务端音符事件 + 本地缓存。 */
    private fun loadPitch() {
        UiKit.bg {
            val ref = NotesRepo.load(this@KaraokeActivity, song.id) ?: return@bg
            UiKit.ui {
                pitchScore = PitchScore(ref.notes)
                pitchRoll.setNotes(ref.notes)
                if (song.hasPitch) {
                    Toast.makeText(this, "已加载参考旋律，开始打分 🎯", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupPlayers() {
        val vUrl = song.vocalUrl ?: song.originalUrl
        val aUrl = song.accompUrl
        totalPlayers = (if (vUrl != null) 1 else 0) + (if (aUrl != null) 1 else 0)
        if (totalPlayers == 0) {
            Toast.makeText(this, "缺少音轨，无法播放", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (vUrl != null) vocalPlayer = makePlayer(vUrl, isVocal = true)
        if (aUrl != null) accompPlayer = makePlayer(aUrl, isVocal = false)
        refreshToggles()
    }

    private fun makePlayer(url: String?, isVocal: Boolean): MediaPlayer? {
        if (url.isNullOrEmpty()) return null
        return try {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setDataSource(this@KaraokeActivity, Uri.parse(ApiClient.absolute(url)), ApiClient.mediaHeaders())
                setOnPreparedListener { onPlayerPrepared(it, isVocal) }
                setOnCompletionListener { onPlaybackCompleted() }
                setOnErrorListener { _, what, extra ->
                    Toast.makeText(this@KaraokeActivity, "播放出错 ($what/$extra)", Toast.LENGTH_LONG).show()
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
    }

    private fun onPlayerPrepared(player: MediaPlayer, isVocal: Boolean) {
        if (master == null) master = player
        preparedCount++
        if (preparedCount >= totalPlayers) {
            seekBar.max = (master?.duration ?: (song.duration * 1000).toInt()).coerceAtLeast(1)
            tvTimeTotal.text = UiKit.fmtTime((master?.duration ?: 0).toLong())
            applyVolumes()
            applySpeed()
            resetSmoothPos(0)
            startPlayback()
        }
    }

    private fun onPlaybackCompleted() {
        if (finished) return
        finishSession()
    }

    // ------------------------------------------------------------ 播放控制

    private fun startPlayback() {
        if (playing || finished) return
        try {
            vocalPlayer?.start()
            accompPlayer?.start()
        } catch (_: Exception) {
        }
        playing = true
        btnPlayPause.setImageResource(R.drawable.ic_pause)
        if (recorder == null && micGranted) startRecorder()
        handler.post(ticker)
        refreshStatus()
    }

    private fun togglePlay() {
        if (finished) return
        if (playing) {
            playing = false
            handler.removeCallbacks(ticker)
            vocalPlayer?.pause()
            accompPlayer?.pause()
            recorder?.pause()
            btnPlayPause.setImageResource(R.drawable.ic_play)
        } else {
            vocalPlayer?.start()
            accompPlayer?.start()
            recorder?.resume()
            playing = true
            resetSmoothPos((master?.currentPosition ?: 0).toLong())
            btnPlayPause.setImageResource(R.drawable.ic_pause)
            handler.post(ticker)
        }
        refreshStatus()
    }

    private fun restart() {
        if (finished) return
        AlertDialog.Builder(this)
            .setTitle("重新演唱")
            .setMessage("当前录音与得分将被丢弃，从头开始？")
            .setPositiveButton("重唱") { _, _ ->
                handler.removeCallbacks(ticker)
                recorder?.discard()
                recorder = null
                pitchScore = null
                tvScore.text = "0"
                lastLoudAt = 0L
                vocalPlayer?.seekTo(0)
                accompPlayer?.seekTo(0)
                lyricView.update(0)
                seekBar.progress = 0
                resetSmoothPos(0)
                applyVolumes()
                playing = true
                vocalPlayer?.start()
                accompPlayer?.start()
                if (micGranted) startRecorder()
                btnPlayPause.setImageResource(R.drawable.ic_pause)
                loadPitch()
                handler.post(ticker)
                refreshStatus()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 双音轨同步：以人声轨为主轨，偏差超过 250ms 时拉回。 */
    private fun syncPlayers() {
        val v = vocalPlayer ?: return
        val a = accompPlayer ?: return
        if (!playing) return
        val drift = v.currentPosition - a.currentPosition
        if (abs(drift) > 250) {
            try {
                a.seekTo(v.currentPosition)
            } catch (_: Exception) {
            }
        }
    }

    /** 智能模式：检测到演唱 → 消人声；停唱约 2.5 秒后人声渐回。 */
    private fun applySmartDucking() {
        val vp = vocalPlayer ?: return
        val now = System.currentTimeMillis()
        if (micLevel > smartThreshold()) lastLoudAt = now
        val ducking = mode == Song.MODE_SMART && vocalOn && (now - lastLoudAt) < 2500
        val target = when {
            !vocalOn -> 0f
            ducking -> 0f
            else -> Prefs.vocalVolume / 100f
        }
        vocalCurrent += (target - vocalCurrent) * 0.3f
        if (abs(vocalCurrent - target) < 0.01f) vocalCurrent = target
        vp.setVolume(vocalCurrent, vocalCurrent)
    }

    private fun smartThreshold(): Float =
        (0.30f - Prefs.smartSensitivity * 0.0025f).coerceIn(0.03f, 0.4f)

    // ------------------------------------------------------------ 音准 / 打分

    private fun updatePitchUi(pos: Int) {
        val score = pitchScore ?: return
        val hit = score.feed(pos.toLong(), micF0)
        pitchRoll.update(pos.toLong(), micF0, hit)
        tvScore.text = score.score.toString()
    }

    // ------------------------------------------------------------ 录音

    private fun startRecorder() {
        if (recorder != null || !micGranted) return
        val dir = getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: filesDir
        val file = File(dir, "record_${System.currentTimeMillis()}.wav")
        val rec = WavRecorder(file)
        rec.earbackEnabled = earbackOn
        rec.onLevel = { level ->
            micLevel = level
            UiKit.ui { pbMic.progress = (level * 100).toInt() }
        }
        rec.onFrame = { buf, n ->
            micF0 = PitchDetector.detect(buf, n, WavRecorder.SAMPLE_RATE)
        }
        if (rec.start()) {
            recorder = rec
        } else {
            Toast.makeText(this, "麦克风不可用", Toast.LENGTH_LONG).show()
        }
        refreshStatus()
    }

    // ------------------------------------------------------------ 结束

    private fun finishSession() {
        if (finished) return
        finished = true
        playing = false
        handler.removeCallbacks(ticker)
        try {
            vocalPlayer?.stop()
            accompPlayer?.stop()
        } catch (_: Exception) {
        }
        vocalPlayer?.release()
        accompPlayer?.release()
        vocalPlayer = null
        accompPlayer = null
        master = null

        val file = recorder?.stop()
        recorder = null

        val score = pitchScore
        val scoreLine = if (score != null && score.frames > 0) {
            "\n🎯 音准得分 ${score.score}（${score.grade} 级）"
        } else ""

        if (!record || file == null) {
            AlertDialog.Builder(this)
                .setTitle("演唱完成")
                .setMessage("本次未保存作品。$scoreLine")
                .setPositiveButton("返回歌单") { _, _ -> finish() }
                .setNeutralButton("再唱一次") { _, _ -> recreate() }
                .setCancelable(false)
                .show()
            return
        }

        val uploading = AlertDialog.Builder(this)
            .setMessage("正在上传作品…")
            .setCancelable(false)
            .show()

        UiKit.bg {
            try {
                val work = ApiClient.uploadWork(
                    songId = song.id,
                    mode = mode,
                    title = "${song.title} - ${Prefs.username.ifEmpty { "我" }}",
                    file = file
                )
                UiKit.ui {
                    uploading.dismiss()
                    AlertDialog.Builder(this)
                        .setTitle("演唱完成 🎉")
                        .setMessage("作品「${work.optString("title")}」已保存到服务端$scoreLine")
                        .setPositiveButton("返回歌单") { _, _ -> finish() }
                        .setNeutralButton("再唱一次") { _, _ -> recreate() }
                        .setCancelable(false)
                        .show()
                }
            } catch (e: Exception) {
                file.delete()
                UiKit.ui {
                    uploading.dismiss()
                    AlertDialog.Builder(this)
                        .setTitle("演唱完成")
                        .setMessage("录音上传失败：${e.message}$scoreLine")
                        .setPositiveButton("确定") { _, _ -> finish() }
                        .show()
                }
            }
        }
    }

    private fun confirmExit() {
        if (finished) {
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("退出演唱")
            .setMessage("当前演唱将不会保存，确定退出？")
            .setPositiveButton("退出") { _, _ ->
                finished = true
                playing = false
                handler.removeCallbacks(ticker)
                recorder?.discard()
                recorder = null
                try {
                    vocalPlayer?.stop()
                    accompPlayer?.stop()
                } catch (_: Exception) {
                }
                vocalPlayer?.release()
                accompPlayer?.release()
                finish()
            }
            .setNegativeButton("继续演唱", null)
            .show()
    }

    private fun refreshStatus() {
        val parts = mutableListOf<String>()
        parts.add(
            when (presetMode()) {
                Song.MODE_MIX -> "🎤 原唱模式"
                Song.MODE_VOCAL -> "🎙 人声模式"
                Song.MODE_ACCOMP -> "🎧 伴奏模式"
                Song.MODE_SMART -> "✨ 智能伴唱"
                else -> "🎛 自定义混音"
            }
        )
        if (recorder != null) parts.add(if (playing) "● 录音中" else "❚❚ 已暂停")
        if (earbackOn) parts.add("🎧 耳返开")
        if (song.separating) parts.add("⏳ 分离中")
        tvStatus.text = parts.joinToString("　")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        confirmExit()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(ticker)
        recorder?.discard()
        recorder = null
        vocalPlayer?.release()
        accompPlayer?.release()
        vocalPlayer = null
        accompPlayer = null
    }
}
