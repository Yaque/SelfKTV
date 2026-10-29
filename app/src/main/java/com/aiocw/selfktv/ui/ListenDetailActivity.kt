package com.aiocw.selfktv.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.aiocw.selfktv.R
import com.aiocw.selfktv.audio.ListenPlayer
import com.aiocw.selfktv.audio.LrcParser
import com.aiocw.selfktv.net.ApiClient
import com.aiocw.selfktv.net.ListenPlaylist
import com.aiocw.selfktv.net.Song

/**
 * 听歌 · 歌曲详情页。
 *
 * - 大封面 / 歌名 / 歌手
 * - 歌词：GET /api/songs/{id}/lyrics，LRC 解析 + 与播放进度联动（当前行高亮自动滚动居中，
 *   点歌词行 seek 到该时间点）；非 LRC 纯文本静态展示；404/空显示「暂无歌词」
 * - 播放控件：上一首 / 播放暂停 / 下一首 + SeekBar + 当前/总时长（与 ListenPlayer 状态同步）
 * - 播放模式：列表循环 → 随机 → 单曲循环（存 Prefs，全局生效）
 * - 音轨选择：原曲 / 人声 / 伴奏（只显示存在的轨；切换保持进度续播）
 * - 收藏 / 取消收藏（乐观更新 + 失败回滚）
 * - 歌单管理：多选列表（勾选 = 当前歌在该歌单），点选即加入/移出，支持新建歌单并加入
 */
class ListenDetailActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_SONG_ID = "song_id"

        fun intentFor(context: Context, songId: Int): Intent =
            Intent(context, ListenDetailActivity::class.java).putExtra(EXTRA_SONG_ID, songId)
    }

    /** 当前展示的歌曲（可能与正在播放的歌不同）。 */
    private var song: Song? = null

    /** 页面是否跟随播放器切歌（展示的是当前播放曲时为 true）。 */
    private var followPlayer = false

    private var lyricLines: List<LrcParser.Line> = emptyList()
    private val favIds = mutableSetOf<Int>()
    private val playlistIdsWithSong = mutableSetOf<Int>()

    private lateinit var ivCover: ImageView
    private lateinit var tvTitle: TextView
    private lateinit var tvArtist: TextView
    private lateinit var chipOriginal: TextView
    private lateinit var chipVocal: TextView
    private lateinit var chipAccomp: TextView
    private lateinit var tvFav: TextView
    private lateinit var btnPlaylist: TextView
    private lateinit var btnMode: TextView
    private lateinit var lyricView: com.aiocw.selfktv.ui.widget.LyricView
    private lateinit var scrollPlain: ScrollView
    private lateinit var tvPlainLyric: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var tvTimeCur: TextView
    private lateinit var tvTimeTotal: TextView
    private lateinit var tvPlayPause: TextView

    private val handler = Handler(Looper.getMainLooper())

    /** 进度 + 歌词联动刷新。 */
    private val ticker = object : Runnable {
        override fun run() {
            if (!isFinishing && !isDestroyed) {
                syncProgress()
                handler.postDelayed(this, 300)
            }
        }
    }

    /** 歌词平滑滚动：逐帧刷新进度插值，避免 300ms 步进造成的卡顿感。 */
    private val lyricFrame = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            if (lyricLines.isNotEmpty() && !isTracking && isCurrentSong()) {
                lyricView.update(currentPosMs())
            }
            lyricView.postOnAnimation(this)
        }
    }

    private val playerListener = object : ListenPlayer.Listener {
        override fun onSongChanged() {
            val cur = ListenPlayer.current
            val shown = song
            when {
                cur == null -> bindPlayer()
                shown == null -> {
                    song = cur
                    followPlayer = true
                    bindSong()
                }
                // 展示的就是当前曲（重新播放 / 切音轨）：只刷新控件
                cur.id == shown.id -> {
                    followPlayer = true
                    bindPlayer()
                }
                // 播放器切歌：页面跟随
                followPlayer -> {
                    song = cur
                    bindSong()
                }
                else -> bindPlayer()
            }
        }

        override fun onPlayStateChanged() {
            bindPlayer()
        }

        override fun onTrackChanged() {
            bindTracks()
            bindPlayer()
        }

        override fun onPlayModeChanged() {
            bindMode()
        }

        override fun onError(msg: String) {
            toast(msg)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_listen_detail)
        UiKit.applySystemBars(this)

        ivCover = findViewById(R.id.ivCover)
        tvTitle = findViewById(R.id.tvTitle)
        tvArtist = findViewById(R.id.tvArtist)
        chipOriginal = findViewById(R.id.chipOriginal)
        chipVocal = findViewById(R.id.chipVocal)
        chipAccomp = findViewById(R.id.chipAccomp)
        tvFav = findViewById(R.id.tvFav)
        btnPlaylist = findViewById(R.id.btnPlaylist)
        btnMode = findViewById(R.id.btnMode)
        lyricView = findViewById(R.id.lyricView)
        scrollPlain = findViewById(R.id.scrollPlain)
        tvPlainLyric = findViewById(R.id.tvPlainLyric)
        seekBar = findViewById(R.id.seekBar)
        tvTimeCur = findViewById(R.id.tvTimeCur)
        tvTimeTotal = findViewById(R.id.tvTimeTotal)
        tvPlayPause = findViewById(R.id.tvPlayPause)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tvPrev).setOnClickListener { ListenPlayer.previous() }
        findViewById<TextView>(R.id.tvNext).setOnClickListener { ListenPlayer.next() }

        tvPlayPause.setOnClickListener {
            val cur = ListenPlayer.current
            val shown = song
            when {
                // 空态：点播放直接播放列表第一首
                cur == null -> ListenPlayer.toggle()
                // 展示的不是当前播放曲：播放本曲（自动入队）
                shown != null && cur.id != shown.id -> ListenPlayer.playInQueue(shown)
                else -> ListenPlayer.toggle()
            }
            bindPlayer()
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    tvTimeCur.text = UiKit.fmtTime(p.toLong())
                    if (lyricLines.isNotEmpty()) lyricView.update(p.toLong())
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                isTracking = true
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                isTracking = false
                if (isCurrentSong()) ListenPlayer.seekTo(sb?.progress ?: 0)
                syncProgress()
            }
        })

        lyricView.onLineTap = { line ->
            if (isCurrentSong()) {
                ListenPlayer.seekTo(line.timeMs.toInt())
                syncProgress()
            }
        }

        chipOriginal.setOnClickListener { switchTrack(ListenPlayer.TrackKind.ORIGINAL) }
        chipVocal.setOnClickListener { switchTrack(ListenPlayer.TrackKind.VOCAL) }
        chipAccomp.setOnClickListener { switchTrack(ListenPlayer.TrackKind.ACCOMP) }

        tvFav.setOnClickListener { toggleFav() }
        btnPlaylist.setOnClickListener { openPlaylistDialog() }
        btnMode.setOnClickListener {
            ListenPlayer.playMode = when (ListenPlayer.playMode) {
                ListenPlayer.PlayMode.LIST_LOOP -> ListenPlayer.PlayMode.SHUFFLE
                ListenPlayer.PlayMode.SHUFFLE -> ListenPlayer.PlayMode.SINGLE_LOOP
                ListenPlayer.PlayMode.SINGLE_LOOP -> ListenPlayer.PlayMode.LIST_LOOP
            }
        }

        ListenPlayer.addListener(playerListener)
        bindMode()
        resolveSong(intent.getIntExtra(EXTRA_SONG_ID, -1))
    }

    override fun onResume() {
        super.onResume()
        ListenPlayer.addListener(playerListener)
        ListenPlayer.startQueueSync() // 与网页端队列双向同步
        handler.post(ticker)
        lyricView.postOnAnimation(lyricFrame)
        loadFavState()
    }

    override fun onPause() {
        super.onPause()
        ListenPlayer.stopQueueSync()
        handler.removeCallbacks(ticker)
        lyricView.removeCallbacks(lyricFrame)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(ticker)
        lyricView.removeCallbacks(lyricFrame)
        ListenPlayer.removeListener(playerListener)
    }

    // ------------------------------------------------ 歌曲解析 / 绑定

    /** 按 songId 解析歌曲：内存队列 → GET /api/songs/{id} → 队列 / 曲库兜底。 */
    private fun resolveSong(songId: Int) {
        if (songId <= 0) {
            toast("歌曲不存在")
            finish()
            return
        }
        val local = ListenPlayer.queue.firstOrNull { it.id == songId }
            ?: ListenPlayer.current?.takeIf { it.id == songId }
        if (local != null) {
            onSongResolved(local)
            return
        }
        UiKit.bg {
            val found: Song? = try {
                ApiClient.song(songId)
            } catch (_: Exception) {
                try {
                    ApiClient.listenQueue().firstOrNull { it.id == songId }
                } catch (_: Exception) {
                    try {
                        ApiClient.songs("").firstOrNull { it.id == songId }
                    } catch (_: Exception) {
                        null
                    }
                }
            }
            UiKit.ui {
                if (isFinishing || isDestroyed) return@ui
                if (found == null) {
                    toast("歌曲不存在或加载失败")
                    finish()
                } else {
                    onSongResolved(found)
                }
            }
        }
    }

    private fun onSongResolved(s: Song) {
        song = s
        followPlayer = ListenPlayer.current?.id == s.id
        bindSong()
    }

    private fun bindSong() {
        val s = song ?: return
        tvTitle.text = s.title
        tvArtist.text = s.artist.ifEmpty { getString(R.string.listen_unknown_artist) }
        UiKit.loadCover(s.coverUrl, ivCover, R.drawable.bg_cover_placeholder)
        bindTracks()
        bindPlayer()
        bindMode()
        bindFav()
        loadLyrics()
        loadPlaylistState()
    }

    // ------------------------------------------------ 歌词

    private fun loadLyrics() {
        val s = song ?: return
        lyricLines = emptyList()
        lyricView.setLyrics(emptyList())
        showPlainLyric(getString(R.string.listen_lyric_loading))
        UiKit.bg {
            val text = ApiClient.getLyric(s.lyricUrl ?: "/api/songs/${s.id}/lyrics")
            // 无时间标签的纯文本返回空（静态展示，不做均匀分配）
            val lines = LrcParser.parse(text)
            UiKit.ui {
                if (isFinishing || isDestroyed) return@ui
                if (song?.id != s.id) return@ui
                when {
                    lines.isNotEmpty() -> {
                        lyricLines = lines
                        scrollPlain.visibility = View.GONE
                        lyricView.visibility = View.VISIBLE
                        lyricView.setLyrics(lines)
                        lyricView.update(currentPosMs())
                    }
                    !text.isNullOrBlank() -> showPlainLyric(text.trim())
                    else -> showPlainLyric(getString(R.string.listen_lyric_placeholder))
                }
            }
        }
    }

    private fun showPlainLyric(text: String) {
        lyricView.visibility = View.GONE
        scrollPlain.visibility = View.VISIBLE
        tvPlainLyric.text = text
    }

    // ------------------------------------------------ 播放控件同步

    private fun isCurrentSong(): Boolean {
        val s = song ?: return false
        return ListenPlayer.current?.id == s.id
    }

    private fun currentPosMs(): Long =
        if (isCurrentSong()) ListenPlayer.positionMs.toLong() else 0L

    private fun bindPlayer() {
        val s = song
        val current = isCurrentSong()
        if (current) {
            val dur = (ListenPlayer.durationMs.takeIf { it > 0 } ?: ((s?.duration ?: 0.0) * 1000).toInt())
                .coerceAtLeast(1)
            seekBar.max = dur
            seekBar.progress = ListenPlayer.positionMs.coerceIn(0, dur)
            tvTimeTotal.text = UiKit.fmtTime(dur.toLong())
            tvTimeCur.text = UiKit.fmtTime(ListenPlayer.positionMs.toLong())
            tvPlayPause.text = getString(if (ListenPlayer.isPlaying) R.string.listen_ctrl_pause else R.string.listen_ctrl_play)
        } else {
            val dur = ((s?.duration ?: 0.0) * 1000).toInt().coerceAtLeast(1)
            seekBar.max = dur
            seekBar.progress = 0
            tvTimeCur.text = getString(R.string.listen_time_zero)
            tvTimeTotal.text = UiKit.fmtTime(dur.toLong())
            tvPlayPause.text = getString(R.string.listen_ctrl_play)
        }
    }

    /** 300ms 轮询：进度条 + 歌词联动。 */
    private fun syncProgress() {
        val s = song ?: return
        if (isCurrentSong()) {
            val dur = (ListenPlayer.durationMs.takeIf { it > 0 } ?: ((s.duration) * 1000).toInt())
                .coerceAtLeast(1)
            seekBar.max = dur
            val pos = ListenPlayer.positionMs.coerceIn(0, dur)
            if (!isTracking) seekBar.progress = pos
            tvTimeCur.text = UiKit.fmtTime(pos.toLong())
            tvTimeTotal.text = UiKit.fmtTime(dur.toLong())
            tvPlayPause.text = getString(if (ListenPlayer.isPlaying) R.string.listen_ctrl_pause else R.string.listen_ctrl_play)
            if (lyricLines.isNotEmpty() && !isTracking) lyricView.update(pos.toLong())
        }
    }

    private var isTracking = false

    // ------------------------------------------------ 播放模式 / 音轨

    private fun bindMode() {
        btnMode.text = getString(
            when (ListenPlayer.playMode) {
                ListenPlayer.PlayMode.LIST_LOOP -> R.string.listen_mode_list_loop
                ListenPlayer.PlayMode.SHUFFLE -> R.string.listen_mode_shuffle
                ListenPlayer.PlayMode.SINGLE_LOOP -> R.string.listen_mode_single_loop
            }
        )
    }

    private fun bindTracks() {
        val s = song ?: return
        chipOriginal.visibility = if (s.originalUrl.isNullOrEmpty()) View.GONE else View.VISIBLE
        chipVocal.visibility = if (s.vocalUrl.isNullOrEmpty()) View.GONE else View.VISIBLE
        chipAccomp.visibility = if (s.accompUrl.isNullOrEmpty()) View.GONE else View.VISIBLE
        val active =
            if (isCurrentSong()) ListenPlayer.currentTrack else ListenPlayer.resolveTrack(s)
        chipOriginal.isSelected = active == ListenPlayer.TrackKind.ORIGINAL
        chipVocal.isSelected = active == ListenPlayer.TrackKind.VOCAL
        chipAccomp.isSelected = active == ListenPlayer.TrackKind.ACCOMP
    }

    /** 切换音轨：当前曲保持进度续播，非当前曲用所选音轨播放（自动入队）。 */
    private fun switchTrack(kind: ListenPlayer.TrackKind) {
        val s = song ?: return
        if (kind == ListenPlayer.TrackKind.ORIGINAL && s.originalUrl.isNullOrEmpty()) return
        if (kind == ListenPlayer.TrackKind.VOCAL && s.vocalUrl.isNullOrEmpty()) return
        if (kind == ListenPlayer.TrackKind.ACCOMP && s.accompUrl.isNullOrEmpty()) return
        val keepMs = if (isCurrentSong()) ListenPlayer.positionMs else 0
        ListenPlayer.playWithTrack(s, kind, keepMs)
        bindTracks()
    }

    // ------------------------------------------------ 收藏（乐观更新 + 失败回滚）

    private fun loadFavState() {
        UiKit.bg {
            try {
                val ids = ApiClient.listenFavorites().map { it.id }.toSet()
                UiKit.ui {
                    favIds.clear()
                    favIds.addAll(ids)
                    bindFav()
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun bindFav() {
        val fav = song?.let { favIds.contains(it.id) } == true
        tvFav.text = if (fav) "❤" else "♡"
        tvFav.setTextColor(
            getColor(if (fav) R.color.brand_green_dark else R.color.text_muted)
        )
    }

    private fun toggleFav() {
        val s = song ?: return
        val add = !favIds.contains(s.id)
        if (add) favIds.add(s.id) else favIds.remove(s.id)
        bindFav()
        UiKit.bg {
            try {
                if (add) ApiClient.listenFavoriteAdd(s.id)
                else ApiClient.listenFavoriteRemove(s.id)
            } catch (e: Exception) {
                if (add) favIds.remove(s.id) else favIds.add(s.id)
                UiKit.ui {
                    bindFav()
                    toast("收藏同步失败：${e.message}")
                }
            }
        }
    }

    // ------------------------------------------------ 歌单管理

    private fun loadPlaylistState() {
        val s = song ?: return
        UiKit.bg {
            try {
                val playlists = ApiClient.listenPlaylists()
                val withSong = playlists.filter {
                    try {
                        ApiClient.listenPlaylistSongs(it.id).second.any { p -> p.id == s.id }
                    } catch (_: Exception) {
                        false
                    }
                }.map { it.id }.toSet()
                UiKit.ui {
                    playlistIdsWithSong.clear()
                    playlistIdsWithSong.addAll(withSong)
                    updatePlaylistButton()
                }
            } catch (_: Exception) {
            }
        }
    }

    /** 按钮文案：不在任何歌单 →「加入歌单」；已在歌单 →「移出歌单」。 */
    private fun updatePlaylistButton() {
        btnPlaylist.text = getString(
            if (playlistIdsWithSong.isEmpty()) R.string.listen_playlist_join
            else R.string.listen_playlist_leave
        )
    }

    /** 多选列表：勾选 = 当前歌在该歌单，点选即加入/移出；支持新建歌单并加入。 */
    private fun openPlaylistDialog() {
        val s = song ?: return
        UiKit.bg {
            try {
                val playlists = ApiClient.listenPlaylists()
                val checked = BooleanArray(playlists.size)
                for ((i, pl) in playlists.withIndex()) {
                    val contains = try {
                        ApiClient.listenPlaylistSongs(pl.id).second.any { it.id == s.id }
                    } catch (_: Exception) {
                        playlistIdsWithSong.contains(pl.id)
                    }
                    checked[i] = contains
                    if (contains) playlistIdsWithSong.add(pl.id) else playlistIdsWithSong.remove(pl.id)
                }
                UiKit.ui {
                    if (isFinishing || isDestroyed) return@ui
                    updatePlaylistButton()
                    showPlaylistDialog(s, playlists, checked)
                }
            } catch (e: Exception) {
                toast("加载歌单失败：${e.message}")
            }
        }
    }

    private fun showPlaylistDialog(s: Song, playlists: List<ListenPlaylist>, checked: BooleanArray) {
        val names = playlists.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.listen_playlist_manage_title, s.title))
            .setMultiChoiceItems(names, checked) { dialog, which, isChecked ->
                val pl = playlists[which]
                if (isChecked) playlistIdsWithSong.add(pl.id) else playlistIdsWithSong.remove(pl.id)
                updatePlaylistButton()
                UiKit.bg {
                    try {
                        if (isChecked) ApiClient.listenPlaylistAddSong(pl.id, s.id)
                        else ApiClient.listenPlaylistRemoveSong(pl.id, s.id)
                    } catch (e: Exception) {
                        if (isChecked) playlistIdsWithSong.remove(pl.id)
                        else playlistIdsWithSong.add(pl.id)
                        UiKit.ui {
                            (dialog as? AlertDialog)?.listView?.setItemChecked(which, !isChecked)
                            updatePlaylistButton()
                            toast("歌单同步失败：${e.message}")
                        }
                    }
                }
            }
            .setNeutralButton(R.string.listen_playlist_new) { _, _ -> promptNewPlaylistAndAdd(s) }
            .setPositiveButton(R.string.listen_done, null)
            .show()
    }

    /** ＋ 新建歌单并加入。 */
    private fun promptNewPlaylistAndAdd(s: Song) {
        val et = EditText(this).apply {
            hint = getString(R.string.listen_playlist_name_hint)
            setPadding(48, 32, 48, 16)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.listen_playlist_new_title)
            .setView(et)
            .setPositiveButton(R.string.listen_ok) { _, _ ->
                val name = et.text.toString().trim()
                if (name.isEmpty()) {
                    toast(getString(R.string.listen_name_empty))
                    return@setPositiveButton
                }
                UiKit.bg {
                    try {
                        val pl = ApiClient.listenPlaylistCreate(name)
                        ApiClient.listenPlaylistAddSong(pl.id, s.id)
                        playlistIdsWithSong.add(pl.id)
                        UiKit.ui {
                            updatePlaylistButton()
                            toast("已加入「${pl.name}」")
                        }
                    } catch (e: Exception) {
                        toast("加入失败：${e.message}")
                    }
                }
            }
            .setNegativeButton(R.string.listen_cancel, null)
            .show()
    }

    // ------------------------------------------------ 小工具

    private fun toast(msg: String) {
        UiKit.ui { Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show() }
    }
}
