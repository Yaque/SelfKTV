package com.aiocw.selfktv.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import com.aiocw.selfktv.R
import com.aiocw.selfktv.data.Prefs
import com.aiocw.selfktv.net.ApiClient

/** 我的：账号信息、演唱参数设置、修改密码、退出登录。 */
class ProfileFragment : Fragment(R.layout.fragment_profile) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val tvUsername = view.findViewById<TextView>(R.id.tvUsername)
        val tvServer = view.findViewById<TextView>(R.id.tvServer)
        val swRecord = view.findViewById<Switch>(R.id.swRecord)
        val sbVocal = view.findViewById<SeekBar>(R.id.sbVocalVolume)
        val sbSens = view.findViewById<SeekBar>(R.id.sbSmartSens)
        val btnPassword = view.findViewById<Button>(R.id.btnPassword)
        val btnLogout = view.findViewById<Button>(R.id.btnLogout)

        tvUsername.text = Prefs.username.ifEmpty { "未登录" }
        tvServer.text = Prefs.server

        swRecord.isChecked = Prefs.recordEnabled
        swRecord.setOnCheckedChangeListener { _, checked -> Prefs.recordEnabled = checked }

        sbVocal.progress = Prefs.vocalVolume
        sbVocal.setOnSeekBarChangeListener(simpleSeek { Prefs.vocalVolume = it })

        sbSens.progress = Prefs.smartSensitivity
        sbSens.setOnSeekBarChangeListener(simpleSeek { Prefs.smartSensitivity = it })

        // 外观主题：自动 / 白天 / 黑夜
        val tvTheme = view.findViewById<TextView>(R.id.tvTheme)
        fun themeLabel() = when (Prefs.themeMode) {
            "light" -> "白天"
            "dark" -> "黑夜"
            else -> "自动（跟随系统）"
        }
        tvTheme.text = themeLabel()
        view.findViewById<View>(R.id.rowTheme).setOnClickListener {
            val items = arrayOf("自动（跟随系统）", "白天", "黑夜")
            val keys = arrayOf("auto", "light", "dark")
            AlertDialog.Builder(requireContext())
                .setTitle("外观主题")
                .setSingleChoiceItems(items, keys.indexOf(Prefs.themeMode).coerceAtLeast(0)) { d, which ->
                    Prefs.themeMode = keys[which]
                    AppCompatDelegate.setDefaultNightMode(
                        when (keys[which]) {
                            "light" -> AppCompatDelegate.MODE_NIGHT_NO
                            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                        }
                    )
                    tvTheme.text = themeLabel()
                    d.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        btnPassword.setOnClickListener { showChangePassword() }
        btnLogout.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("退出登录")
                .setMessage("确定退出当前账号？")
                .setPositiveButton("退出") { _, _ ->
                    Prefs.clearLogin()
                    (activity as? MainActivity)?.goLogin()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun simpleSeek(onChange: (Int) -> Unit): SeekBar.OnSeekBarChangeListener =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) onChange(progress)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        }

    private fun showChangePassword() {
        val ctx = requireContext()
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        val etOld = EditText(ctx).apply { hint = "原密码"; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val etNew = EditText(ctx).apply { hint = "新密码（至少 6 位）"; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        layout.addView(etOld)
        layout.addView(etNew)

        AlertDialog.Builder(ctx)
            .setTitle("修改密码")
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                val old = etOld.text.toString()
                val new = etNew.text.toString()
                if (old.isEmpty() || new.length < 6) {
                    Toast.makeText(ctx, "请输入原密码，新密码至少 6 位", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                UiKit.bg {
                    try {
                        ApiClient.changePassword(old, new)
                        UiKit.ui { Toast.makeText(ctx, "密码已修改", Toast.LENGTH_SHORT).show() }
                    } catch (e: Exception) {
                        UiKit.ui {
                            Toast.makeText(ctx, "修改失败：${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
