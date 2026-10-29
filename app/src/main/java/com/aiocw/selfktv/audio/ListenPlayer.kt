package com.aiocw.selfktv.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import com.aiocw.selfktv.data.Prefs
import com.aiocw.selfktv.net.ApiClient
import com.aiocw.selfktv.net.Song
import com.aiocw.selfktv.ui.UiKit
import android.os.SystemClock

/**
 * 听歌播放器（全局单例，MediaPlayer 封装）。
 *
 * - 音轨选取：默认原曲（跟随用户选择的音轨，缺失时按 original → vocal → accomp 兜底）
 * - 音轨选择存内存（跟随播放会话），可切换音轨并保持进度续播
 * - 本地缓存优先：有缓存直接播本地文件，在线播放的同时后台写缓存（按音轨分文件）
 * - 上一首/下一首由播放队列 + 播放模式驱动，onCompletion 自动下一首
 * - 播放模式（列表循环 / 随机 / 单曲循环）存 Prefs，全局生效
 * - 空态（无当前歌曲）点播放 → 直接播放列表第一首（必要时恢复队列 / 查曲库）
 * - 状态通过 [Listener] 回调给 UI（主线程）
 */
object ListenPlayer {

    /** 播放模式：列表循环 → 随机 → 单曲循环。 */
    enum class PlayMode(val key: String) {
        /** 列表循环：顺序播放，队列播完回队首继续。 */
        LIST_LOOP("list_loop"),

        /** 随机：下一首随机取队列中不同曲。 */
        SHUFFLE("shuffle"),

        /** 单曲循环：当前曲播完重播当前曲。 */
        SINGLE_LOOP("single_loop");

        companion object {
            fun fromKey(key: String?): PlayMode =
                values().firstOrNull { it.key == key } ?: LIST_LOOP
        }
    }

    /** 音轨类型（对应 song.tracks 的 original / vocal / accomp）。 */
    enum class TrackKind(val key: String) {
        ORIGINAL("original"),
        VOCAL("vocal"),
        ACCOMP("accomp");

        companion object {
            fun fromKey(key: String?): TrackKind =
                values().firstOrNull { it.key == key } ?: ORIGINAL
        }
    }

    interface Listener {
        /** 当前歌曲变化（含停止后置空的刷新）。 */
        fun onSongChanged() {}

        /** 播放/暂停状态变化。 */
        fun onPlayStateChanged() {}

        /** 队列内容变化。 */
        fun onQueueChanged() {}

        /** 音轨切换（当前歌曲不变）。 */
        fun onTrackChanged() {}

        /** 播放模式切换。 */
        fun onPlayModeChanged() {}

        /** 播放出错。 */
        fun onError(msg: String) {}
    }

    private var app: Context? = null
    private var player: MediaPlayer? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 播放队列（UI 真源）。 */
    val queue: MutableList<Song> = mutableListOf()

    /** 当前歌曲（可能独立于队列播放）。 */
    var current: Song? = null
        private set

    /** 是否正在播放。 */
    var isPlaying: Boolean = false
        private set

    /** 播放模式（存 Prefs，全局生效）。 */
    var playMode: PlayMode
        get() = PlayMode.fromKey(Prefs.listenPlayMode)
        set(value) {
            Prefs.listenPlayMode = value.key
            dispatch { it.onPlayModeChanged() }
        }

    /** 音轨选择（内存态，跟随播放会话，不落盘）。 */
    var selectedTrack: TrackKind = TrackKind.ORIGINAL
        private set

    /** 当前歌曲实际使用的音轨（按选中轨 + 缺轨兜底解析而来）。 */
    var currentTrack: TrackKind = TrackKind.ORIGINAL
        private set

    /** 状态回调（可多端同时注册：列表页播放条 / 歌曲详情页 / 后续页面，销毁时各自注销）。 */
    private val listeners = java.util.concurrent.CopyOnWriteArraySet<Listener>()

    /** 注册状态回调（幂等，重复注册同一实例只保留一份）。 */
    fun addListener(l: Listener) {
        listeners.add(l)
    }

