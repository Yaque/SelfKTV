package com.aiocw.selfktv.audio

import android.content.Context
import com.aiocw.selfktv.net.ApiClient
import com.aiocw.selfktv.ui.UiKit
import java.io.File

/**
 * 听歌音轨缓存：播放过的歌自动缓存播放所用的那条音轨文件。
 *
 * - 目录：filesDir/listen_cache/，文件名按音轨区分：
 *   原曲 {songId}.audio（兼容旧缓存文件名） / 人声 {songId}_vocal.audio / 伴奏 {songId}_accomp.audio
 * - 播放时有缓存直接播本地文件；无缓存则在线播放同时后台下载
 * - LRU 上限 500MB（按文件 lastModified 淘汰最旧）
 * - 下载后台线程、失败静默、重复下载跳过、临时文件 + rename 原子写
 */
object ListenCache {

    private const val DIR_NAME = "listen_cache"
    private const val MAX_BYTES = 500L * 1024L * 1024L // 500MB

    private var dir: File? = null
    private val downloading = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** 缓存状态变化（下载开始/完成/清理）→ 主线程回调，UI 刷新缓存角标用。 */
    @Volatile
    var onChanged: (() -> Unit)? = null

    fun init(context: Context) {
        dir = File(context.applicationContext.filesDir, DIR_NAME).apply { mkdirs() }
    }

    /** 缓存文件名：原曲沿用旧的 {songId}.audio（旧缓存视为原曲轨缓存）。 */
    private fun keyOf(id: Int, trackKey: String): String =
        if (trackKey == ListenPlayer.TrackKind.ORIGINAL.key) "$id.audio" else "${id}_${trackKey}.audio"

    /** 缓存文件路径（不保证存在）。 */
    fun fileOf(id: Int, trackKey: String = ListenPlayer.TrackKind.ORIGINAL.key): File =
        File(dir ?: throw IllegalStateException("ListenCache 未初始化"), keyOf(id, trackKey))

    /** 指定音轨是否已缓存。 */
    fun has(id: Int, trackKey: String = ListenPlayer.TrackKind.ORIGINAL.key): Boolean =
        fileOf(id, trackKey).let { it.exists() && it.length() > 0L }

    /** 指定音轨是否正在下载。 */
    fun isDownloading(id: Int, trackKey: String = ListenPlayer.TrackKind.ORIGINAL.key): Boolean =
        keyOf(id, trackKey) in downloading

    /** 缓存占用总字节数。 */
    fun totalSizeBytes(): Long =
        dir?.listFiles()?.filter { !it.name.endsWith(".part") }?.sumOf { it.length() } ?: 0L

    /** 播放命中缓存时刷新 LRU 时间戳。 */
    fun touch(id: Int, trackKey: String = ListenPlayer.TrackKind.ORIGINAL.key) {
        runCatching { fileOf(id, trackKey).setLastModified(System.currentTimeMillis()) }
    }

    /**
     * 确保缓存文件可被 Android MediaPlayer 播放。
     * demucs 等分离工具可能输出 32 位浮点 / 24 位 / 32 位整型 WAV，
     * MediaPlayer 只支持 8/16/24 位整型 PCM WAV，这里就地转成 16 位 PCM。
     * 非 WAV 或已兼容 → true；无法转换/损坏 → false。
     */
    fun ensurePlayable(file: File): Boolean {
        if (!file.exists() || file.length() < 44L) return false
        return try {
            if (!file.name.endsWith(".audio") && !file.extension.equals("wav", ignoreCase = true)) return true
            normalizeWav(file)
        } catch (_: Exception) {
            true // 识别不了就按原样交给播放器（失败由上层自愈重试）
        }
    }

