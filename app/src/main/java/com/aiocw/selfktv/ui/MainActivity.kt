package com.aiocw.selfktv.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.aiocw.selfktv.R
import com.aiocw.selfktv.data.Prefs
import com.google.android.material.bottomnavigation.BottomNavigationView

/** 主界面：歌房 / 作品 / 听歌 / 我的。 */
class MainActivity : AppCompatActivity() {

    private val songsFragment = SongsFragment()
    private val worksFragment = WorksFragment()
    private val listenFragment = ListenFragment()
    private val profileFragment = ProfileFragment()
    private var active: Fragment = songsFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Prefs.isLoggedIn) {
            goLogin()
            return
        }
        setContentView(R.layout.activity_main)
        val bottomNav = findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNav)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // 顶部/左右留出系统栏；底部由导航栏自己吃掉手势区，
            // 避免导航下方多出一条空白（视觉“高度不对”）
            v.setPadding(bars.left, bars.top, bars.right, 0)
            bottomNav.setPadding(0, 0, 0, bars.bottom)
            insets
        }

        supportFragmentManager.beginTransaction()
            .add(R.id.container, profileFragment, "profile").hide(profileFragment)
            .add(R.id.container, listenFragment, "listen").hide(listenFragment)
            .add(R.id.container, worksFragment, "works").hide(worksFragment)
            .add(R.id.container, songsFragment, "songs")
            .commit()

        bottomNav.setOnItemSelectedListener { item ->
            val target = when (item.itemId) {
                R.id.nav_songs -> songsFragment
                R.id.nav_works -> worksFragment
                R.id.nav_listen -> listenFragment
                else -> profileFragment
            }
            if (target != active) {
                supportFragmentManager.beginTransaction().hide(active).show(target).commit()
                active = target
            }
            true
        }

        // Android 16+/澎湃OS4：局域网访问需先持有本地网络权限（被撤销后重新引导）
        if (!LanPermission.isGranted(this)) {
            LanPermission.ensure(this) {}
        }

        // Android 13+：通知权限是媒体通知 / 灵动岛的展示前提，启动时请求一次
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1002)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        LanPermission.onRequestResult(this, requestCode, grantResults) {}
    }

    fun goLogin() {
        Prefs.clearLogin()
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }
}
