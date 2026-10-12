package com.adguardhome.android

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// 端口校准串行化，避免 onResume 与首页轮询并发覆盖。
private val portSyncMutex = Mutex()

// 通过控制桥读取 AdGuardHome.yaml 里的实际管理端口。
// 只有确认新端口有 HTTP 响应才切换，避免端口刚改完 AdGuard 尚未监听时把 App 锁死在不可用端口；
// 若 YAML 端口未监听，则依次探测当前端口和默认 3000，优先保住能连上的端口。
suspend fun syncApiPortFromBridge(): Boolean = withContext(Dispatchers.IO) {
    portSyncMutex.withLock {
        val rawPort = runCatching {
            val result = ModuleControlClient().execute("port-get")
            if (result.code == 0) result.output.trim() else ""
        }.getOrDefault("")
        val reserved = setOf(3001, 5353)
        val yamlPort = parsePortOutput(rawPort)?.takeIf { it !in reserved }

        val current = ApiClient.getInstance().getPort()
        val candidates = mutableListOf<Int>()
        if (yamlPort != null) candidates.add(yamlPort)
        candidates.add(current)
        if (current != 3000) candidates.add(3000)
        for (candidate in candidates.distinct()) {
            if (candidate == current) continue
            if (apiRespondsOnPort(candidate)) {
                ApiClient.setPort(candidate)
                return@withLock true
            }
        }
        true
    }
}

// 控制桥允许返回多行时,从最后一行提取纯数字端口,忽略诊断性输出。
fun parsePortOutput(raw: String): Int? = raw.lineSequence()
    .map(String::trim)
    .lastOrNull { it.isNotEmpty() && it.length <= 5 && it.all(Char::isDigit) }
    ?.toIntOrNull()
    ?.takeIf { it in 1..65535 }

// 轻量探测：管理端口上有 HTTP 服务应答（含 401/302/404）即视为可用。
fun apiRespondsOnPort(port: Int): Boolean = runCatching {
    val connection = (URL("http://127.0.0.1:$port/control/status").openConnection() as HttpURLConnection).apply {
        connectTimeout = 600
        readTimeout = 1200
        setRequestProperty("Accept", "application/json")
    }
    try {
        connection.responseCode in 200..499
    } finally {
        connection.disconnect()
    }
}.getOrDefault(false)
