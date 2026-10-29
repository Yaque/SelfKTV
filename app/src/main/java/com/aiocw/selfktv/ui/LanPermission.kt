package com.aiocw.selfktv.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 本地网络权限（Android 16+/澎湃OS4）。
 *
 * targetSdk 37+ 的应用访问局域网（家庭服务器）默认被系统屏蔽，
 * 必须持有运行时权限：
 *   - Android 17+：android.permission.ACCESS_LOCAL_NETWORK（dangerous）
 *   - Android 16（部分 ROM 提前引入）：同上或退化为 NEARBY_WIFI_DEVICES
 * 本助手按设备实际提供的权限自动选择申请项，并带用途说明引导授权。
 */
object LanPermission {

    private const val PERM_ACCESS_LOCAL = "android.permission.ACCESS_LOCAL_NETWORK"
    private const val PERM_NEARBY = "android.permission.NEARBY_WIFI_DEVICES"

    const val REQUEST_CODE = 4101

    /** 当前设备需要的本地网络权限（优先 ACCESS_LOCAL_NETWORK，澎湃OS4 已提前引入）。 */
    fun requiredOnDevice(ctx: Context): String? {
        // 无论 SDK 版本，设备定义了 ACCESS_LOCAL_NETWORK 就以它为准（小米澎湃OS 等已提前引入）；
        // 否则 Android 16 原生机型退化到 NEARBY_WIFI_DEVICES（同一权限组，授权互通）。
        return when {
            isDefinedOnDevice(ctx, PERM_ACCESS_LOCAL) -> PERM_ACCESS_LOCAL
            isDefinedOnDevice(ctx, PERM_NEARBY) && Build.VERSION.SDK_INT >= 36 -> PERM_NEARBY
            else -> null
        }
    }

    /** 已经满足（无需权限 / 权限已授予）。 */
    fun isGranted(ctx: Context): Boolean {
        val perm = requiredOnDevice(ctx) ?: return true
        return ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 确保权限就绪：未授权先弹用途说明，再触发系统授权弹窗。
     * 已授权时直接回调 onReady。
     */
    fun ensure(activity: Activity, onReady: () -> Unit) {
        if (isGranted(activity)) {
            onReady()
            return
        }
        val perm = requiredOnDevice(activity) ?: run {
            onReady()
            return
        }
        val message =
            if (perm == PERM_ACCESS_LOCAL) {
                "SelfKTV 运行在家庭局域网内，需要访问你自己的服务器" +
                    "（例如 http://192.168.x.x:8000）。\n\n" +
                    "系统接下来会弹出「允许使用本地网络」授权请求，请点击「允许」。\n" +
                    "如果之前拒绝过，可到 设置 → 应用 → SelfKTV → 权限 → 本地网络 中重新开启。"
            } else {
                "SelfKTV 运行在家庭局域网内，需要访问你自己的服务器" +
                    "（例如 http://192.168.x.x:8000）。\n\n" +
                    "系统接下来会弹出「附近设备」授权请求，请点击「允许」。\n" +
                    "如果之前拒绝过，可到 设置 → 应用 → SelfKTV → 权限 → 附近设备 中重新开启。"
            }
        AlertDialog.Builder(activity)
            .setTitle("需要「本地网络」权限")
            .setMessage(message)
            .setPositiveButton("继续") { _, _ ->
                ActivityCompat.requestPermissions(activity, arrayOf(perm), REQUEST_CODE)
            }
            .setNegativeButton("取消") { _, _ -> }
            .show()
    }

    /** 授权结果处理：授予→继续；拒绝→引导去设置。 */
    fun onRequestResult(
        activity: Activity,
        requestCode: Int,
        grantResults: IntArray,
        onReady: () -> Unit,
    ) {
        if (requestCode != REQUEST_CODE) return
        if (isGranted(activity)) {
            onReady()
            return
        }
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            onReady()
            return
        }
        AlertDialog.Builder(activity)
            .setTitle("未授予本地网络权限")
            .setMessage(
                "没有该权限将无法连接局域网内的 SelfKTV 服务器。\n\n" +
                    "请到 设置 → 应用 → SelfKTV → 权限 → 本地网络（或 附近设备）中开启，" +
                    "然后返回重试。"
            )
            .setPositiveButton("去设置") { _, _ -> openAppSettings(activity) }
            .setNegativeButton("稍后", null)
            .show()
    }

    fun openAppSettings(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
            ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /**
     * 针对连接异常给出本地网络权限视角的排障提示。
     * 权限缺失时明确指向授权入口，避免只显示 "timeout" 让人无从下手。
     */
    fun connectErrorHint(ctx: Context): String? {
        if (isGranted(ctx)) return null
        return "⚠ 未授予「本地网络」权限，无法访问局域网服务器（设置 → 应用 → SelfKTV → 权限 → 本地网络）"
    }

    private fun isDefinedOnDevice(ctx: Context, perm: String): Boolean = try {
        ctx.packageManager.getPermissionInfo(perm, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