    /** 注销状态回调。 */
    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    fun init(context: Context) {
        app = context.applicationContext
        ListenMediaSession.init(context.applicationContext)
    }

    val durationMs: Int
        get() = try { player?.duration ?: 0 } catch (_: Exception) { 0 }

    val positionMs: Int
        get() = try { player?.currentPosition ?: 0 } catch (_: Exception) { 0 }

    /** 播放指定歌曲（在队列中则由队列驱动上下曲，否则独立播放）。 */
    fun play(song: Song) = startPlayback(song)

    /** 播放队列中第 index 首。 */
    fun playAt(index: Int) {
        queue.getOrNull(index)?.let { startPlayback(it) }
    }

    /** 整份替换队列并从第一首播放。 */
    fun playAll(songs: List<Song>) {
        queue.clear()
        queue.addAll(songs)
        dispatch { it.onQueueChanged() }
        pushQueueToServer()
        songs.firstOrNull()?.let { startPlayback(it) }
    }

    /**
     * 歌曲页「▶ 播放」：自动入队（已在队列则直接播）后播放，
     * 插到当前曲之后（不在播则追加到队尾），并同步服务器队列。
     */
    fun playInQueue(song: Song) {
        if (queue.none { it.id == song.id }) {
            val curIdx = queue.indexOfFirst { it.id == current?.id }
            val at = if (curIdx >= 0) curIdx + 1 else queue.size
            queue.add(at, song)
            dispatch { it.onQueueChanged() }
            pushQueueToServer()
        }
        startPlayback(song)
    }

    /** 从服务器恢复队列：正在播放的歌保留在队列中，避免打断播放。 */
    fun adoptQueue(songs: List<Song>) {
        queue.clear()
        queue.addAll(songs)
        val cur = current
        if (cur != null && queue.indexOfFirst { it.id == cur.id } < 0) {
            queue.add(0, cur)
        }
        dispatch { it.onQueueChanged() }
    }

    /** 清空队列（不动当前播放）。 */
    fun clearQueue() {
        queue.clear()
        dispatch { it.onQueueChanged() }
        pushQueueToServer()
    }

    /** 队列被 UI 直接改动后刷新。 */
    fun queueUpdated() {
        dispatch { it.onQueueChanged() }
        pushQueueToServer()
    }

    // ------------------------------------------------ 队列双向同步（与网页端）

    /** 本地队列最近一次推送时间（推送在途时轮询不覆盖）。 */
    @Volatile
    private var lastQueuePush = 0L

    private var queueSyncRunning = false

    /** 队列任何变更后整份 PUT 到服务器（与网页端同一同步语义）。 */
    fun pushQueueToServer() {
        val ids = queue.map { it.id }
        lastQueuePush = SystemClock.uptimeMillis()
        UiKit.bg {
            try {
                ApiClient.listenQueueReplace(ids)
            } catch (_: Exception) {
                // 同步失败不阻断播放；下次变更或轮询时对齐
            }
        }
    }

    /** 启动队列双向同步轮询（听歌页 / 歌词页可见时调用，5s 一次）。 */
    fun startQueueSync() {
        if (queueSyncRunning) return
        queueSyncRunning = true
        mainHandler.post(queueSyncTick)
    }

    /** 停止轮询（页面不可见时调用）。 */
    fun stopQueueSync() {
        queueSyncRunning = false
        mainHandler.removeCallbacks(queueSyncTick)
    }

    private val queueSyncTick = object : Runnable {
        override fun run() {
            if (!queueSyncRunning) return
            UiKit.bg {
                try {
                    val server = ApiClient.listenQueue()
                    UiKit.ui {
                        // 本地刚推送过（在途）不覆盖，避免互相打架
                        if (SystemClock.uptimeMillis() - lastQueuePush > 3000) {
                            val serverIds = server.map { it.id }
                            if (serverIds != queue.map { it.id }) {
                                queue.clear()
                                queue.addAll(server)
                                dispatch { it.onQueueChanged() }
                            }
                        }
                    }
                } catch (_: Exception) {
                    // 离线等场景忽略
                }
            }
            mainHandler.postDelayed(this, 5000)
        }
    }

