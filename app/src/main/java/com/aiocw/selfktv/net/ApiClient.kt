package com.aiocw.selfktv.net

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.aiocw.selfktv.data.Prefs
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 服务端返回的业务错误。 */
class ApiException(val code: Int, message: String) : Exception(message)

/**
 * SelfKTV 服务端 HTTP 客户端（OkHttp）。
 * 所有请求自动携带 Bearer 令牌；网络操作请放到后台线程执行。
 */
object ApiClient {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    /** 服务器地址（不含末尾斜杠）。 */
    val baseUrl: String
        get() = Prefs.server.trim().trimEnd('/')

    /** 把服务端返回的相对路径转为完整 URL。 */
    fun absolute(path: String?): String {
        if (path.isNullOrEmpty()) return ""
        return if (path.startsWith("http")) path else baseUrl + path
    }

    /** MediaPlayer 用的鉴权头。 */
    fun mediaHeaders(): Map<String, String> =
        if (Prefs.token.isEmpty()) emptyMap()
        else mapOf("Authorization" to "Bearer " + Prefs.token)

    private fun build(path: String, withToken: Boolean = true): Request.Builder {
        // 兼容相对路径与绝对 URL（避免重复拼接 baseUrl）
        val full = if (path.startsWith("http")) path else baseUrl + path
        val b = Request.Builder().url(full)
        if (withToken && Prefs.token.isNotEmpty()) {
            b.header("Authorization", "Bearer " + Prefs.token)
        }
        return b
    }

    private fun parseError(code: Int, body: String): ApiException {
        val detail = try {
            JSONObject(body).optString("detail").ifEmpty { body }
        } catch (_: Exception) {
            body
        }
        return ApiException(code, detail.ifEmpty { "请求失败 ($code)" })
    }

