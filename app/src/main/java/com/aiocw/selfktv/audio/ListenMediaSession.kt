package com.aiocw.selfktv.audio

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import com.aiocw.selfktv.net.ApiClient
import com.aiocw.selfktv.net.Song
import com.aiocw.selfktv.ui.MainActivity
import java.io.File
import kotlin.concurrent.thread

/**
 * 系统媒体会话：让锁屏 / 控制中心 / 澎湃OS 灵动岛播放页识别歌曲信息。
 *
 * - 注册 [MediaSession] 并上报元数据（歌名 / 歌手 / 封面）与播放状态
 * - 响应系统媒体按钮（播放 / 暂停 / 上一首 / 下一首 / 进度拖动 / 停止）
 * - 播放期间持有音频焦点，被永久抢占时自动暂停
 * - 封面异步下载（磁盘缓存 + 降采样），完成后回填元数据
 */
object ListenMediaSession {

    private const val ART_MAX_DIM = 640

    private var app: Context? = null
    private var session: MediaSession? = null
    private var audioManager: AudioManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var currentSong: Song? = null
    private var lastDurationMs: Int = 0
    private var art: Bitmap? = null
    private var artSongId: Int = -1

    private var focusReq: AudioFocusRequest? = null
    private var focusGranted = false

    /** 通知 / 灵动岛用：当前曲目与封面、会话 token。 */
    val sessionToken: android.media.session.MediaSession.Token?
        get() = session?.sessionToken
    val hasSong: Boolean
        get() = currentSong != null
    val notifTitle: String
        get() = currentSong?.title ?: ""
    val notifArtist: String
        get() = currentSong?.artist ?: ""
    val notifArt: Bitmap?
        get() = art

    /** 播放中每 2s 刷新一次进度，保证系统面板进度条跟手。 */
    private val ticker = object : Runnable {
        override fun run() {
            if (ListenPlayer.isPlaying) {
                updateState(PlaybackState.STATE_PLAYING, ListenPlayer.positionMs, ListenPlayer.durationMs)
                mainHandler.postDelayed(this, 2000L)
            }
        }
    }

    fun init(context: Context) {
        if (session != null) return
        val ctx = context.applicationContext
        app = ctx
        audioManager = ctx.getSystemService(AudioManager::class.java)
        val s = MediaSession(ctx, "SelfKtvListen")
        s.setFlags(
            MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
        )
        s.setCallback(object : MediaSession.Callback() {
            override fun onPlay() {
                mainHandler.post { ListenPlayer.resume() }
            }

            override fun onPause() {
                mainHandler.post { ListenPlayer.pause() }
            }

            override fun onSkipToNext() {
                mainHandler.post { ListenPlayer.next() }
            }

            override fun onSkipToPrevious() {
                mainHandler.post { ListenPlayer.previous() }
            }

            override fun onSeekTo(pos: Long) {
                mainHandler.post { ListenPlayer.seekTo(pos.toInt()) }
            }

            override fun onStop() {
                mainHandler.post { ListenPlayer.pause() }
            }
        })
        try {
            val pi = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            s.setSessionActivity(pi)
        } catch (_: Exception) {
        }
        s.isActive = false // 有歌曲时才激活，避免系统出现空媒体卡片
        session = s
    }

    /** 歌曲切换：先推基础元数据（封面异步补），并声明缓冲中。 */
    fun onSong(song: Song?, durationMs: Int) {
        val s = session ?: return
        currentSong = song
        lastDurationMs = if (durationMs > 0) durationMs else ((song?.duration ?: 0.0) * 1000).toInt()
        try {
            s.isActive = song != null
        } catch (_: Exception) {
        }
        if (song == null) {
            art = null
            artSongId = -1
            s.setMetadata(null)
            updateState(PlaybackState.STATE_STOPPED, 0, 0)
            app?.let { MediaPlaybackService.stop(it) }
            return
        }
        if (artSongId != song.id) {
            art = null
            artSongId = -1
        }
        s.setMetadata(buildMetadata(song))
        updateState(PlaybackState.STATE_BUFFERING, 0, lastDurationMs)
        loadArt(song)
        requestFocus()
        app?.let { MediaPlaybackService.refresh(it) }
    }