    /**
     * 按指定音轨播放歌曲。
     * 同一首歌切换音轨时保持当前进度续播（[keepPositionMs]，不传则从头播）；
     * 非当前歌曲则用所选音轨播放并自动入队（“只播放人声/伴奏”）。
     */
    fun playWithTrack(song: Song, trackKind: TrackKind, keepPositionMs: Int = 0) {
        selectedTrack = trackKind
        val sameSong = current?.id == song.id
        if (!sameSong) {
            // 非当前歌曲：用所选音轨开始播放并自动入队
            currentTrack = resolveTrack(song)
            dispatch { it.onTrackChanged() }
            playInQueue(song)
            return
        }
        currentTrack = resolveTrack(song)
        dispatch { it.onTrackChanged() }
        startPlayback(song, keepMs = keepPositionMs, autoplay = isPlaying)
    }

    /** 上一首。 */
    fun previous() {
        val cur = current
        if (queue.isEmpty()) {
            cur?.let { startPlayback(it) }
            return
        }
        if (playMode == PlayMode.SHUFFLE) {
            startPlayback(pickShuffled(cur))
            return
        }
        val i = queue.indexOfFirst { it.id == cur?.id }
        startPlayback(if (i > 0) queue[i - 1] else queue.last())
    }

    /** 下一首（队列 + 播放模式驱动）。 */
    fun next() = advance(manual = true)

    /** 播放/暂停切换（空态点播放 → 直接播放列表第一首）。 */
    fun toggle() {
        if (current == null) {
            playFirstAvailable()
            return
        }
        if (isPlaying) pause() else resume()
    }

    fun pause() {
        try { player?.pause() } catch (_: Exception) {}
        isPlaying = false
        ListenMediaSession.onPlayback(false, positionMs, durationMs)
        dispatch { it.onPlayStateChanged() }
    }

    fun resume() {
        val p = player
        if (p == null) {
            val cur = current
            if (cur != null) startPlayback(cur) else playFirstAvailable()
            return
        }
        try {
            p.start()
            isPlaying = true
            ListenMediaSession.onPlayback(true, positionMs, durationMs)
            dispatch { it.onPlayStateChanged() }
        } catch (_: Exception) {
            current?.let { startPlayback(it) }
        }
    }

    fun seekTo(ms: Int) {
        try { player?.seekTo(ms.coerceAtLeast(0)) } catch (_: Exception) {}
        ListenMediaSession.onPlayback(isPlaying, positionMs, durationMs)
    }

    /**
     * 空态播放：当前没有播放内容时直接播放列表第一首。
     * 本地队列为空先 GET /api/listen/queue 恢复；仍为空取曲库第一首；
     * 曲库为空回调 onError("曲库还没有歌曲")。
     */
    fun playFirstAvailable() {
        if (current != null) return
        queue.firstOrNull()?.let {
            startPlayback(it)
            return
        }
        UiKit.bg {
            // 1) 恢复服务器队列
            try {
                val serverQueue = ApiClient.listenQueue()
                if (serverQueue.isNotEmpty()) {
                    UiKit.ui {
                        adoptQueue(serverQueue)
                        queue.firstOrNull()?.let { first -> startPlayback(first) }
                    }
                    return@bg
                }
            } catch (_: Exception) {
                // 离线等场景继续兜底
            }
            // 2) 曲库第一首
            try {
                val first = ApiClient.songs("").firstOrNull()
                if (first == null) {
                    dispatch { it.onError("曲库还没有歌曲") }
                    return@bg
                }
                UiKit.ui { playAll(listOf(first)) }
                // 队列变更仍走整份 PUT 同步语义
                try {
                    ApiClient.listenQueueReplace(listOf(first.id))
                } catch (_: Exception) {
                }
            } catch (e: Exception) {
                dispatch { it.onError("加载曲库失败：${e.message}") }
            }
        }
    }

    // ------------------------------------------------ 内部

