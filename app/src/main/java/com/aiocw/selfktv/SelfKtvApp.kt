package com.aiocw.selfktv

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.aiocw.selfktv.audio.ListenCache
import com.aiocw.selfktv.audio.ListenPlayer
import com.aiocw.selfktv.data.Prefs

/** 全局初始化。 */
class SelfKtvApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        applyTheme()
        ListenCache.init(this)
        ListenPlayer.init(this)
    }

    /** 按偏好应用主题：自动（跟随系统）/ 白天 / 黑夜。 */
    private fun applyTheme() {
        AppCompatDelegate.setDefaultNightMode(
            when (Prefs.themeMode) {
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }
}