    /** 播放状态变化（播放 / 暂停 / seek / 就绪）。 */
    fun onPlayback(playing: Boolean, positionMs: Int, durationMs: Int) {
        if (playing) requestFocus() else abandonFocus()
        if (durationMs > 0 && durationMs != lastDurationMs) {
            lastDurationMs = durationMs
            currentSong?.let { session?.setMetadata(buildMetadata(it)) }
        }
        updateState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, positionMs, durationMs)
        if (currentSong != null) app?.let { MediaPlaybackService.refresh(it) }
    }

    fun release() {
        mainHandler.removeCallbacks(ticker)
        abandonFocus()
        try {
            session?.release()
        } catch (_: Exception) {
        }
        session = null
        currentSong = null
        app?.let { MediaPlaybackService.stop(it) }
    }

    // ------------------------------------------------ 内部

    private fun buildMetadata(song: Song): MediaMetadata {
        val mb = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, song.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, song.artist.ifEmpty { "未知歌手" })
            .putString(MediaMetadata.METADATA_KEY_ALBUM, "SelfKTV 听歌")
            .putLong(MediaMetadata.METADATA_KEY_DURATION, lastDurationMs.coerceAtLeast(0).toLong())
        val bmp = art
        if (bmp != null && artSongId == song.id) {
            mb.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bmp)
            mb.putBitmap(MediaMetadata.METADATA_KEY_ART, bmp)
        }
        return mb.build()
    }

    private fun updateState(state: Int, positionMs: Int, durationMs: Int) {
        val s = session ?: return
        val actions =
            PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_STOP or PlaybackState.ACTION_SEEK_TO or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        val playing = state == PlaybackState.STATE_PLAYING
        val st = PlaybackState.Builder()
            .setActions(actions)
            .setState(state, positionMs.coerceAtLeast(0).toLong(), if (playing) 1.0f else 0f)
            .build()
        try {
            s.setPlaybackState(st)
        } catch (_: Exception) {
        }
        mainHandler.removeCallbacks(ticker)
        if (playing) mainHandler.postDelayed(ticker, 2000L)
    }

    private fun loadArt(song: Song) {
        val ctx = app ?: return
        val cover = song.coverUrl
        if (cover.isNullOrEmpty()) {
            if (artSongId == song.id) return
            art = null
            artSongId = -1
            currentSong?.let { session?.setMetadata(buildMetadata(it)) }
            return
        }
        val cache = File(ctx.cacheDir, "listen_cover_" + song.id + ".img")
        thread(name = "listen-cover") {
            try {
                if (!cache.exists() || cache.length() == 0L) {
                    ApiClient.downloadToFile(cover, cache)
                }
                val bmp = decodeCover(cache)
                mainHandler.post {
                    if (currentSong?.id != song.id) return@post
                    art = bmp
                    artSongId = if (bmp != null) song.id else -1
                    currentSong?.let { session?.setMetadata(buildMetadata(it)) }
                    app?.let { MediaPlaybackService.refresh(it) }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun decodeCover(file: File): Bitmap? {
        if (!file.exists() || file.length() == 0L) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / sample > ART_MAX_DIM || bounds.outHeight / sample > ART_MAX_DIM) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }

    private fun requestFocus() {
        if (focusGranted) return
        val am = audioManager ?: return
        try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener { change ->
                    if (change == AudioManager.AUDIOFOCUS_LOSS ||
                        change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                    ) {
                        mainHandler.post { if (ListenPlayer.isPlaying) ListenPlayer.pause() }
                    }
                }
                .build()
            focusReq = req
            focusGranted =
                am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } catch (_: Exception) {
        }
    }

    private fun abandonFocus() {
        val am = audioManager ?: return
        try {
            focusReq?.let { am.abandonAudioFocusRequest(it) }
        } catch (_: Exception) {
        }
        focusGranted = false
    }
}