    /** WAV 格式检查 + 不兼容精度就地转 16 位 PCM。返回文件是否可播。 */
    private fun normalizeWav(file: File): Boolean {
        val bytes = file.readBytes()
        if (bytes.size < 44) return false
        if (String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") return true // 不是 WAV，不拦
        var pos = 12
        var fmt: ByteArray? = null
        var dataStart = -1
        var dataLen = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val size = readInt(bytes, pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> fmt = bytes.copyOfRange(body, minOf(body + size, bytes.size))
                "data" -> {
                    dataStart = body
                    dataLen = minOf(size, bytes.size - body)
                }
            }
            pos = body + size + (size and 1) // chunk 按偶数字节对齐
        }
        val f = fmt ?: return false
        if (f.size < 16 || dataStart < 0 || dataLen <= 0) return false
        val audioFormat = readShort(f, 0)
        val channels = readShort(f, 2)
        val sampleRate = readInt(f, 4)
        val bits = readShort(f, 14)
        // 已兼容：PCM 整型 8/16/24 位
        if (audioFormat == 1 && (bits == 8 || bits == 16 || bits == 24)) return true
        // 可转换：IEEE 浮点 32/64 位、PCM 整型 32 位
        val convertible =
            (audioFormat == 3 && (bits == 32 || bits == 64)) || (audioFormat == 1 && bits == 32)
        if (!convertible || channels <= 0) return false

        val src = java.nio.ByteBuffer.wrap(bytes, dataStart, dataLen).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val samples = dataLen / (bits / 8)
        val out = java.nio.ByteBuffer.allocate(samples * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        when {
            audioFormat == 3 && bits == 32 -> {
                val fb = src.asFloatBuffer()
                repeat(samples) { i ->
                    val v = (fb.get(i).toDouble() * 32767.0).toInt().coerceIn(-32768, 32767)
                    out.putShort(v.toShort())
                }
            }
            audioFormat == 3 && bits == 64 -> {
                val db = src.asDoubleBuffer()
                repeat(samples) { i ->
                    val v = (db.get(i) * 32767.0).toInt().coerceIn(-32768, 32767)
                    out.putShort(v.toShort())
                }
            }
            else -> { // PCM 整型 32 位 → 取高 16 位
                val ib = src.asIntBuffer()
                repeat(samples) { i -> out.putShort((ib.get(i) shr 16).toShort()) }
            }
        }
        val dataBytes = out.array()
        val total = 36 + dataBytes.size
        val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()); header.putInt(total)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()); header.putInt(16)
        header.putShort(1) // PCM
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(sampleRate * channels * 2)
        header.putShort((channels * 2).toShort())
        header.putShort(16)
        header.put("data".toByteArray()); header.putInt(dataBytes.size)
        val tmp = File(file.parentFile, file.name + ".part")
        tmp.outputStream().use { it.write(header.array()); it.write(dataBytes) }
        file.delete()
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
        return true
    }

    private fun readShort(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8)

    private fun readInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8) or
            ((b[off + 2].toInt() and 0xff) shl 16) or ((b[off + 3].toInt() and 0xff) shl 24)

    /** 清空全部缓存。 */
    fun clearAll() {
        dir?.listFiles()?.forEach { runCatching { it.delete() } }
        notifyChanged()
    }

    /**
     * 后台下载音轨到缓存（播放在线音频的同时调用）。
     * 已缓存 / 正在下载 / 无 URL 时直接跳过；失败静默不影响播放。
     */
    fun putAsync(songId: Int, trackKey: String = ListenPlayer.TrackKind.ORIGINAL.key, url: String?) {
        if (dir == null || url.isNullOrEmpty()) return
        val key = keyOf(songId, trackKey)
        if (has(songId, trackKey)) return
        if (!downloading.add(key)) return // 重复下载跳过
        notifyChanged()
        UiKit.bg {
            try {
                val target = fileOf(songId, trackKey)
                if (target.exists() && target.length() > 0L) return@bg
                if (ApiClient.downloadToFile(url, target)) {
                    target.setLastModified(System.currentTimeMillis())
                    evictLru()
                } else {
                    target.delete() // 失败静默，清理残留
                }
            } finally {
                downloading.remove(key)
                notifyChanged()
            }
        }
    }

    /** 超出上限时按 lastModified 淘汰最旧的缓存文件。 */
    private fun evictLru() {
        val files = dir?.listFiles()?.filter { !it.name.endsWith(".part") }?.sortedBy { it.lastModified() }
            ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= MAX_BYTES) break
            val len = f.length()
            if (f.delete()) total -= len
        }
    }

    private fun notifyChanged() {
        val cb = onChanged ?: return
        UiKit.ui { cb() }
    }
}
