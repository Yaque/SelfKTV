package com.aiocw.selfktv.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.aiocw.selfktv.R
import com.aiocw.selfktv.data.Prefs
import com.aiocw.selfktv.net.ApiClient

/**
 * 登录页：填写服务器地址 + 账号密码。
 *
 * 连接前先确保「本地网络」权限就绪（Android 16+/澎湃OS4 默认屏蔽局域网访问），
 * 授权被拒时给出定向排障入口，而不是干巴巴的 timeout。
 */
class LoginActivity : AppCompatActivity() {

    private var pendingAction: (() -> Unit)? = null

    private lateinit var etServer: EditText
    private lateinit var etUser: EditText
    private lateinit var etPassword: EditText
    private lateinit var btnLogin: Button
    private lateinit var tvHint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        UiKit.applySystemBars(this)

        etServer = findViewById(R.id.etServer)
        etUser = findViewById(R.id.etUser)
        etPassword = findViewById(R.id.etPassword)
        btnLogin = findViewById(R.id.btnLogin)
        tvHint = findViewById(R.id.tvHint)

        etServer.setText(Prefs.server.ifEmpty { "http://192.168.1.100:8000" })
        etUser.setText(Prefs.username)

        if (Prefs.isLoggedIn) {
            withLanPermission { goMain() }
            return
        }

        btnLogin.setOnClickListener {
            val server = etServer.text.toString().trim()
            val user = etUser.text.toString().trim()
            val pass = etPassword.text.toString()
            if (server.isEmpty() || user.isEmpty() || pass.isEmpty()) {
                Toast.makeText(this, "请填写完整信息", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val normalized = if (server.startsWith("http")) server else "http://$server"
            withLanPermission { doLogin(normalized, user, pass) }
        }
    }

    /** 权限就绪后执行；未授权先引导。 */
    private fun withLanPermission(action: () -> Unit) {
        if (LanPermission.isGranted(this)) {
            action()
            return
        }
        pendingAction = action
        LanPermission.ensure(this) {
            pendingAction = null
            action()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val action = pendingAction
        LanPermission.onRequestResult(this, requestCode, grantResults) {
            pendingAction = null
            action?.invoke()
        }
    }

    private fun doLogin(server: String, user: String, pass: String) {
        btnLogin.isEnabled = false
        tvHint.text = "正在连接服务器…"
        UiKit.bg {
            try {
                ApiClient.login(server, user, pass)
                UiKit.ui {
                    Toast.makeText(this, "登录成功", Toast.LENGTH_SHORT).show()
                    goMain()
                }
            } catch (e: Exception) {
                UiKit.ui {
                    btnLogin.isEnabled = true
                    val hint = LanPermission.connectErrorHint(this)
                    if (hint != null) {
                        tvHint.text = "登录失败：${e.message}\n$hint"
                        AlertDialog.Builder(this)
                            .setTitle("无法访问局域网服务器")
                            .setMessage(
                                "$hint\n\n" +
                                    "系统已屏蔽局域网访问，需要授予 SelfKTV「本地网络」权限。\n" +
                                    "授权后回到本页重新点击「登录」即可。"
                            )
                            .setPositiveButton("去授权") { _, _ -> LanPermission.openAppSettings(this) }
                            .setNegativeButton("知道了", null)
                            .show()
                    } else {
                        tvHint.text = "登录失败：${e.message}\n请检查：服务器是否已启动、手机与服务器是否同一局域网、地址端口是否正确"
                    }
                }
            }
        }
    }

    private fun goMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
