package com.aiocw.selfktv.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.aiocw.selfktv.ui.MainActivity

/**
 * 听歌前台服务：以 MediaStyle 通知为锚点，让系统媒体中心 / 澎湃OS 灵动岛识别播放状态。
 *
 * - 播放期间 startForeground（mediaPlayback 类型），通知绑定 MediaSession token
 * - 通知按钮：上一首 / 播放暂停 / 下一首；点击通知回到 App
 * - 无歌曲时停止服务并移除通知
 */
class MediaPlaybackService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PREV -> {
                ListenPlayer.previous()
                return START_STICKY
            }
            ACTION_TOGGLE -> {
                if (ListenPlayer.isPlaying) ListenPlayer.pause() else ListenPlayer.resume()
                return START_STICKY
            }
            ACTION_NEXT -> {
                ListenPlayer.next()
                return START_STICKY
            }
        }
        // ACTION_REFRESH：用 ListenMediaSession 当前状态重建通知
        if (!ListenMediaSession.hasSong) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForeground(
                NOTIF_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } catch (_: Exception) {
            // 后台启动受限等场景：忽略，下一次播放状态变化会重试
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(
            CHANNEL_ID,
            "听歌播放",
            NotificationManager.IMPORTANCE_LOW
        )
        ch.setShowBadge(false)
        ch.enableVibration(false)
        ch.setSound(null, null)
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val title = ListenMediaSession.notifTitle.ifEmpty { "SelfKTV" }
        val artist = ListenMediaSession.notifArtist.ifEmpty { "未知歌手" }
        val playing = ListenPlayer.isPlaying

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val prevPi = actionPending(ACTION_PREV, 1)
        val togglePi = actionPending(ACTION_TOGGLE, 2)
        val nextPi = actionPending(ACTION_NEXT, 3)

        val b = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(artist)
            .setContentIntent(open)
            .setShowWhen(false)
            .setOngoing(playing)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_previous, "上一首", prevPi).build())
            .addAction(
                Notification.Action.Builder(
                    if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    if (playing) "暂停" else "播放",
                    togglePi
                ).build()
            )
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_next, "下一首", nextPi).build())

        ListenMediaSession.notifArt?.let { b.setLargeIcon(it) }

        val style = Notification.MediaStyle()
            .setShowActionsInCompactView(0, 1, 2)
        ListenMediaSession.sessionToken?.let { style.setMediaSession(it) }
        b.setStyle(style)
        return b.build()
    }

    private fun actionPending(action: String, reqCode: Int): PendingIntent {
        val i = Intent(this, MediaPlaybackService::class.java).setAction(action)
        return PendingIntent.getService(
            this, reqCode, i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    companion object {
        private const val CHANNEL_ID = "selfktv_listen_media"
        private const val NOTIF_ID = 0x534b

        private const val ACTION_REFRESH = "com.aiocw.selfktv.REFRESH"
        private const val ACTION_PREV = "com.aiocw.selfktv.PREV"
        private const val ACTION_TOGGLE = "com.aiocw.selfktv.TOGGLE"
        private const val ACTION_NEXT = "com.aiocw.selfktv.NEXT"

        /** 用当前播放状态创建 / 刷新通知（幂等，可频繁调用）。 */
        fun refresh(context: Context) {
            val i = Intent(context, MediaPlaybackService::class.java).setAction(ACTION_REFRESH)
            try {
                context.startForegroundService(i)
            } catch (_: Exception) {
            }
        }

        /** 无歌曲 / 释放播放器时移除通知并停止服务。 */
        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, MediaPlaybackService::class.java))
            } catch (_: Exception) {
            }
        }
    }
}
