package com.aiocw.selfktv.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 本地偏好设置：服务器地址、登录令牌、演唱参数。
 */
object Prefs {
    private const val FILE = "selfktv_prefs"

    private const val KEY_SERVER = "server_url"
    private const val KEY_TOKEN = "token"
    private const val KEY_USERNAME = "username"
    private const val KEY_RECORD = "record_enabled"
    private const val KEY_VOCAL_VOLUME = "vocal_volume"
    private const val KEY_SMART_SENS = "smart_sensitivity"
    private const val KEY_LISTEN_PLAY_MODE = "listen_play_mode"
    private const val KEY_THEME = "theme_mode"

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    var server: String
        get() = sp.getString(KEY_SERVER, "") ?: ""
        set(value) {
            sp.edit().putString(KEY_SERVER, value.trim().trimEnd('/')).apply()
        }

    var token: String
        get() = sp.getString(KEY_TOKEN, "") ?: ""
        set(value) {
            sp.edit().putString(KEY_TOKEN, value).apply()
        }

    var username: String
        get() = sp.getString(KEY_USERNAME, "") ?: ""
        set(value) {
            sp.edit().putString(KEY_USERNAME, value).apply()
        }

    val isLoggedIn: Boolean
        get() = server.isNotEmpty() && token.isNotEmpty()

    /** 外观主题：auto 跟随系统 / light 白天 / dark 黑夜 */
    var themeMode: String
        get() = sp.getString(KEY_THEME, "auto") ?: "auto"
        set(value) {
            sp.edit().putString(KEY_THEME, value).apply()
        }

    /** K 歌时是否录音保存作品 */
    var recordEnabled: Boolean
        get() = sp.getBoolean(KEY_RECORD, true)
        set(value) {
            sp.edit().putBoolean(KEY_RECORD, value).apply()
        }

    /** 原唱/人声音量 0..100 */
    var vocalVolume: Int
        get() = sp.getInt(KEY_VOCAL_VOLUME, 80)
        set(value) {
            sp.edit().putInt(KEY_VOCAL_VOLUME, value.coerceIn(0, 100)).apply()
        }

    /** 智能模式消音灵敏度 0..100（越高越容易触发消原唱） */
    var smartSensitivity: Int
        get() = sp.getInt(KEY_SMART_SENS, 55)
        set(value) {
            sp.edit().putInt(KEY_SMART_SENS, value.coerceIn(0, 100)).apply()
        }

    /** 听歌播放模式（list_loop / shuffle / single_loop），全局生效。 */
    var listenPlayMode: String
        get() = sp.getString(KEY_LISTEN_PLAY_MODE, "list_loop") ?: "list_loop"
        set(value) {
            sp.edit().putString(KEY_LISTEN_PLAY_MODE, value).apply()
        }

    fun clearLogin() {
        sp.edit().remove(KEY_TOKEN).remove(KEY_USERNAME).apply()
    }
}