    private fun execute(request: Request): String {
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw parseError(resp.code, body)
            return body
        }
    }

    fun getJson(path: String): JSONObject = JSONObject(execute(build(path).get().build()))

    fun getJsonArray(path: String): JSONArray = JSONArray(execute(build(path).get().build()))

    fun getText(path: String): String = execute(build(path).get().build())

    fun postJson(path: String, payload: JSONObject): JSONObject {
        val body = payload.toString().toRequestBody("application/json".toMediaType())
        return JSONObject(execute(build(path).post(body).build()))
    }

    fun putJson(path: String, payload: JSONObject): JSONObject {
        val body = payload.toString().toRequestBody("application/json".toMediaType())
        return JSONObject(execute(build(path).put(body).build()))
    }

    fun delete(path: String): JSONObject =
        JSONObject(execute(build(path).delete().build()).ifEmpty { "{}" })

    /**
     * 流式下载到目标文件（临时文件 + rename 原子写），失败返回 false 并清理残留。
     * 用于大文件缓存，不占内存。
     */
    fun downloadToFile(path: String, dest: File): Boolean {
        val tmp = File(dest.parentFile, dest.name + ".part")
        return try {
            client.newCall(build(path).get().build()).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val body = resp.body ?: return false
                tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
            }
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            true
        } catch (_: Exception) {
            tmp.delete()
            false
        }
    }

    /** 登录并保存令牌。 */
    fun login(server: String, username: String, password: String): JSONObject {
        Prefs.server = server
        Prefs.token = ""
        val payload = JSONObject().put("username", username).put("password", password)
        val data = postJson("/api/auth/login", payload)
        Prefs.token = data.optString("token")
        Prefs.username = data.optString("username", username)
        return data
    }

    /** 拉取位图（封面），失败返回 null。 */
    fun getBitmap(path: String?): Bitmap? {
        if (path.isNullOrEmpty()) return null
        return try {
            client.newCall(build(path).get().build()).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val bytes = resp.body?.bytes() ?: return null
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 下载文本（歌词 LRC）。 */
    fun getLyric(path: String?): String? = try {
        if (path.isNullOrEmpty()) null else getText(path)
    } catch (_: Exception) {
        null
    }

    /** 参考音高轮廓（打分/音准器），失败返回 null。 */
    fun getPitch(songId: Int): JSONObject? = try {
        getJson("/api/songs/$songId/pitch")
    } catch (_: Exception) {
        null
    }

    /** 参考旋律（音符事件序列），失败返回 null。 */
    fun getNotes(songId: Int): JSONObject? = try {
        getJson("/api/songs/$songId/notes")
    } catch (_: Exception) {
        null
    }

    /** 下载二进制内容（混合导出等），失败返回 null。 */
    fun downloadBytes(path: String): ByteArray? = try {
        client.newCall(build(path).get().build()).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.bytes()
        }
    } catch (_: Exception) {
        null
    }

    // ------------------------------------------------ 业务接口

    fun songs(query: String = ""): List<Song> {
        val path = if (query.isBlank()) "/api/songs" else "/api/songs?q=" + java.net.URLEncoder.encode(query, "UTF-8")
        val data = getJson(path)
        val arr = data.optJSONArray("songs") ?: JSONArray()
        return (0 until arr.length()).map { Song.fromJson(arr.getJSONObject(it)) }
    }

    fun song(id: Int): Song = Song.fromJson(getJson("/api/songs/$id"))

    // ------------------------------------------------ 听歌（队列 / 收藏 / 歌单）

    private fun songsOf(data: JSONObject, key: String = "songs"): List<Song> {
        val arr = data.optJSONArray(key) ?: JSONArray()
        return (0 until arr.length()).map { Song.fromJson(arr.getJSONObject(it)) }
    }

    /** 播放队列。 */
    fun listenQueue(): List<Song> = songsOf(getJson("/api/listen/queue"))

    /** 整份替换播放队列（返回服务器上的完整歌曲列表）。 */
    fun listenQueueReplace(songIds: List<Int>): List<Song> {
        val arr = JSONArray()
        songIds.forEach { arr.put(it) }
        return songsOf(putJson("/api/listen/queue", JSONObject().put("songIds", arr)))
    }

    /** 幂等追加队尾。 */
    fun listenQueueAdd(songId: Int): List<Song> =
        songsOf(postJson("/api/listen/queue", JSONObject().put("songId", songId)))

    fun listenQueueRemove(songId: Int): List<Song> = songsOf(delete("/api/listen/queue/$songId"))

    fun listenQueueClear(): List<Song> = songsOf(delete("/api/listen/queue"))

    /** 收藏。 */
    fun listenFavorites(): List<Song> = songsOf(getJson("/api/listen/favorites"))

    fun listenFavoriteAdd(songId: Int) {
        postJson("/api/listen/favorites/$songId", JSONObject())
    }

    fun listenFavoriteRemove(songId: Int) {
        delete("/api/listen/favorites/$songId")
    }

    /** 歌单。 */
    fun listenPlaylists(): List<ListenPlaylist> {
        val data = getJson("/api/listen/playlists")
        val arr = data.optJSONArray("playlists") ?: JSONArray()
        return (0 until arr.length()).map { ListenPlaylist.fromJson(arr.getJSONObject(it)) }
    }

    fun listenPlaylistCreate(name: String): ListenPlaylist =
        ListenPlaylist.fromJson(postJson("/api/listen/playlists", JSONObject().put("name", name)).optJSONObject("playlist") ?: JSONObject())

    fun listenPlaylistRename(id: Int, name: String): ListenPlaylist =
        ListenPlaylist.fromJson(putJson("/api/listen/playlists/$id", JSONObject().put("name", name)).optJSONObject("playlist") ?: JSONObject())

    fun listenPlaylistDelete(id: Int) {
        delete("/api/listen/playlists/$id")
    }

    /** 歌单详情（含歌曲列表）。 */
    fun listenPlaylistSongs(id: Int): Pair<ListenPlaylist, List<Song>> {
        val data = getJson("/api/listen/playlists/$id")
        val pl = ListenPlaylist.fromJson(data.optJSONObject("playlist") ?: JSONObject())
        return pl to songsOf(data)
    }

    /** 幂等加入歌单，返回最新曲目数。 */
    fun listenPlaylistAddSong(id: Int, songId: Int): Int =
        postJson("/api/listen/playlists/$id/songs", JSONObject().put("songId", songId)).optInt("count", 0)

    fun listenPlaylistRemoveSong(id: Int, songId: Int) {
        delete("/api/listen/playlists/$id/songs/$songId")
    }

    /** 整份替换歌单歌曲（排序）。 */
    fun listenPlaylistSetSongs(id: Int, songIds: List<Int>): Pair<ListenPlaylist, List<Song>> {
        val arr = JSONArray()
        songIds.forEach { arr.put(it) }
        val data = putJson("/api/listen/playlists/$id/songs", JSONObject().put("songIds", arr))
        val pl = ListenPlaylist.fromJson(data.optJSONObject("playlist") ?: JSONObject())
        return pl to songsOf(data)
    }

    fun works(): List<Work> {
        val data = getJson("/api/works")
        val arr = data.optJSONArray("works") ?: JSONArray()
        return (0 until arr.length()).map { Work.fromJson(arr.getJSONObject(it)) }
    }

    fun deleteWork(id: Int) {
        delete("/api/works/$id")
    }

    fun changePassword(oldPassword: String, newPassword: String): JSONObject =
        postJson(
            "/api/admin/password",
            JSONObject().put("old_password", oldPassword).put("new_password", newPassword)
        )

    /** 上传录音作品。 */
    fun uploadWork(songId: Int?, mode: String, title: String, file: File): JSONObject {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("mode", mode)
            .addFormDataPart("title", title)
            .let { b -> if (songId != null) b.addFormDataPart("song_id", songId.toString()) else b }
            .addFormDataPart(
                "file", file.name,
                file.asRequestBody("audio/wav".toMediaType())
            )
            .build()
        return JSONObject(execute(build("/api/works").post(body).build()))
    }
}
