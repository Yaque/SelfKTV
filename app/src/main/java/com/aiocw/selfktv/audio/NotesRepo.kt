package com.aiocw.selfktv.audio

import android.content.Context
import com.aiocw.selfktv.net.ApiClient
import com.aiocw.selfktv.net.RefNote
import org.json.JSONObject
import java.io.File

/**
 * 参考旋律缓存仓库（三层策略）：
 *  1. 内存：当前使用的 notes 常驻，渲染/评分零 IO
 *  2. 磁盘：filesDir/reference_notes/{songId}_v{version}.json
 *  3. 版本校验：加载时拉服务端版本，不同则覆盖缓存；离线回落到本地缓存
 */
object NotesRepo {

    data class Reference(val version: Int, val notes: List<RefNote>, val engine: String = "")

    private val memory = HashMap<Int, Reference>()

    /** 加载参考旋律（需在后台线程调用：含网络）。 */
    fun load(context: Context, songId: Int): Reference? {
        memory[songId]?.let { return it }

        var cached = readDisk(context, songId)
        val remote = fetchRemote(songId)
        val ref = if (remote != null) {
            if (cached == null || cached.version != remote.version) {
                writeDisk(context, songId, remote)
            }
            remote
        } else {
            cached
        }
        ref?.let { memory[songId] = it }
        return ref
    }

    /** 丢弃内存缓存（重新转录后强制拉新）。 */
    fun invalidate(songId: Int) {
        memory.remove(songId)
    }

    private fun fetchRemote(songId: Int): Reference? = try {
        val payload = ApiClient.getNotes(songId) ?: return null
        val (version, notes) = RefNote.parse(payload)
        Reference(version, notes, payload.optString("engine", ""))
    } catch (_: Exception) {
        null
    }

    private fun dir(context: Context): File =
        File(context.filesDir, "reference_notes").apply { mkdirs() }

    private fun readDisk(context: Context, songId: Int): Reference? {
        val pattern = Regex("^${songId}_v(\\d+)\\.json$")
        val best = dir(context).listFiles()
            ?.mapNotNull { f ->
                val m = pattern.matchEntire(f.name) ?: return@mapNotNull null
                m.groupValues[1].toInt() to f
            }
            ?.maxByOrNull { it.first } ?: return null
        return try {
            val payload = JSONObject(best.second.readText())
            val (version, notes) = RefNote.parse(payload)
            Reference(version, notes, payload.optString("engine", ""))
        } catch (_: Exception) {
            null
        }
    }

    private fun writeDisk(context: Context, songId: Int, ref: Reference) {
        try {
            val dir = dir(context)
            // 清理旧版本
            dir.listFiles()?.forEach { f ->
                if (f.name.startsWith("${songId}_v")) f.delete()
            }
            val payload = JSONObject()
                .put("songId", songId)
                .put("version", ref.version)
                .put("engine", ref.engine)
                .put("notes", JSONArray_wrap(ref.notes))
            File(dir, "${songId}_v${ref.version}.json").writeText(payload.toString())
        } catch (_: Exception) {
        }
    }

    private fun JSONArray_wrap(notes: List<RefNote>): org.json.JSONArray {
        val arr = org.json.JSONArray()
        for (n in notes) {
            arr.put(
                JSONObject()
                    .put("t", n.t.toDouble())
                    .put("d", n.d.toDouble())
                    .put("m", n.m)
                    .put("c", n.c.toDouble())
            )
        }
        return arr
    }
}
