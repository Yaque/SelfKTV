package com.aiocw.selfktv.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlin.math.sqrt

/**
 * 麦克风引擎：采集 PCM 写 WAV（44.1kHz / 单声道 / 16bit），
 * 同时提供：
 *  - 实时音量回调（智能消音 / 麦克风能量条）
 *  - 原始帧回调（音准检测）
 *  - 本地实时耳返（可开关，建议戴耳机使用）
 */
class WavRecorder(private val outFile: File) {

    companion object {
        const val SAMPLE_RATE = 44100
    }

    /** 实时输入音量（0f..1f） */
    var onLevel: ((Float) -> Unit)? = null

    /** 原始 PCM 帧（音准检测等） */
    var onFrame: ((ShortArray, Int) -> Unit)? = null

    /** 本地实时耳返开关 */
    @Volatile
    var earbackEnabled = false

    private var record: AudioRecord? = null
    private var earback: AudioTrack? = null
    private var thread: Thread? = null
    private val lock = Object()

    @Volatile
    private var running = false

    @Volatile
    private var paused = false

    val isRunning: Boolean get() = running

    /** 开始采集；返回 false 表示麦克风不可用/无权限。 */
    fun start(): Boolean {
        if (running) return true
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf * 2, 8192)
            )
        } catch (_: SecurityException) {
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }

        // 耳返通道（低延迟回放麦克风输入）
        val trackBuf = maxOf(minBuf * 2, 8192)
        earback = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(trackBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (_: Exception) {
            null
        }

        outFile.parentFile?.mkdirs()
        val out = BufferedOutputStream(FileOutputStream(outFile))
        out.write(ByteArray(44)) // 占位 WAV 头，结束时回填

        var totalBytes = 0
        rec.startRecording()
        earback?.play()
        record = rec
        running = true
        paused = false

        thread = Thread {
            val buf = ShortArray(2048)
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) {
                    if (!paused) {
                        val bytes = ByteArray(n * 2)
                        var j = 0
                        for (i in 0 until n) {
                            val v = buf[i].toInt()
                            bytes[j++] = (v and 0xff).toByte()
                            bytes[j++] = ((v shr 8) and 0xff).toByte()
                        }
                        synchronized(lock) {
                            out.write(bytes)
                            totalBytes += bytes.size
                        }
                    }
                    // 耳返：把麦克风帧直接送到耳机
                    if (earbackEnabled) {
                        try {
                            earback?.write(buf, 0, n)
                        } catch (_: Exception) {
                        }
                    }
                    // 音量 / 音准回调
                    var sum = 0L
                    for (i in 0 until n) {
                        val v = buf[i].toLong()
                        sum += v * v
                    }
                    val rms = sqrt(sum.toDouble() / n) / 32768.0
                    onLevel?.invoke(rms.toFloat().coerceIn(0f, 1f))
                    onFrame?.invoke(buf, n)
                }
            }
            try {
                rec.stop()
                rec.release()
            } catch (_: Exception) {
            }
            try {
                earback?.stop()
                earback?.release()
            } catch (_: Exception) {
            }
            earback = null
            synchronized(lock) {
                try {
                    out.flush()
                    out.close()
                } catch (_: Exception) {
                }
            }
            writeWavHeader(outFile, totalBytes)
        }.also { it.start() }
        return true
    }

    fun pause() {
        paused = true
    }

    fun resume() {
        paused = false
    }

    /** 停止采集，返回有效音频文件（太短/失败返回 null）。 */
    fun stop(): File? {
        if (!running) return outFile.takeIf { it.length() > 44 }
        running = false
        try {
            thread?.join(3000)
        } catch (_: InterruptedException) {
        }
        thread = null
        record = null
        return if (outFile.length() > 44 + SAMPLE_RATE / 5) outFile else null
    }

    /** 放弃本次录音并删除文件。 */
    fun discard() {
        stop()
        outFile.delete()
    }

    private fun writeWavHeader(file: File, dataBytes: Int) {
        try {
            val raf = RandomAccessFile(file, "rw")
            raf.seek(0)
            raf.writeBytes("RIFF")
            raf.write(intLE(36 + dataBytes))
            raf.writeBytes("WAVE")
            raf.writeBytes("fmt ")
            raf.write(intLE(16))
            raf.write(shortLE(1))                    // PCM
            raf.write(shortLE(1))                    // 单声道
            raf.write(intLE(SAMPLE_RATE))
            raf.write(intLE(SAMPLE_RATE * 2))        // 字节率
            raf.write(shortLE(2))                    // 块对齐
            raf.write(shortLE(16))                   // 位深
            raf.writeBytes("data")
            raf.write(intLE(dataBytes))
            raf.close()
        } catch (_: Exception) {
        }
    }

    private fun intLE(v: Int): ByteArray = byteArrayOf(
        (v and 0xff).toByte(), ((v shr 8) and 0xff).toByte(),
        ((v shr 16) and 0xff).toByte(), ((v shr 24) and 0xff).toByte()
    )

    private fun shortLE(v: Int): ByteArray =
        byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte())
}
