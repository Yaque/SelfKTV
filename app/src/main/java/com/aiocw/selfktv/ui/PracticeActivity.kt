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
import android.os.SystemClock
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
import com.aiocw.selfktv.ui.widget.PitchRollView
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * 练唱页（模仿全民K歌练唱模式）：
 *  - 块状音准提示器：参考旋律横向滚动色块，你的音高轨迹叠加其上，命中变绿
 *  - 逐句跟唱：系统唱一句（示范）→ 自动倒回 → 只放伴奏你跟唱一句 → 下一句
 *  - 自定义练习范围：单选/多选任意歌词句
 */
class PracticeActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SONG_JSON = "song_json"
        private const val DEMO_LEAD_MS = 400L   // 每句提前进入
        private const val SING_TAIL_MS = 500L   // 跟唱尾部余量
    }

    private class Phrase(val index: Int, val startMs: Long, val endMs: Long, val text: String)

    private enum class Phase { DEMO, SING }

    private lateinit var song: Song
    private var phrases: List<Phrase> = emptyList()
    private var selected: MutableList<Int> = mutableListOf()   // 练习句下标（有序）
    private var selPos = 0
    private var groupSize = 1                 // 一次唱几句
    private var groupPos = 0                  // 当前轮次（组）
    private var groups: List<List<Int>> = listOf()
    private var lastActiveIdx = -1
    private var phase = Phase.DEMO

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

    // 跟唱模式（同演唱页）：mix 原唱 / vocal 人声 / accomp 伴奏 / smart 智能
    private var singMode = Song.MODE_ACCOMP
    private var lastLoudAt = 0L
    private var vocalCurrent = 1f
    private lateinit var chipMix: TextView
    private lateinit var chipVocal: TextView
    private lateinit var chipAccomp: TextView
    private lateinit var chipSmart: TextView

    @Volatile
    private var micLevel = 0f

    @Volatile
    private var micF0 = 0f
    private var pitchScore: PitchScore? = null

    // 卷帘平滑滚动：时钟外推位置（消除变速卡顿/回跳）
    private var estPos = 0.0
    private var lastTickReal = 0L

    private lateinit var rollView: PitchRollView
    private lateinit var tvPhase: TextView
    private lateinit var tvPhraseInfo: TextView
    private lateinit var tvLineCurrent: TextView
    private lateinit var tvLinePrev: TextView
    private lateinit var tvLineNext: TextView
    private lateinit var tvScore: TextView
    private lateinit var btnPlayPause: ImageView
    private lateinit var pbMic: ProgressBar
    private lateinit var tvSpeed: TextView
    private lateinit var seekProgress: SeekBar
    private lateinit var tvRecordDot: TextView
    private lateinit var tvRecordStatus: TextView

    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            if (!playing || finished) return
            val reported = master?.currentPosition ?: 0
            if (currentGroup().isNotEmpty() && reported >= groupEndMs()) {
                advancePhase()
            } else {
                updateUi(smoothPos().toInt())
                applySmartDucking()
                handler.postDelayed(this, 50)
            }
        }
    }

    /** 平滑播放位置：时钟匀速外推 + 大偏差才硬同步。 */
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
        if (kotlin.math.abs(estPos - reported) > 2500) estPos = reported.toDouble()
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
                Toast.makeText(this, "未授予麦克风权限：无法打分", Toast.LENGTH_LONG).show()
            } else if (playing && recorder == null) {
                startRecorder()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_practice)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        song = Song.fromJson(JSONObject(intent.getStringExtra(EXTRA_SONG_JSON) ?: "{}"))
        bindViews()
        ensureMicPermission()
        loadAndBuild()
    }

    // ------------------------------------------------------------ 初始化

    private fun bindViews() {
        rollView = findViewById(R.id.pitchRoll)
        tvPhase = findViewById(R.id.tvPhase)
        tvPhraseInfo = findViewById(R.id.tvPhraseInfo)
        tvLineCurrent = findViewById(R.id.tvLineCurrent)
        tvLinePrev = findViewById(R.id.tvLinePrev)
        tvLineNext = findViewById(R.id.tvLineNext)
        tvScore = findViewById(R.id.tvScore)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        pbMic = findViewById(R.id.pbMic)
        seekProgress = findViewById(R.id.seekProgress)
        tvRecordDot = findViewById(R.id.tvRecordDot)
        tvRecordStatus = findViewById(R.id.tvRecordStatus)
        seekProgress.setOnTouchListener { _, _ -> true }   // 仅展示，不可拖动

        findViewById<TextView>(R.id.tvSongTitle).text = song.title
        findViewById<TextView>(R.id.tvSongArtist).text = song.artist.ifEmpty { "未知歌手" }
        findViewById<ImageView>(R.id.btnBack).setOnClickListener { confirmExit() }
        findViewById<Button>(R.id.btnPickPhrases).setOnClickListener { pickPhrases() }
        findViewById<Button>(R.id.btnRedoPhrase).setOnClickListener { redoPhrase() }
        findViewById<Button>(R.id.btnGroupSize).setOnClickListener { pickGroupSize() }
        findViewById<Button>(R.id.btnPrevPhrase).setOnClickListener { jumpPhrase(-1) }
        findViewById<Button>(R.id.btnNextPhrase).setOnClickListener { jumpPhrase(1) }
        btnPlayPause.setOnClickListener { togglePlay() }
        tvSpeed = findViewById(R.id.tvSpeed)
        findViewById<TextView>(R.id.btnSpeedMinus).setOnClickListener { changeSpeed(-0.05f) }
        findViewById<TextView>(R.id.btnSpeedPlus).setOnClickListener { changeSpeed(0.05f) }
        tvSpeed.setOnClickListener {
            playbackSpeed = 1.0f
            applySpeed()
            updateSpeedLabel()
        }
        chipMix = findViewById(R.id.chipMix)
        chipVocal = findViewById(R.id.chipVocal)
        chipAccomp = findViewById(R.id.chipAccomp)
        chipSmart = findViewById(R.id.chipSmart)
        chipMix.setOnClickListener { selectSingMode(Song.MODE_MIX) }
        chipVocal.setOnClickListener { selectSingMode(Song.MODE_VOCAL) }
        chipAccomp.setOnClickListener { selectSingMode(Song.MODE_ACCOMP) }
        chipSmart.setOnClickListener { selectSingMode(Song.MODE_SMART) }
    }

    private fun ensureMicPermission() {
        micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!micGranted) permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    /** 加载歌词 + 参考旋律（音符事件），构建练习句，准备播放器。 */
    private fun loadAndBuild() {
        UiKit.bg {
            val text = ApiClient.getLyric(song.lyricUrl)
            val fallbackMs = (song.duration * 1000).toLong().coerceAtLeast(120_000)
            val lines = LrcParser.parse(text, fallbackMs)
            val ref = NotesRepo.load(this@PracticeActivity, song.id)
            UiKit.ui {
                buildPhrases(lines, ref)
                refreshModeChips()
                setupPlayers()
            }
        }
    }

    private fun buildPhrases(lines: List<LrcParser.Line>, ref: NotesRepo.Reference?) {
        val durationMs = (song.duration * 1000).toLong().coerceAtLeast(60_000)
        val list = ArrayList<Phrase>()
        if (lines.isEmpty()) {
            list.add(Phrase(0, 0L, durationMs, "（无歌词）整曲练习"))
        } else {
            for (i in lines.indices) {
                val start = (lines[i].timeMs - DEMO_LEAD_MS).coerceAtLeast(0L)
                val end = if (i + 1 < lines.size) {
                    (lines[i + 1].timeMs - 200L).coerceAtLeast(start + 1500L)
                } else {
                    (lines[i].timeMs + 8000L).coerceAtMost(durationMs)
                }
                list.add(Phrase(i, start, end, lines[i].text.ifEmpty { "···" }))
            }
        }
        phrases = list
        selected = list.indices.toMutableList()
        rebuildGroups()

        if (ref != null && ref.notes.isNotEmpty()) {
            pitchScore = PitchScore(ref.notes)
            rollView.setNotes(ref.notes)
        }
        refreshPhraseUi()
    }

    // ------------------------------------------------------------ 播放器

    private fun setupPlayers() {
        val vUrl = song.vocalUrl ?: song.originalUrl
        val aUrl = song.accompUrl
        totalPlayers = (if (vUrl != null) 1 else 0) + (if (aUrl != null) 1 else 0)
        if (totalPlayers == 0) {
            Toast.makeText(this, "缺少音轨，无法练习", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (vUrl != null) vocalPlayer = makePlayer(vUrl, isVocal = true)
        if (aUrl != null) accompPlayer = makePlayer(aUrl, isVocal = false)
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
                setDataSource(this@PracticeActivity, Uri.parse(ApiClient.absolute(url)), ApiClient.mediaHeaders())
                setOnPreparedListener { onPlayerPrepared(it) }
                setOnErrorListener { _, what, extra ->
                    Toast.makeText(this@PracticeActivity, "播放出错 ($what/$extra)", Toast.LENGTH_LONG).show()
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

    private fun onPlayerPrepared(player: MediaPlayer) {
        if (master == null) master = player
        preparedCount++
        if (preparedCount >= totalPlayers) {
            startPlayback()
        }
    }

    private fun startPlayback() {
        if (playing || finished) return
        if (currentGroup().isEmpty()) return
        try {
            vocalPlayer?.seekTo(groupStartMs().toInt())
            accompPlayer?.seekTo(groupStartMs().toInt())
            vocalPlayer?.start()
            accompPlayer?.start()
        } catch (_: Exception) {
        }
        phase = Phase.DEMO
        applyVolumes()
        applySpeed()
        playing = true
        btnPlayPause.setImageResource(R.drawable.ic_pause)
        if (micGranted && recorder == null) startRecorder()
        handler.post(ticker)
        refreshPhraseUi()
    }

    /** 示范 ↔ 跟唱 阶段推进：唱完倒回 → 让位给用户；跟唱完 → 下一轮（N 句一组）。 */
    private fun advancePhase() {
        if (currentGroup().isEmpty()) {
            finishPractice()
            return
        }
        handler.removeCallbacks(ticker)
        if (phase == Phase.DEMO) {
            phase = Phase.SING
            seekTo(groupStartMs())
            Toast.makeText(this, "倒回伴奏，你来唱这一轮 🎙", Toast.LENGTH_SHORT).show()
        } else {
            if (groupPos + 1 >= groups.size) {
                finishPractice()
                return
            }
            groupPos++
            phase = Phase.DEMO
            seekTo(groupStartMs())
        }
        applyVolumes()
        refreshPhraseUi()
        if (playing) handler.post(ticker)
    }

    private fun seekTo(ms: Long) {
        try {
            vocalPlayer?.seekTo(ms.toInt())
            accompPlayer?.seekTo(ms.toInt())
        } catch (_: Exception) {
        }
        resetSmoothPos(ms)
    }

    /** 示范：人声+伴奏；跟唱：按所选模式混音（随时可切）。 */
    private fun applyVolumes() {
        if (phase == Phase.DEMO) {
            vocalPlayer?.setVolume(1f, 1f)
            accompPlayer?.setVolume(1f, 1f)
            return
        }
        when (singMode) {
            Song.MODE_MIX -> {
                vocalCurrent = 1f
                vocalPlayer?.setVolume(1f, 1f)
                accompPlayer?.setVolume(1f, 1f)
            }
            Song.MODE_VOCAL -> {
                vocalCurrent = 1f
                vocalPlayer?.setVolume(1f, 1f)
                accompPlayer?.setVolume(0f, 0f)
            }
            Song.MODE_ACCOMP -> {
                vocalCurrent = 0f
                vocalPlayer?.setVolume(0f, 0f)
                accompPlayer?.setVolume(1f, 1f)
            }
            else -> {   // 智能：开口自动消人声
                accompPlayer?.setVolume(1f, 1f)
                vocalPlayer?.setVolume(vocalCurrent, vocalCurrent)
            }
        }
    }

    /** 跟唱模式切换（随时生效）。 */
    private fun selectSingMode(mode: String) {
        if (!song.supportsMode(mode)) {
            Toast.makeText(this, "该模式需要分离完成（人声/伴奏双音轨）", Toast.LENGTH_SHORT).show()
            return
        }
        singMode = mode
        if (mode == Song.MODE_SMART) {
            vocalCurrent = 1f
            lastLoudAt = 0L
        }
        refreshModeChips()
        applyVolumes()
    }

    private fun refreshModeChips() {
        chipMix.isSelected = singMode == Song.MODE_MIX
        chipVocal.isSelected = singMode == Song.MODE_VOCAL
        chipAccomp.isSelected = singMode == Song.MODE_ACCOMP
        chipSmart.isSelected = singMode == Song.MODE_SMART
        chipMix.alpha = if (song.supportsMode(Song.MODE_MIX)) 1f else 0.35f
        chipVocal.alpha = if (song.supportsMode(Song.MODE_VOCAL)) 1f else 0.35f
        chipAccomp.alpha = if (song.supportsMode(Song.MODE_ACCOMP)) 1f else 0.35f
        chipSmart.alpha = if (song.supportsMode(Song.MODE_SMART)) 1f else 0.35f
    }

    private fun smartThreshold(): Float =
        (0.30f - Prefs.smartSensitivity * 0.0025f).coerceIn(0.03f, 0.4f)

    /** 智能模式：检测到你开口 → 人声音量渐隐；停唱约 2.5s 后渐回。 */
    private fun applySmartDucking() {
        if (phase != Phase.SING || singMode != Song.MODE_SMART) return
        val vp = vocalPlayer ?: return
        val now = SystemClock.elapsedRealtime()
        val ducking = (now - lastLoudAt) < 2500
        val target = if (ducking) 0f else 1f
        vocalCurrent += (target - vocalCurrent) * 0.3f
        if (kotlin.math.abs(vocalCurrent - target) < 0.01f) vocalCurrent = target
        vp.setVolume(vocalCurrent, vocalCurrent)
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

    private fun currentPhrase(): Phrase? =
        selected.getOrNull(selPos)?.let { phrases.getOrNull(it) }

    // —— 分组（一次唱 N 句） ——

    private fun rebuildGroups() {
        groups = if (selected.isEmpty()) emptyList() else selected.chunked(groupSize.coerceAtLeast(1))
        groupPos = 0
    }

    private fun currentGroup(): List<Int> = groups.getOrElse(groupPos) { emptyList() }

    private fun groupStartMs(): Long =
        currentGroup().firstOrNull()?.let { phrases[it].startMs } ?: 0L

    private fun groupEndMs(): Long =
        currentGroup().lastOrNull()?.let { phrases[it].endMs } ?: 0L

    /** 组内按时间定位当前句（歌词高亮用）。 */
    private fun activePhrase(posMs: Long? = null): Phrase? {
        val pos = posMs ?: (master?.currentPosition ?: 0).toLong()
        val g = currentGroup()
        for (idx in g) {
            val p = phrases[idx]
            if (pos < p.endMs) return p
        }
        return g.lastOrNull()?.let { phrases[it] }
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
    }

    private fun redoPhrase() {
        if (currentGroup().isEmpty()) return
        phase = Phase.DEMO
        seekTo(groupStartMs())
        applyVolumes()
        if (!playing) togglePlay()
        refreshPhraseUi()
    }

    private fun jumpPhrase(delta: Int) {
        if (groups.isEmpty()) return
        val target = (groupPos + delta).coerceIn(0, groups.size - 1)
        if (target == groupPos) return
        groupPos = target
        phase = Phase.DEMO
        seekTo(groupStartMs())
        applyVolumes()
        refreshPhraseUi()
        if (!playing) togglePlay()
    }

    /** 一次唱几句（分组轮次）。 */
    private fun pickGroupSize() {
        val sizes = intArrayOf(1, 2, 3, 4)
        val options = sizes.map { "一次唱 $it 句" }.toTypedArray()
        val checked = sizes.indexOf(groupSize).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("一次唱几句")
            .setSingleChoiceItems(options, checked) { dialog, which ->
                groupSize = sizes[which]
                rebuildGroups()
                phase = Phase.DEMO
                seekTo(groupStartMs())
                applyVolumes()
                refreshPhraseUi()
                dialog.dismiss()
                if (!playing) togglePlay()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 选择练习句（单选/多选）。 */
    private fun pickPhrases() {
        if (phrases.isEmpty()) return
        val labels = phrases.map { "第${it.index + 1}句　${it.text}" }.toTypedArray()
        val checked = BooleanArray(phrases.size) { selected.contains(it) }
        AlertDialog.Builder(this)
            .setTitle("选择练习句（可多选）")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("开始练习") { _, _ ->
                val picked = checked.withIndex().filter { it.value }.map { it.index }
                if (picked.isEmpty()) {
                    Toast.makeText(this, "至少选择一句", Toast.LENGTH_SHORT).show()
                } else {
                    selected = picked.toMutableList()
                    rebuildGroups()
                    phase = Phase.DEMO
                    pitchScore = null
                    rollView.resetTrail()
                    loadPitchOnly()
                    seekTo(groupStartMs())
                    applyVolumes()
                    refreshPhraseUi()
                    if (!playing) togglePlay()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun loadPitchOnly() {
        UiKit.bg {
            val ref = NotesRepo.load(this@PracticeActivity, song.id) ?: return@bg
            UiKit.ui {
                pitchScore = PitchScore(ref.notes)
                rollView.setNotes(ref.notes)
            }
        }
    }

    // ------------------------------------------------------------ UI

    private fun updateUi(pos: Int) {
        // 只在「你来唱」阶段采集音高：示范阶段的外放声音不计入打分/不画块
        val effF0 = if (phase == Phase.SING) micF0 else 0f
        val score = pitchScore
        val hit = score?.feed(pos.toLong(), effF0)
        rollView.update(pos.toLong(), effF0, hit)
        tvScore.text = (score?.score ?: 0).toString()

        // 多句轮次中随时间切换当前句高亮
        val activeIdx = activePhrase(pos.toLong())?.index ?: -1
        if (activeIdx != lastActiveIdx) {
            lastActiveIdx = activeIdx
            refreshPhraseUi()
        }

        // 底部进度轴 + 红点计时（设计稿底部层）
        val total = master?.duration ?: (song.duration * 1000).toInt()
        if (total > 0) {
            seekProgress.progress = (pos.toLong() * 1000 / total).toInt().coerceIn(0, 1000)
            tvRecordStatus.text = "练唱 ${UiKit.fmtTime(pos.toLong())}/${UiKit.fmtTime(total.toLong())}"
        }
        tvRecordDot.alpha = if ((SystemClock.elapsedRealtime() / 500) % 2 == 0L) 1f else 0.25f
    }

    private fun refreshPhraseUi() {
        val isDemo = phase == Phase.DEMO
        val phrase = activePhrase()
        // 音准器窗口 = 当前轮（1~N 句）拉满
        if (currentGroup().isNotEmpty()) rollView.setPhraseWindow(groupStartMs(), groupEndMs())
        tvPhase.text = when {
            groupSize > 1 && isDemo -> "🎤 系统唱 ${currentGroup().size} 句（示范）"
            groupSize > 1 -> "🎙 你来唱 ${currentGroup().size} 句（跟唱）"
            isDemo -> "🎤 系统唱一句（示范）"
            else -> "🎙 你来唱一句（跟唱）"
        }
        tvPhraseInfo.text = "第 ${groupPos + 1} / ${groups.size} 轮 · 每轮 ${groupSize} 句" +
            if (phrase != null) "（当前第 ${phrase.index + 1} 句）" else ""
        findViewById<Button>(R.id.btnGroupSize).text = "一次唱 ${groupSize} 句"
        tvLineCurrent.text = phrase?.text ?: ""
        // 多句歌词上下文：前一句 + 当前句 + 后两句（按练习顺序）
        val activeSelPos = phrase?.let { p -> selected.indexOf(p.index).coerceAtLeast(0) } ?: 0
        val prev = selected.getOrNull(activeSelPos - 1)?.let { phrases[it].text }
        tvLinePrev.text = prev ?: ""
        val nextLines = (1..2).mapNotNull { step ->
            selected.getOrNull(activeSelPos + step)?.let { phrases[it].text }
        }
        if (nextLines.isEmpty()) {
            tvLineNext.text = "（最后一句）"
        } else {
            // 亮度层级：越往后越淡（回森式）
            val joined = nextLines.joinToString("\n")
            val span = android.text.SpannableString(joined)
            var off = 0
            for ((i, line) in nextLines.withIndex()) {
                val color = if (i == 0) 0xB3FFFFFF.toInt() else 0x59FFFFFF.toInt()
                span.setSpan(
                    android.text.style.ForegroundColorSpan(color),
                    off, off + line.length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                off += line.length + 1
            }
            tvLineNext.text = span
        }
    }

    private fun startRecorder() {
        if (recorder != null || !micGranted) return
        val dir = getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: filesDir
        val rec = WavRecorder(File(dir, "practice_${System.currentTimeMillis()}.wav"))
        rec.onLevel = { level ->
            micLevel = level
            if (level > smartThreshold()) lastLoudAt = SystemClock.elapsedRealtime()
            UiKit.ui { pbMic.progress = (level * 100).toInt() }
        }
        rec.onFrame = { buf, n ->
            micF0 = PitchDetector.detect(buf, n, WavRecorder.SAMPLE_RATE)
        }
        if (rec.start()) recorder = rec
    }

    // ------------------------------------------------------------ 结束

    private fun finishPractice() {
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
        recorder?.discard()   // 练唱不保存录音
        recorder = null

        val score = pitchScore
        val msg = if (score != null && score.frames > 0) {
            "🎯 音准得分 ${score.score}（${score.grade} 级）\n共练习 ${selected.size} 句"
        } else {
            "共练习 ${selected.size} 句"
        }
        AlertDialog.Builder(this)
            .setTitle("练唱完成 🎉")
            .setMessage(msg)
            .setPositiveButton("返回") { _, _ -> finish() }
            .setNeutralButton("再练一次") { _, _ -> recreate() }
            .setCancelable(false)
            .show()
    }

    private fun confirmExit() {
        if (finished) {
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("退出练唱")
            .setMessage("确定退出？本次练唱不保存录音。")
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
            .setNegativeButton("继续练", null)
            .show()
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
