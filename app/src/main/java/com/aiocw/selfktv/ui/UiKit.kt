package com.aiocw.selfktv.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.aiocw.selfktv.net.ApiClient
import java.util.concurrent.Executors

/** UI 小工具：主线程调度、时间格式化、圆形封面加载。 */
object UiKit {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioPool = Executors.newFixedThreadPool(3)
    private val imageCache = HashMap<String, Bitmap>()

    /**
     * 系统栏适配（Android 15+ 强制 edge-to-edge）：
     * 给页面内容根视图加系统栏 padding，避免内容被状态栏/手势区遮挡。
     * 在 Activity.onCreate 的 setContentView 之后调用一次即可。
     */
    fun applySystemBars(activity: Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val root = content.getChildAt(0) ?: return
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    /** 切回主线程。 */
    fun ui(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    /** 后台线程执行。 */
    fun bg(block: () -> Unit) {
        ioPool.execute {
            try {
                block()
            } catch (_: Exception) {
            }
        }
    }

    /** 毫秒 → mm:ss */
    fun fmtTime(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val m = totalSec / 60
        val s = totalSec % 60
        return "%02d:%02d".format(m, s)
    }

    fun fmtDuration(sec: Double): String = fmtTime((sec * 1000).toLong())

    /** 异步加载网络封面并裁成圆形。 */
    fun loadCover(url: String?, imageView: ImageView, placeholder: Int = 0) {
        if (url.isNullOrEmpty()) {
            if (placeholder != 0) imageView.setImageResource(placeholder)
            return
        }
        val abs = ApiClient.absolute(url)
        imageCache[abs]?.let {
            imageView.setImageBitmap(circleCrop(it))
            return
        }
        if (placeholder != 0) imageView.setImageResource(placeholder)
        bg {
            val bmp = ApiClient.getBitmap(abs) ?: return@bg
            imageCache[abs] = bmp
            val circle = circleCrop(bmp)
            ui { imageView.setImageBitmap(circle) }
        }
    }

    /** 裁剪为圆形头像/封面。 */
    fun circleCrop(src: Bitmap): Bitmap {
        val size = minOf(src.width, src.height)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        return output
    }

    /** 解码采样后的位图（大图防 OOM）。 */
    fun decodeSampled(bytes: ByteArray, reqSize: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / sample > reqSize * 2) sample *= 2
        val opts2 = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts2)
    }
}