    /** 播放模式驱动的自动/手动切歌。 */
    private fun advance(manual: Boolean) {
        val cur = current
        // 单曲循环：播完（自动）重播当前曲
        if (!manual && playMode == PlayMode.SINGLE_LOOP && cur != null) {
            startPlayback(cur)
            return
        }
        if (queue.isEmpty()) {
            if (!manual) stopPlaybackState()
            return
        }
        if (playMode == PlayMode.SHUFFLE) {
            startPlayback(pickShuffled(cur))
            return
        }
        val i = queue.indexOfFirst { it.id == cur?.id }
        when {
            // 当前曲不在队列（独立播放）：手动下一首取队首，自动播完停止
            i < 0 -> if (manual) startPlayback(queue.first()) else stopPlaybackState()
            i + 1 < queue.size -> startPlayback(queue[i + 1])
            // 队列到尾：列表循环回队首继续
            manual || playMode == PlayMode.LIST_LOOP -> startPlayback(queue.first())
            else -> stopPlaybackState()
        }
    }

    /** 随机取队列中与当前不同的一首（单曲队列时重播自己）。 */
    private fun pickShuffled(cur: Song?): Song {
        val candidates = queue.filter { it.id != cur?.id }
        return if (candidates.isNotEmpty()) candidates.random() else queue.random()
    }

    /** 解析实际播放音轨：优先用户选中轨，缺失按 original → vocal → accomp 兜底。 */
    fun resolveTrack(song: Song): TrackKind =
        (listOf(selectedTrack, TrackKind.ORIGINAL, TrackKind.VOCAL, TrackKind.ACCOMP))
            .firstOrNull { !trackUrl(song, it).isNullOrEmpty() }
            ?: TrackKind.ORIGINAL

    private fun trackUrl(song: Song, kind: TrackKind): String? = when (kind) {
        TrackKind.ORIGINAL -> song.originalUrl
        TrackKind.VOCAL -> song.vocalUrl
        TrackKind.ACCOMP -> song.accompUrl
    }

    private fun startPlayback(song: Song, keepMs: Int = 0, autoplay: Boolean = true) {
        releasePlayer()
        current = song
        val track = resolveTrack(song)
        currentTrack = track
        ListenMediaSession.onSong(song, (song.duration * 1000).toInt())
        dispatch { it.onSongChanged() }

        val ctx = app ?: return
        val trackUrl = trackUrl(song, track)
        if (trackUrl.isNullOrEmpty()) {
            isPlaying = false
            ListenMediaSession.onPlayback(false, 0, 0)
            dispatch { it.onPlayStateChanged() }
            dispatch { it.onError("「${song.title}」缺少可播放音轨") }
            return
        }

        // 有缓存直接播本地（顺便规范化不兼容 WAV）
        val cached = runCatching { ListenCache.fileOf(song.id, track.key) }.getOrNull()
        if (cached != null && cached.exists() && cached.length() > 0L) {
            ListenCache.touch(song.id, track.key)
            if (ListenCache.ensurePlayable(cached)) {
                playOnPlayer(song, track, keepMs, autoplay, fromCache = true) { p ->
                    p.setDataSource(cached.absolutePath)
                }
                return
            }
            runCatching { cached.delete() } // 损坏/无法转换：删掉重来
        }

        // 统一「先缓存再播放」：原曲/人声/伴奏都下载进缓存后本地播放，
        // 避免切换音轨时临时流式缓存导致的卡顿与解码错误；播过之后秒开
        fetchThenPlay(song, track, trackUrl, keepMs, autoplay)
    }

