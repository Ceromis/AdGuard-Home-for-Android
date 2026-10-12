package com.adguardhome.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import com.adguardhome.android.liveisland.LiveIslandService
import com.adguardhome.android.ui.AdGuardManager
import com.adguardhome.android.ui.ManagerTheme

class ManagerActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        // 每次进入应用都通过控制桥校准管理端口，确认新端口可连通后才切换，
        // 避免端口刚被修改而 AdGuard 尚未监听时把 App 锁死在不可用端口上。
        Thread {
            runCatching { kotlinx.coroutines.runBlocking { syncApiPortFromBridge() } }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 先发起通知权限请求,避免前台服务通知尚未被授权导致 silent fail。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2201)
        }
        LiveIslandService.evaluateStartup(this)
        enableEdgeToEdge()
        setContent {
            ManagerTheme {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    AdGuardManager()
                }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2201) {
            // 授权后再拉起灵动岛前台服务,否则通知会被系统静默拦掉。
            LiveIslandService.evaluateStartup(this)
        }
    }
}