    /** 下载音轨到缓存后本地播放（下载完成前保持缓冲态；进度/曲目变了就放弃）。 */
    private fun fetchThenPlay(song: Song, track: TrackKind, url: String, keepMs: Int, autoplay: Boolean) {
        ListenCache.putAsync(song.id, track.key, url)
        val started = android.os.SystemClock.uptimeMillis()
        val check = object : Runnable {
            override fun run() {
                if (current?.id != song.id || currentTrack != track) return
                val f = runCatching { ListenCache.fileOf(song.id, track.key) }.getOrNull()
                val ready = f != null && f.exists() && f.length() > 0L &&
                    !ListenCache.isDownloading(song.id, track.key)
                if (ready) {
                    if (ListenCache.ensurePlayable(f!!)) {
                        ListenCache.touch(song.id, track.key)
                        playOnPlayer(song, track, keepMs, autoplay, fromCache = true) { p ->
                            p.setDataSource(f.absolutePath)
                        }
                    } else {
                        runCatching { f.delete() }
                        dispatch { it.onError("「${song.title}」音轨无法解码") }
                    }
                } else if (android.os.SystemClock.uptimeMillis() - started > 120_000) {
                    dispatch { it.onError("「${song.title}」音轨下载超时") }
                } else {
                    mainHandler.postDelayed(this, 300)
                }
            }
        }
        mainHandler.post(check)
    }

    /** 构建并启动 MediaPlayer；[configure] 设置音源（本地文件 / 在线流）。 */
    private fun playOnPlayer(
        song: Song,
        track: TrackKind,
        keepMs: Int,
        autoplay: Boolean,
        fromCache: Boolean,
        configure: (MediaPlayer) -> Unit,
    ) {
        var retried = false
        try {
            val p = MediaPlayer()
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            configure(p)
            p.setOnPreparedListener { mp ->
                try {
                    if (autoplay) {
                        mp.start()
                        isPlaying = true
                    } else {
                        isPlaying = false
                    }
                    if (keepMs > 0) {
                        // 先起播再 seek（HTTP 流上 prepare 后直接 seek 不稳）
                        val dur = mp.duration
                        val target = if (dur > 0) keepMs.coerceIn(0, (dur - 200).coerceAtLeast(0)) else keepMs
                        try { mp.seekTo(target) } catch (_: Exception) {}
                    }
                    ListenMediaSession.onPlayback(isPlaying, mp.currentPosition, mp.duration)
                    dispatch { l -> l.onPlayStateChanged() }
                } catch (_: Exception) {
                    isPlaying = false
                    dispatch { l -> l.onPlayStateChanged() }
                }
            }
            p.setOnCompletionListener {
                isPlaying = false
                ListenMediaSession.onPlayback(false, positionMs, durationMs)
                autoNext()
            }
            p.setOnErrorListener { _, what, extra ->
                releasePlayer()
                isPlaying = false
                ListenMediaSession.onPlayback(false, 0, 0)
                dispatch { l -> l.onPlayStateChanged() }
                val url = trackUrl(song, track)
                if (!retried && !url.isNullOrEmpty()) {
                    // 自愈：缓存损坏 / 流式播放失败 → 重新下载后本地播放（重试一次）
                    retried = true
                    runCatching { ListenCache.fileOf(song.id, track.key).delete() }
                    fetchThenPlay(song, track, url, keepMs, autoplay)
                } else {
                    dispatch { l -> l.onError("播放出错 ($what/$extra)") }
                }
                true
            }
            player = p
            p.prepareAsync()
        } catch (e: Exception) {
            isPlaying = false
            releasePlayer()
            dispatch { it.onPlayStateChanged() }
            dispatch { it.onError("无法播放：${e.message}") }
        }
    }

    /** 播完自动下一首（按播放模式选曲）。 */
    private fun autoNext() = advance(manual = false)

    private fun stopPlaybackState() {
        isPlaying = false
        ListenMediaSession.onPlayback(false, 0, 0)
        dispatch { it.onPlayStateChanged() }
    }

    private fun releasePlayer() {
        val p = player ?: return
        player = null
        try {
            p.reset()
        } catch (_: Exception) {
        }
        try {
            p.release()
        } catch (_: Exception) {
        }
    }

    private fun dispatch(action: (Listener) -> Unit) {
        UiKit.ui {
            for (l in listeners.toList()) {
                try {
                    action(l)
                } catch (_: Exception) {
                    // 单个 UI 回调异常不影响其他监听器
                }
            }
        }
    }
}
