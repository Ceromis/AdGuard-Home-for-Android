package com.adguardhome.android.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Base64
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.zIndex
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import com.adguardhome.android.ApiClient
import com.adguardhome.android.ModuleControlClient
import com.adguardhome.android.PauseCountdownService
import com.adguardhome.android.R
import com.adguardhome.android.ui.liquid.lens
import com.adguardhome.android.ui.liquid.vibrancy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import top.yukonga.miuix.kmp.blur.*
import top.yukonga.miuix.kmp.shader.isRenderEffectSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.NavigationBarItem as MiuixNavigationBarItem
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

private data class QueryEntry(val domain: String, val type: String, val client: String, val blocked: Boolean)
private data class FilterEntry(val name: String, val url: String, val rules: Int, val enabled: Boolean)
private data class EditableRule(val id: Long, val value: String)
private object RulePreloadCache { var values: List<String>? = null }
private const val FILTER_SNAPSHOT_PREFS = "filter_subscription_snapshot"
private val DEFAULT_FILTERS = listOf(
    FilterEntry("GOODBYEADS", "https://raw.githubusercontent.com/8680/GOODBYEADS/master/data/rules/dns.txt", 0, true),
    FilterEntry("AdGuard DNS Filter", "https://filters.adtidy.org/android/filters/15_optimized.txt", 0, true),
    FilterEntry("Malicious URL Blocklist (URLHaus)", "https://adguardteam.github.io/HostlistsRegistry/assets/filter_11.txt", 0, true),
)
private data class Dashboard(val running: Boolean, val protection: Boolean, val total: Long, val blocked: Long, val pauseUntil: Long = 0L) {
    val rate: Double get() = if (total == 0L) 0.0 else blocked * 100.0 / total
}

private fun pauseDeadline(status: JSONObject, fallback: Long): Long {
    if (status.optBoolean("protection_enabled")) return 0L
    val now = System.currentTimeMillis()
    for (key in listOf("protection_disabled_until", "protection_until", "disabled_until")) {
        val raw = status.opt(key)
        val parsed = when (raw) {
            is Number -> raw.toLong().let { if (it in 1 until 10_000_000_000L) it * 1_000L else it }
            is String -> runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
            else -> null
        }
        if (parsed != null && parsed > now) return parsed
    }
    // Keep an expired local deadline long enough for the UI timer to actively restore protection.
    if (fallback > 0L && fallback < Long.MAX_VALUE) return fallback
    val rawDuration = status.optLong("protection_disabled_duration", 0L)
    val durationMillis = when {
        rawDuration <= 0L -> 0L
        rawDuration > 365L * 24L * 60L * 60L * 1_000L -> rawDuration / 1_000_000L
        else -> rawDuration
    }
    return if (durationMillis > 0L) now + durationMillis.coerceAtMost(Long.MAX_VALUE - now) else Long.MAX_VALUE
}

private fun requireControlSuccess(result: ModuleControlClient.Result): ModuleControlClient.Result {
    if (result.code == 0) return result
    throw IOException(result.output.trim().ifBlank { "模块控制失败，状态码 ${result.code}" })
}

private enum class MainPage(val label: String, val icon: ImageVector) {
    HOME("概览", Icons.Rounded.Home), LOG("查询", Icons.Rounded.ListAlt),
    FILTERS("过滤", Icons.Rounded.FilterAlt), SETTINGS("设置", Icons.Rounded.Settings)
}
private enum class SubPage { NONE, PROXY, THEME, DNS, RULES, ABOUT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdGuardManager() {
    var subPage by remember { mutableStateOf(SubPage.NONE) }
    var renderedSubPage by remember { mutableStateOf(SubPage.NONE) }
    var backProgress by remember { mutableFloatStateOf(0f) }
    var predictiveCommit by remember { mutableStateOf(false) }
    val theme = LocalThemeSettings.current.value
    val activity = LocalActivity.current
    val backdrop = rememberLayerBackdrop()
    val control = remember { ModuleControlClient() }
    var networkAlert by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            if (networkAlert == null) {
                withContext(Dispatchers.IO) { runCatching { control.execute("network-alert") } }
                    .onSuccess { result -> if (result.code == 0 && result.output.isNotBlank()) networkAlert = result.output.trim() }
            }
            delay(1_000)
        }
    }
    LaunchedEffect(subPage) {
        if (subPage != SubPage.NONE) {
            renderedSubPage = subPage
            predictiveCommit = false
        }
    }
    PredictiveBackHandler(enabled = true) { progress ->
        var committed = false
        try {
            progress.collect { event ->
                backProgress = if (theme.predictiveBack && subPage != SubPage.NONE) event.progress else 0f
            }
            committed = true
            if (subPage != SubPage.NONE) {
                predictiveCommit = theme.predictiveBack
                backProgress = if (theme.predictiveBack) 1f else 0f
                subPage = SubPage.NONE
            } else {
                activity?.finish()
            }
        } finally {
            if (!committed) backProgress = 0f
        }
    }
    LaunchedEffect(subPage, predictiveCommit) {
        if (subPage == SubPage.NONE && predictiveCommit) {
            backProgress = 0f
            delay(450)
            predictiveCommit = false
        }
    }
    val pager = rememberPagerState(pageCount = { MainPage.entries.size })
    val scope = rememberCoroutineScope()
    val primaryPageDepth by animateFloatAsState(
        targetValue = if (subPage == SubPage.NONE) 0f else 1f,
        animationSpec = tween(420),
        label = "primary page depth",
    )
    val page = MainPage.entries[pager.currentPage]
    val bottomBar = @Composable {
        ManagerBottomBar(page, { target -> scope.launch { pager.animateScrollToPage(target.ordinal) } }, theme, backdrop)
    }
    val content = remember { movableContentOf<PaddingValues> { padding ->
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize().layerBackdrop(backdrop).padding(
                start = padding.calculateStartPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                top = padding.calculateTopPadding(),
                end = padding.calculateEndPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
            ),
            beyondViewportPageCount = 3,
        ) { index ->
            when (MainPage.entries[index]) {
                MainPage.HOME -> ManagerPageScaffold("AdGuard Home") { OverviewPage() }
                MainPage.LOG -> ManagerPageScaffold("查询") { QueryLogPage() }
                MainPage.FILTERS -> ManagerPageScaffold("过滤") { FiltersPage() }
                MainPage.SETTINGS -> ManagerPageScaffold("设置") {
                    SettingsPage(
                        { subPage = SubPage.PROXY },
                        { subPage = SubPage.THEME },
                        { subPage = SubPage.DNS },
                        { subPage = SubPage.RULES },
                        { subPage = SubPage.ABOUT },
                    )
                }
            }
        }
    } }
    Box(Modifier.fillMaxSize()) {
        if (theme.style == UiStyle.MIUIX) {
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    val depth = if (predictiveCommit) 0f else primaryPageDepth * (1f - backProgress)
                    translationX = -size.width * 0.08f * depth
                    scaleX = 1f - depth * 0.08f
                    scaleY = 1f - depth * 0.08f
                }
            ) {
                content(PaddingValues(0.dp))
                Box(Modifier.align(Alignment.BottomCenter)) { bottomBar() }
            }
        } else {
            Scaffold(
                bottomBar = bottomBar,
                containerColor = MaterialTheme.colorScheme.background,
                contentWindowInsets = WindowInsets(0),
            ) { content(it) }
        }
        AnimatedVisibility(
            visible = subPage != SubPage.NONE,
            enter = slideInHorizontally(animationSpec = tween(420)) { it } + fadeIn(tween(300)),
            exit = if (predictiveCommit) ExitTransition.None else
                slideOutHorizontally(animationSpec = tween(220)) { it } + fadeOut(tween(140)),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = backProgress * size.width }
                    .background(MaterialTheme.colorScheme.background)
            ) {
            val title = when (renderedSubPage) { SubPage.PROXY -> "订阅兼容"; SubPage.THEME -> "主题设置"; SubPage.DNS -> "系统 DNS"; SubPage.RULES -> "自定义规则"; SubPage.ABOUT -> "关于"; else -> "" }
            ManagerPageScaffold(title, { subPage = SubPage.NONE }) {
                when (renderedSubPage) { SubPage.PROXY -> ProxySubscriptions(); SubPage.THEME -> ThemePage(); SubPage.DNS -> DnsPage(); SubPage.RULES -> CustomRulesPage(); SubPage.ABOUT -> AboutPage(); else -> Unit }
            }
            }
        }
    }
    networkAlert?.let { message ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("网络状态异常") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { runCatching { control.execute("network-alert-ack") } }
                        networkAlert = null
                    }
                }) { Text("我知道了") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManagerPageScaffold(title: String, onBack: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val theme = LocalThemeSettings.current.value
    if (theme.style == UiStyle.MIUIX) {
        val blur = theme.blurBars && isRenderEffectSupported()
        val backdrop = rememberLayerBackdrop()
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop)
            ) { content() }
            BlurSurface(backdrop, blur, Modifier.fillMaxWidth().align(Alignment.TopCenter)) {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().height(48.dp).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onBack != null) {
                        top.yukonga.miuix.kmp.basic.IconButton(onClick = onBack) { MiuixIcon(Icons.Rounded.ArrowBack, "返回") }
                        Spacer(Modifier.width(4.dp))
                    }
                    top.yukonga.miuix.kmp.basic.Text(title, fontSize = 20.sp, color = MiuixTheme.colorScheme.onSurface)
                }
            }
        }
        /*
        MiuixScaffold(
            topBar = {
                BlurSurface(backdrop, blur) {
                    Row(
                        Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (onBack != null) {
                            top.yukonga.miuix.kmp.basic.IconButton(onClick = onBack) { MiuixIcon(Icons.Rounded.ArrowBack, "返回") }
                            Spacer(Modifier.width(4.dp))
                        }
                        top.yukonga.miuix.kmp.basic.Text(title, fontSize = 20.sp, color = MiuixTheme.colorScheme.onSurface)
                    }
                }
            },
            popupHost = { },
            contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Horizontal),
            contentWindowInsets = WindowInsets(0),
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                BlurSurface(backdrop, blur) {
                    Row(
                        Modifier.fillMaxWidth().statusBarsPadding().height(44.dp).padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (onBack != null) {
                            top.yukonga.miuix.kmp.basic.IconButton(onClick = onBack) { MiuixIcon(Icons.Rounded.ArrowBack, "返回") }
                            Spacer(Modifier.width(4.dp))
                        }
                        top.yukonga.miuix.kmp.basic.Text(title, fontSize = 20.sp, color = MiuixTheme.colorScheme.onSurface)
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth().layerBackdrop(backdrop)) { content() }
            }
        }
        */
    } else {
        Scaffold(
            topBar = {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (theme.style == UiStyle.MATERIAL) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.background)
                        .statusBarsPadding()
                        .height(if (onBack == null) 36.dp else 44.dp)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "返回") }
                    Text(title, style = if (onBack == null) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = if (onBack == null) 8.dp else 4.dp))
                }
            },
            containerColor = if (theme.style == UiStyle.MATERIAL) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0),
        ) { padding -> Box(Modifier.fillMaxSize().padding(padding)) { content() } }
    }
}

@Composable
private fun BlurSurface(
    backdrop: LayerBackdrop,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        if (enabled) modifier.textureBlur(
            backdrop = backdrop,
            shape = RoundedCornerShape(0.dp),
            blurRadius = 25f,
            colors = BlurColors(listOf(BlendColorEntry(MaterialTheme.colorScheme.surface.copy(alpha = .82f))))
        ) else modifier.background(MaterialTheme.colorScheme.surface)
    ) { content() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManagerBottomBar(page: MainPage, onPage: (MainPage) -> Unit, settings: ThemeSettings, backdrop: LayerBackdrop) {
    when (settings.style) {
        UiStyle.DEFAULT -> NavigationBar {
            MainPage.entries.forEach { item -> NavigationBarItem(
                selected = page == item, onClick = { onPage(item) },
                icon = { Icon(item.icon, item.label) }, label = { Text(item.label) }
            ) }
        }
        UiStyle.MATERIAL -> ShortNavigationBar {
            MainPage.entries.forEach { item -> ShortNavigationBarItem(
                selected = page == item, onClick = { onPage(item) },
                icon = { Icon(item.icon, item.label) }, label = { Text(item.label) }
            ) }
        }
        UiStyle.MIUIX -> if (settings.floatingBar) {
            Box(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                JellyFloatingBottomBar(
                    selectedIndex = page.ordinal,
                    onSelected = { onPage(MainPage.entries[it]) },
                    tabsCount = MainPage.entries.size,
                    backdrop = backdrop,
                    blurEnabled = settings.blurBars,
                    liquidGlassEnabled = settings.liquidGlass,
                ) { index, selected ->
                    val item = MainPage.entries[index]
                    JellyNavigationItem(item.icon, item.label, selected) { }
                }
            }
        } else {
            BlurSurface(backdrop, settings.blurBars && isRenderEffectSupported()) {
                top.yukonga.miuix.kmp.basic.NavigationBar(
                    color = if (settings.blurBars) Color.Transparent else MiuixTheme.colorScheme.surface
                ) {
                    MainPage.entries.forEach { item ->
                        MiuixNavigationBarItem(
                            modifier = Modifier.weight(1f), selected = page == item,
                            onClick = { onPage(item) }, icon = item.icon, label = item.label
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OverviewPage() {
    val context = LocalContext.current
    val api = remember { ApiClient() }
    val control = remember { ModuleControlClient() }
    val scope = rememberCoroutineScope()
    val statePrefs = remember { context.getSharedPreferences("manager_state", 0) }
    var dashboard by remember { mutableStateOf(Dashboard(false, false, 0, 0)) }
    var message by remember { mutableStateOf("正在连接模块") }
    var busy by remember { mutableStateOf(false) }
    var customPauseDialog by remember { mutableStateOf(false) }
    var customPauseHours by remember { mutableStateOf("0") }
    var customPauseMinutes by remember { mutableStateOf("30") }
    var customPauseSeconds by remember { mutableStateOf("0") }
    var customPauseError by remember { mutableStateOf<String?>(null) }
    var pauseUntil by remember { mutableLongStateOf(statePrefs.getLong("pause_until", 0L)) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    fun reload() = scope.launch {
        val result = withContext(Dispatchers.IO) { runCatching {
            val running = control.execute("status").output.contains("running")
            if (!running) Dashboard(false, false, 0, 0) else {
                val status = api.get("/control/status")
                val stats = api.get("/control/stats")
                Dashboard(true, status.optBoolean("protection_enabled"), stats.optLong("num_dns_queries"), stats.optLong("num_blocked_filtering"), pauseDeadline(status, pauseUntil))
            }
        } }
        result.onSuccess { dashboard = it; pauseUntil = it.pauseUntil; message = if (it.running) "服务运行中" else "服务已停止" }
            .onFailure { message = friendlyError(it) }
    }
    fun moduleAction(action: String) = scope.launch {
        if (busy) return@launch
        busy = true
        val actionName = when (action) { "start" -> "启动服务"; "restart" -> "重启服务"; else -> "停止服务" }
        message = "正在$actionName…"
        val result = withContext(Dispatchers.IO) { runCatching { control.execute(action) } }
        message = result.fold({ if (it.code == 0) "${actionName}完成" else "操作失败：${it.output.trim()}" }, ::friendlyError)
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        busy = false; reload()
    }
    fun setProtection(enabled: Boolean, durationMillis: Long = 0L) = scope.launch {
        val result = withContext(Dispatchers.IO) { runCatching { api.post("/control/protection", JSONObject().put("enabled", enabled).put("duration", durationMillis)) } }
        result.onSuccess {
            pauseUntil = when {
                enabled -> 0L
                durationMillis > 0L -> System.currentTimeMillis() + durationMillis
                else -> Long.MAX_VALUE
            }
            dashboard = dashboard.copy(protection = enabled, pauseUntil = pauseUntil)
            statePrefs.edit().putLong("pause_until", pauseUntil).apply()
            if (!enabled && pauseUntil in 1 until Long.MAX_VALUE) {
                PauseCountdownService.start(context, pauseUntil)
            } else {
                PauseCountdownService.cancel(context)
            }
        }.onFailure { message = friendlyError(it) }
        reload()
    }
    LaunchedEffect(Unit) {
        while (true) {
            val result = withContext(Dispatchers.IO) { runCatching {
                val running = control.execute("status").output.contains("running")
                if (!running) Dashboard(false, false, 0, 0) else {
                    val status = api.get("/control/status")
                    val stats = api.get("/control/stats")
                    Dashboard(true, status.optBoolean("protection_enabled"), stats.optLong("num_dns_queries"), stats.optLong("num_blocked_filtering"), pauseDeadline(status, pauseUntil))
                }
            } }
            result.onSuccess { dashboard = it; pauseUntil = it.pauseUntil; message = if (it.running) "服务运行中 · 每秒自动监测" else "服务已停止 · 每秒自动监测" }
                .onFailure { message = friendlyError(it) }
            delay(1_000)
        }
    }
    LaunchedEffect(dashboard.protection, pauseUntil) {
        if (dashboard.protection) {
            pauseUntil = 0L
            statePrefs.edit().putLong("pause_until", 0L).apply()
            PauseCountdownService.cancel(context)
            return@LaunchedEffect
        }
        while (pauseUntil in 1 until Long.MAX_VALUE) {
            now = System.currentTimeMillis()
            if (now >= pauseUntil) {
                val resumed = withContext(Dispatchers.IO) {
                    runCatching { api.post("/control/protection", JSONObject().put("enabled", true).put("duration", 0L)) }
                }
                if (resumed.isSuccess) {
                    pauseUntil = 0L
                    statePrefs.edit().putLong("pause_until", 0L).apply()
                    reload()
                    break
                }
                message = friendlyError(resumed.exceptionOrNull()!!)
                delay(5_000)
                continue
            }
            delay(1_000)
        }
    }
    val protectionStatus = when {
        dashboard.protection -> "保护已开启"
        pauseUntil == Long.MAX_VALUE || pauseUntil == 0L -> "保护已暂停，直到手动恢复"
        else -> "保护已暂停，剩余 ${formatPauseRemaining((pauseUntil - now).coerceAtLeast(0L))}"
    }
    PageList {
        item {
            ManagerCard(
                if (dashboard.running) "广告拦截正在运行" else "AdGuard Home 已停止",
                message,
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    EndpointMetric("127.0.0.1:5353", "本地 DNS")
                    EndpointMetric("3000", "管理端口")
                    EndpointMetric("ARM64", "内置服务")
                }
            }
        }
        item { SectionLabel("今日概览") }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("查询", dashboard.total.toString(), Modifier.weight(1f))
                StatCard("已拦截", dashboard.blocked.toString(), Modifier.weight(1f))
                StatCard("拦截率", "%.1f%%".format(dashboard.rate), Modifier.weight(1f))
            }
        }
        item { SectionLabel("服务控制") }
        item {
            Row(Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button({ moduleAction("start") }, Modifier.weight(1f).fillMaxHeight(), enabled = !busy) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(5.dp)); Text("启动") }
                FilledTonalButton(
                    { moduleAction("restart") },
                    Modifier.weight(1f).fillMaxHeight(),
                    enabled = !busy,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                ) { Icon(Icons.Rounded.RestartAlt, null); Spacer(Modifier.width(5.dp)); Text("重启") }
                Button(
                    { moduleAction("stop") },
                    Modifier.weight(1f).fillMaxHeight(),
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer),
                ) { Icon(Icons.Rounded.Stop, null); Spacer(Modifier.width(5.dp)); Text("停止") }
            }
        }
        item { SectionLabel("保护控制") }
        item { ManagerCard("广告拦截", protectionStatus) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("过滤保护", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Switch(
                    checked = dashboard.protection,
                    onCheckedChange = { enabled -> setProtection(enabled, 0L) },
                    enabled = dashboard.running,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PauseDurationButton("5 分钟", 300_000L, dashboard.running, ::setProtection, Modifier.weight(1f))
                PauseDurationButton("30 分钟", 1_800_000L, dashboard.running, ::setProtection, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PauseDurationButton("1 小时", 3_600_000L, dashboard.running, ::setProtection, Modifier.weight(1f))
                PauseDurationButton("3 小时", 10_800_000L, dashboard.running, ::setProtection, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PauseDurationButton("12 小时", 43_200_000L, dashboard.running, ::setProtection, Modifier.weight(1f))
                PauseDurationButton("24 小时", 86_400_000L, dashboard.running, ::setProtection, Modifier.weight(1f))
            }
            OutlinedButton(
                onClick = { customPauseError = null; customPauseDialog = true },
                modifier = Modifier.fillMaxWidth(),
                enabled = dashboard.running,
            ) { Icon(Icons.Rounded.Schedule, null); Spacer(Modifier.width(7.dp)); Text("自定义时间") }
        } }
    }
    if (customPauseDialog) {
        AlertDialog(
            onDismissRequest = { customPauseDialog = false },
            title = { Text("自定义暂停时间") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DurationField(customPauseHours, { customPauseHours = it; customPauseError = null }, "时", Modifier.weight(1f), 3)
                        DurationField(customPauseMinutes, { customPauseMinutes = it; customPauseError = null }, "分", Modifier.weight(1f), 2)
                        DurationField(customPauseSeconds, { customPauseSeconds = it; customPauseError = null }, "秒", Modifier.weight(1f), 2)
                    }
                    customPauseError?.let { ErrorText(it) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val hours = customPauseHours.toLongOrNull() ?: 0L
                    val minutes = customPauseMinutes.toLongOrNull() ?: 0L
                    val seconds = customPauseSeconds.toLongOrNull() ?: 0L
                    if (minutes > 59L || seconds > 59L) {
                        customPauseError = "分钟和秒数需在 0 到 59 之间"
                        return@TextButton
                    }
                    val duration = (hours * 3_600L + minutes * 60L + seconds) * 1_000L
                    if (duration <= 0L) {
                        customPauseError = "暂停时间不能为 0"
                        return@TextButton
                    }
                    if (duration > 24L * 24L * 3_600_000L) {
                        customPauseError = "暂停时间最长为 24 天"
                        return@TextButton
                    }
                    setProtection(false, duration)
                    customPauseDialog = false
                }) { Text("开始暂停") }
            },
            dismissButton = { TextButton({ customPauseDialog = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun QueryLogPage() {
    val context = LocalContext.current; val api = remember { ApiClient() }; val scope = rememberCoroutineScope()
    val entries = remember { mutableStateListOf<QueryEntry>() }; var error by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var allowing by remember { mutableStateOf<QueryEntry?>(null) }
    fun reload() = scope.launch {
        val result = withContext(Dispatchers.IO) { runCatching {
            val rows = api.get("/control/querylog?limit=50").optJSONArray("data") ?: JSONArray()
            List(rows.length()) { i -> rows.optJSONObject(i) }.mapNotNull { row -> row?.let {
                val q = it.optJSONObject("question"); val reason = it.optString("reason")
                QueryEntry(q?.optString("name", "未知域名") ?: "未知域名", q?.optString("type", "") ?: "", it.optString("client", "本机"), it.optBoolean("blocked") || reason.contains("Filtered") || reason.contains("Blocked"))
            } }
        } }
        result.onSuccess { entries.clear(); entries.addAll(it); error = null }.onFailure { error = friendlyError(it) }
    }
    LaunchedEffect(Unit) { reload() }
    PageList {
        item { PageTitle("查询日志", "最近 50 条 DNS 请求及处理结果") }
        item { OutlinedButton(onClick = { confirm = true }) { Icon(Icons.Rounded.DeleteSweep, null); Spacer(Modifier.width(8.dp)); Text("清空日志") } }
        error?.let { item { ErrorText(it) } }
        if (entries.isEmpty() && error == null) item { ManagerCard("暂无查询记录") }
        itemsIndexed(entries) { _, row -> ManagerCard(row.domain, "${if (row.blocked) "已拦截" else "已放行"}  ${row.type}  ${row.client}") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = { allowing = row }) { Icon(Icons.Rounded.CheckCircle, null); Spacer(Modifier.width(6.dp)); Text("放行") }
            }
        } }
    }
    if (confirm) ConfirmDialog("清空查询日志", "此操作会删除当前查询记录。", "确认清空", { confirm = false }) {
        confirm = false; scope.launch { withContext(Dispatchers.IO) { runCatching { api.post("/control/querylog_clear", JSONObject()) } }; reload() }
    }
    allowing?.let { row ->
        val rule = "@@||${row.domain.trimEnd('.')}^\$important"
        ConfirmDialog("放行此域名", "确认生成并添加以下放行规则吗？\n\n$rule", "确认放行", { allowing = null }) {
            allowing = null
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching {
                    val array = api.get("/control/filtering/status").optJSONArray("user_rules") ?: JSONArray()
                    val current = List(array.length()) { array.optString(it) }.filter(String::isNotBlank)
                    if (rule !in current) api.post("/control/filtering/set_rules", JSONObject().put("rules", JSONArray(current + rule)))
                    current + rule
                } }
                result.onSuccess {
                    RulePreloadCache.values = it.distinct()
                    Toast.makeText(context, "放行规则已添加", Toast.LENGTH_SHORT).show()
                }.onFailure { error = friendlyError(it) }
            }
        }
    }
}

@Composable
private fun FiltersPage() {
    val context = LocalContext.current; val api = remember { ApiClient() }; val scope = rememberCoroutineScope(); val filters = remember { mutableStateListOf<FilterEntry>() }
    val snapshot = remember { context.getSharedPreferences(FILTER_SNAPSHOT_PREFS, 0) }
    var error by remember { mutableStateOf<String?>(null) }; var adding by remember { mutableStateOf(false) }
    var operationMessage by remember { mutableStateOf<String?>(null) }; var busy by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }; var url by remember { mutableStateOf("") }; var deleting by remember { mutableStateOf<FilterEntry?>(null) }
    fun reload() = scope.launch {
        val result = withContext(Dispatchers.IO) { runCatching {
            val rows = api.get("/control/filtering/status").optJSONArray("filters") ?: JSONArray()
            List(rows.length()) { i -> rows.optJSONObject(i) }.mapNotNull { it?.let { row -> FilterEntry(row.optString("name", "未命名订阅"), row.optString("url"), row.optInt("rules_count"), row.optBoolean("enabled", true)) } }
        } }
        result.onSuccess {
            filters.clear(); filters.addAll(it); error = null
            snapshot.edit().putString("filters", JSONArray(it.map { row -> JSONObject().put("name", row.name).put("url", row.url).put("rules", row.rules).put("enabled", row.enabled) }).toString()).apply()
        }.onFailure { error = friendlyError(it) }
    }
    fun post(path: String, body: JSONObject, progress: String = "正在保存…", success: String = "保存完成") = scope.launch {
        if (busy) return@launch
        busy = true; operationMessage = progress; error = null
        val result = withContext(Dispatchers.IO) { runCatching {
            if (path.endsWith("/set_url") && body.optJSONObject("data")?.optBoolean("enabled") == true) {
                val seeded = ModuleControlClient().execute("filter-seed")
                if (seeded.code != 0 && !(seeded.code == 64 && seeded.output.contains("unsupported control action", true))) {
                    requireControlSuccess(seeded)
                }
            }
            api.post(path, body)
        } }
        result.onSuccess { operationMessage = success }.onFailure { error = friendlyError(it); operationMessage = error }
        Toast.makeText(context, operationMessage, Toast.LENGTH_SHORT).show()
        busy = false; reload()
    }
    LaunchedEffect(Unit) {
        val cached = runCatching { JSONArray(snapshot.getString("filters", "[]")) }.getOrNull()?.let { rows ->
            List(rows.length()) { i -> rows.optJSONObject(i) }.mapNotNull { row -> row?.let { FilterEntry(it.optString("name"), it.optString("url"), it.optInt("rules"), it.optBoolean("enabled", true)) } }
        }.orEmpty()
        if (filters.isEmpty()) filters.addAll(cached.ifEmpty { DEFAULT_FILTERS })
        reload()
    }
    PageList {
        item { PageTitle("过滤规则", "管理 AdGuard Home 过滤订阅") }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { adding = true }, enabled = !busy) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("添加订阅") }
            OutlinedButton(
                onClick = { post("/control/filtering/refresh", JSONObject().put("whitelist", false), "正在更新全部过滤规则…", "全部过滤规则已更新") },
                enabled = !busy,
            ) { if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("更新全部") }
        } }
        operationMessage?.let { item { OperationMessage(it, error != null) } }
        error?.let { item { ErrorText(it) } }
        itemsIndexed(filters) { _, filter -> ManagerCard(filter.name, "${filter.rules} 条规则\n${filter.url}") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("启用", Modifier.weight(1f)); Switch(filter.enabled, { enabled -> post("/control/filtering/set_url", JSONObject().put("url", filter.url).put("data", JSONObject().put("enabled", enabled).put("name", filter.name).put("url", filter.url)), "正在保存过滤状态…", "过滤状态已保存") }, enabled = !busy)
                IconButton(onClick = { deleting = filter }) { Icon(Icons.Rounded.Delete, "删除") }
            }
        } }
    }
    if (adding) AlertDialog(onDismissRequest = { adding = false }, title = { Text("添加过滤订阅") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(name, { name = it }, label = { Text("名称") }); OutlinedTextField(url, { url = it }, label = { Text("HTTP(S) 链接") })
    } }, confirmButton = { TextButton(onClick = {
        if (validUrl(url)) { post("/control/filtering/add_url", JSONObject().put("name", name.ifBlank { url }).put("url", url.trim()).put("whitelist", false), "正在添加过滤订阅…", "过滤订阅已添加"); adding = false; name = ""; url = "" } else error = "请输入有效的 HTTP 或 HTTPS 链接"
    }) { Text("添加") } }, dismissButton = { TextButton(onClick = { adding = false }) { Text("取消") } })
    deleting?.let { filter -> ConfirmDialog("删除过滤订阅", "确定删除“${filter.name}”吗？", "确认删除", { deleting = null }) {
        post("/control/filtering/remove_url", JSONObject().put("url", filter.url).put("whitelist", false), "正在删除过滤订阅…", "过滤订阅已删除"); deleting = null
    } }
}

@Composable
private fun SettingsPage(openProxy: () -> Unit, openTheme: () -> Unit, openDns: () -> Unit, openRules: () -> Unit, openAbout: () -> Unit) {
    val context = LocalContext.current; val theme = LocalThemeSettings.current; val api = remember { ApiClient() }; val control = remember { ModuleControlClient() }
    val scope = rememberCoroutineScope(); var message by remember { mutableStateOf<String?>(null) }; var taskFailed by remember { mutableStateOf(false) }; var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (RulePreloadCache.values == null) withContext(Dispatchers.IO) {
            runCatching {
                val array = api.get("/control/filtering/status").optJSONArray("user_rules") ?: JSONArray()
                List(array.length()) { array.optString(it) }.filter(String::isNotBlank)
            }.onSuccess { RulePreloadCache.values = it }
        }
    }
    fun task(progress: String, success: String, block: () -> Unit) = scope.launch {
        if (busy) return@launch
        busy = true; taskFailed = false; message = progress
        val result = withContext(Dispatchers.IO) { runCatching(block) }
        message = result.fold({ success }, { taskFailed = true; friendlyError(it) })
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        busy = false
    }
    if (theme.value.style == UiStyle.MIUIX) {
        MiuixSettingsContent(
            openProxy = openProxy,
            openTheme = openTheme,
            openDns = openDns,
            openRules = openRules,
            openAbout = openAbout,
            predictiveBack = theme.value.predictiveBack,
            onPredictiveBack = {
                theme.value = theme.value.copy(predictiveBack = it)
                saveTheme(context, theme.value)
            },
            updateFilters = { task("正在更新过滤器…", "过滤器更新完成") { api.post("/control/filtering/refresh", JSONObject().put("whitelist", false)) } },
            clearCache = { task("正在清除 DNS 缓存…", "DNS 缓存已清除") { api.post("/control/cache_clear", JSONObject()) } },
            restart = { task("正在重启模块…", "模块重启完成") { val result = control.execute("restart"); check(result.code == 0) { result.output } } },
            clearLogs = { task("正在清理日志…", "模块日志已清理") { val result = control.execute("logs-clean"); check(result.code == 0) { result.output } } },
            recover = { task("正在恢复系统网络…", "系统网络已恢复") { val result = control.execute("recover"); check(result.code == 0) { result.output } } },
            message = message,
            busy = busy,
        )
        return
    }
    PageList {
        item { EntryCard("订阅兼容", "管理多个代理订阅链接，支持单条编辑和删除。", openProxy) }
        item { EntryCard("主题", "默认、Miuix 与 Material，支持暗色和 Monet。", openTheme) }
        item { EntryCard("自定义规则", "逐条新建、编辑、排序和删除过滤规则。", openRules) }
        item { SwitchCard("预测性返回手势", "Android 14 及以上启用系统预测性返回动画。", theme.value.predictiveBack) {
            theme.value = theme.value.copy(predictiveBack = it); saveTheme(context, theme.value)
        } }
        item { ManagerCard("过滤与缓存", "更新过滤器或清除 AdGuard Home DNS 缓存。") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { task("正在更新过滤器…", "过滤器更新完成") { api.post("/control/filtering/refresh", JSONObject().put("whitelist", false)) } }, enabled = !busy) { Text("更新过滤器") }
                OutlinedButton(onClick = { task("正在清除 DNS 缓存…", "DNS 缓存已清除") { api.post("/control/cache_clear", JSONObject()) } }, enabled = !busy) { Text("清除缓存") }
            }
        } }
        item { ManagerCard("模块日志", "日志集中保存在 /data/adb/agh/logs，并会自动限长。") {
            OutlinedButton(onClick = { task("正在清理日志…", "模块日志已清理") { val result = control.execute("logs-clean"); check(result.code == 0) { result.output } } }, enabled = !busy) {
                Icon(Icons.Rounded.CleaningServices, null); Spacer(Modifier.width(6.dp)); Text("清理日志")
            }
        } }
        item { EntryCard("Android 系统 DNS", "编辑上游 DNS，或停止模块并恢复系统解析器。", openDns) }
        item { EntryCard("关于", "应用信息、作者与 QQ 交流群。", openAbout) }
        item { ManagerCard("模块维护", "作者 Ceromis\n配置目录 /data/adb/agh\n管理端口 127.0.0.1:3000") {
            OutlinedButton(onClick = { task("正在重启模块…", "模块重启完成") { val result = control.execute("restart"); check(result.code == 0) { result.output } } }, enabled = !busy) { Icon(Icons.Rounded.RestartAlt, null); Spacer(Modifier.width(6.dp)); Text("重启模块") }
            OutlinedButton(onClick = { task("正在恢复系统网络…", "系统网络已恢复") { val result = control.execute("recover"); check(result.code == 0) { result.output } } }, enabled = !busy) { Icon(Icons.Rounded.SettingsBackupRestore, null); Spacer(Modifier.width(6.dp)); Text("恢复系统网络") }
            Text("恢复原机情况，避免整机断网；该功能在出错紧急情况下使用。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        } }
        message?.let { item { OperationMessage(it, taskFailed) } }
    }
}

@Composable
private fun MiuixSettingsContent(
    openProxy: () -> Unit,
    openTheme: () -> Unit,
    openDns: () -> Unit,
    openRules: () -> Unit,
    openAbout: () -> Unit,
    predictiveBack: Boolean,
    onPredictiveBack: (Boolean) -> Unit,
    updateFilters: () -> Unit,
    clearCache: () -> Unit,
    restart: () -> Unit,
    clearLogs: () -> Unit,
    recover: () -> Unit,
    message: String?,
    busy: Boolean,
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 12.dp),
        contentPadding = PaddingValues(
            top = 60.dp + WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
            bottom = 96.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            MiuixCard(Modifier.fillMaxWidth()) {
                ArrowPreference(
                    title = "订阅兼容", summary = "管理多个代理订阅链接",
                    startAction = { MiuixIcon(Icons.Rounded.Link, null, Modifier.padding(end = 6.dp)) },
                    onClick = openProxy,
                )
                ArrowPreference(
                    title = "主题", summary = "Miuix 外观、暗色、Monet 与模糊效果",
                    startAction = { MiuixIcon(Icons.Rounded.Palette, null, Modifier.padding(end = 6.dp)) },
                    onClick = openTheme,
                )
                ArrowPreference(
                    title = "自定义规则", summary = "逐条编辑、排序和删除过滤规则",
                    startAction = { MiuixIcon(Icons.Rounded.Rule, null, Modifier.padding(end = 6.dp)) },
                    onClick = openRules,
                )
            }
        }
        item {
            MiuixCard(Modifier.fillMaxWidth()) {
                SwitchPreference(
                    title = "预测性返回手势", summary = "Android 14 及以上启用系统返回动画",
                    startAction = { MiuixIcon(Icons.Rounded.Swipe, null, Modifier.padding(end = 6.dp)) },
                    checked = predictiveBack, onCheckedChange = onPredictiveBack,
                )
            }
        }
        item {
            MiuixCard(Modifier.fillMaxWidth()) {
                ArrowPreference(
                    title = "更新过滤器", summary = "立即拉取全部过滤订阅",
                    startAction = { MiuixIcon(Icons.Rounded.Sync, null, Modifier.padding(end = 6.dp)) }, onClick = { if (!busy) updateFilters() },
                )
                ArrowPreference(
                    title = "清除 DNS 缓存", summary = "清空 AdGuard Home 查询缓存",
                    startAction = { MiuixIcon(Icons.Rounded.DeleteSweep, null, Modifier.padding(end = 6.dp)) }, onClick = { if (!busy) clearCache() },
                )
            }
        }
        item {
            MiuixCard(Modifier.fillMaxWidth()) {
                ArrowPreference(
                    title = "Android 系统 DNS", summary = "编辑上游 DNS 或恢复系统解析器",
                    startAction = { MiuixIcon(Icons.Rounded.Dns, null, Modifier.padding(end = 6.dp)) }, onClick = openDns,
                )
                ArrowPreference(
                    title = "关于", summary = "应用信息、作者与 QQ 交流群",
                    startAction = { MiuixIcon(Icons.Rounded.Info, null, Modifier.padding(end = 6.dp)) }, onClick = openAbout,
                )
            }
        }
        item {
            MiuixCard(Modifier.fillMaxWidth()) {
                ArrowPreference(
                    title = "重启模块", summary = "作者 Ceromis · 管理端口 127.0.0.1:3000",
                    startAction = { MiuixIcon(Icons.Rounded.RestartAlt, null, Modifier.padding(end = 6.dp)) }, onClick = { if (!busy) restart() },
                )
                ArrowPreference(
                    title = "恢复系统网络", summary = "恢复原机情况，避免整机断网；出错紧急情况下使用",
                    startAction = { MiuixIcon(Icons.Rounded.SettingsBackupRestore, null, Modifier.padding(end = 6.dp)) }, onClick = { if (!busy) recover() },
                )
                ArrowPreference(
                    title = "清理模块日志", summary = "日志目录 /data/adb/agh/logs",
                    startAction = { MiuixIcon(Icons.Rounded.CleaningServices, null, Modifier.padding(end = 6.dp)) }, onClick = { if (!busy) clearLogs() },
                )
            }
        }
        message?.let { item { OperationMessage(it, it.startsWith("操作失败")) } }
    }
}

@Composable
private fun ThemePage() {
    val context = LocalContext.current; val state = LocalThemeSettings.current; val value = state.value
    var accentText by remember(value.accent) { mutableStateOf("#%08X".format(value.accent.toInt())) }
    fun update(next: ThemeSettings) {
        state.value = next
        saveTheme(context, next)
    }
    PageList {
        item { PageTitle("主题风格", "界面变更会立即应用") }
        item { ManagerCard("内置主题") { SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            UiStyle.entries.forEachIndexed { index, style -> SegmentedButton(
                selected = value.style == style, onClick = { update(value.copy(style = style)) },
                shape = SegmentedButtonDefaults.itemShape(index, UiStyle.entries.size),
                label = { Text(when (style) { UiStyle.DEFAULT -> "默认"; UiStyle.MIUIX -> "Miuix"; UiStyle.MATERIAL -> "Material" }) }
            ) }
        } } }
        item { SwitchCard("暗色模式", "使用完整暗色配色。", value.dark) { update(value.copy(dark = it)) } }
        item { SwitchCard("Monet 动态颜色", "关闭时使用固定的默认强调色。", value.monet) { update(value.copy(monet = it)) } }
        item {
            ManagerCard("自定义强调色", "关闭 Monet 时覆盖默认、Material 和 Miuix 的主色调。") {
                OutlinedTextField(
                    value = accentText,
                    onValueChange = { text ->
                        accentText = text
                        parseAccentColor(text)?.let { update(value.copy(accent = it)) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("HEX 颜色，例如 #0067C0") },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0xFF0067C0.toInt(), 0xFF2E7D32.toInt(), 0xFF8E24AA.toInt(), 0xFFD84315.toInt(), 0xFF00838F.toInt()).forEach { color ->
                        Box(
                            Modifier.size(34.dp).clip(CircleShape).background(Color(color)).clickable {
                                accentText = "#%08X".format(color.toInt())
                                update(value.copy(accent = color))
                            }
                        )
                    }
                }
            }
        }
        if (value.style == UiStyle.MIUIX) {
            item { SwitchCard("悬浮底栏", "使用 Miuix 悬浮导航布局。", value.floatingBar) { update(value.copy(floatingBar = it, liquidGlass = if (it) value.liquidGlass else false)) } }
            item { SwitchCard("顶栏和底栏模糊", "通过 backdrop 对栏位下方内容实时取样模糊。", value.blurBars) { update(value.copy(blurBars = it)) } }
            if (value.floatingBar) item { SwitchCard("液态玻璃", "为悬浮底栏启用背景折射、色彩增强与模糊。", value.liquidGlass) { update(value.copy(liquidGlass = it)) } }
        }
    }
}

private fun parseAccentColor(text: String): Int? {
    val clean = text.trim().removePrefix("#")
    val value = when (clean.length) {
        6 -> "FF$clean"
        8 -> clean
        else -> return null
    }
    return value.toLongOrNull(16)?.toInt()
}

@Composable
private fun DnsPage() {
    val api = remember { ApiClient() }; val control = remember { ModuleControlClient() }; val scope = rememberCoroutineScope()
    var upstream by remember { mutableStateOf("") }; var message by remember { mutableStateOf<String?>(null) }; var confirmRecover by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val result = withContext(Dispatchers.IO) { runCatching { api.get("/control/dns_info").optJSONArray("upstream_dns")?.let { a -> List(a.length()) { a.optString(it) }.joinToString("\n") } ?: "" } }
        result.onSuccess { upstream = it }.onFailure { message = friendlyError(it) }
    }
    PageList {
        item { PageTitle("Android 系统 DNS", "配置 AdGuard Home 上游或恢复系统解析器") }
        item { ManagerCard("上游 DNS") { OutlinedTextField(upstream, { upstream = it }, Modifier.fillMaxWidth(), minLines = 3, label = { Text("每行一个上游地址") }); Button(onClick = {
            scope.launch { val values = upstream.lines().map(String::trim).filter(String::isNotBlank); val result = withContext(Dispatchers.IO) { runCatching { api.post("/control/dns_config", JSONObject().put("upstream_dns", JSONArray(values))) } }; message = result.fold({ "上游 DNS 已保存" }, ::friendlyError) }
        }) { Text("保存") } } }
        item { ManagerCard("恢复系统 DNS", "停止模块、撤销 DNS 接管，并验证域名解析恢复。") { OutlinedButton(onClick = { confirmRecover = true }) { Text("停止并恢复") } } }
        message?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
    }
    if (confirmRecover) ConfirmDialog("恢复系统 DNS", "这会停止 AdGuard Home 服务并撤销 DNS 接管。", "继续", { confirmRecover = false }) {
        confirmRecover = false; scope.launch { val result = withContext(Dispatchers.IO) { runCatching { control.execute("recover") } }; message = result.fold({ it.output.trim().ifBlank { "系统 DNS 已恢复" } }, ::friendlyError) }
    }
}

@Composable
private fun AboutPage() {
    val context = LocalContext.current
    val versionName = remember(context) {
        context.packageManager
            .getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            .versionName
            ?.replace("(", " (")
            .orEmpty()
    }
    val control = remember { ModuleControlClient() }
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    PageList {
        item {
            Column(
                Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(
                        painterResource(R.drawable.ic_shield),
                        null,
                        Modifier.size(92.dp).padding(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Text("AdGuard Home", style = MaterialTheme.typography.headlineSmall)
                Text(versionName, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("作者 Ceromis", style = MaterialTheme.typography.titleMedium)
            }
        }
        item {
            ManagerCard("日志打包", "将当前模块日志导出为 ADGrizhi.zip，便于提交问题。") {
                Button(
                    onClick = {
                        if (exporting) return@Button
                        exporting = true
                        exportMessage = "正在打包日志…"
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { runCatching {
                                requireControlSuccess(control.execute("logs-export"))
                            } }
                            exportMessage = result.fold(
                                { "已保存到 内部存储/Download/ADGlogs/ADGrizhi.zip" },
                                ::friendlyError,
                            )
                            Toast.makeText(context, exportMessage, Toast.LENGTH_LONG).show()
                            exporting = false
                        }
                    },
                    enabled = !exporting,
                    modifier = Modifier.fillMaxWidth(),
                ) { Icon(Icons.Rounded.Archive, null); Spacer(Modifier.width(8.dp)); Text(if (exporting) "正在打包" else "打包全部日志") }
                exportMessage?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        item {
            ManagerCard("联系与交流", "加入 QQ 交流群，获取使用交流与问题反馈。") {
                Button(
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://qm.qq.com/q/vJDf21uGZM")))
                        }.onFailure { Toast.makeText(context, "未找到可打开链接的浏览器", Toast.LENGTH_SHORT).show() }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Icon(Icons.Rounded.Forum, null); Spacer(Modifier.width(8.dp)); Text("加入 QQ 交流群") }
            }
        }
    }
}

@Composable
private fun CustomRulesPage() {
    val context = LocalContext.current
    val api = remember { ApiClient() }
    val scope = rememberCoroutineScope()
    val rules = remember { mutableStateListOf<EditableRule>() }
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    var nextRuleId by remember { mutableLongStateOf(0L) }
    var editing by remember { mutableStateOf<Int?>(null) }
    var deleting by remember { mutableStateOf<Int?>(null) }
    var confirmFactoryRestore by remember { mutableStateOf(false) }
    var value by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var draggingId by remember { mutableStateOf<Long?>(null) }
    var dragDistance by remember { mutableFloatStateOf(0f) }
    var lastQuickIndex by remember { mutableIntStateOf(-1) }

    fun load() = scope.launch {
        val result = withContext(Dispatchers.IO) { runCatching {
            val array = api.get("/control/filtering/status").optJSONArray("user_rules") ?: JSONArray()
            List(array.length()) { array.optString(it) }.filter(String::isNotBlank)
        } }
        result.onSuccess { values ->
            RulePreloadCache.values = values
            if (rules.map(EditableRule::value) != values) {
                rules.clear()
                rules.addAll(values.map { EditableRule(nextRuleId++, it) })
            }
            error = null
        }
            .onFailure { error = friendlyError(it) }
    }

    fun save(next: List<EditableRule>, message: String, scrollTo: Int? = null, replaceUi: Boolean = true) = scope.launch {
        if (busy) return@launch
        busy = true
        val result = withContext(Dispatchers.IO) { runCatching {
            api.post("/control/filtering/set_rules", JSONObject().put("rules", JSONArray(next.map(EditableRule::value))))
        } }
        result.onSuccess {
            if (replaceUi) { rules.clear(); rules.addAll(next) }
            error = null
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            if (scrollTo != null && rules.isNotEmpty()) listState.animateScrollToItem(scrollTo.coerceIn(rules.indices))
        }.onFailure { error = friendlyError(it); if (!replaceUi) load() }
        busy = false
    }

    LaunchedEffect(Unit) {
        RulePreloadCache.values?.let { cached ->
            if (rules.isEmpty()) rules.addAll(cached.map { EditableRule(nextRuleId++, it) })
        }
        load()
    }
    val theme = LocalThemeSettings.current.value
    val topInset = if (theme.style == UiStyle.MIUIX) 64.dp + WindowInsets.statusBars.asPaddingValues().calculateTopPadding() else 16.dp
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = topInset, end = 16.dp, bottom = 32.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { PageTitle("逐条规则", "共 ${rules.size} 条，长按左侧拖动柄调整顺序") }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { editing = -1; value = "" },
                        modifier = Modifier.weight(1f),
                        enabled = !busy,
                    ) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("新建规则") }
                    OutlinedButton(onClick = { confirmFactoryRestore = true }, enabled = !busy) {
                        Icon(Icons.Rounded.Restore, null); Spacer(Modifier.width(6.dp)); Text("恢复出厂规则")
                    }
                }
            }
            error?.let { item { ErrorText(it) } }
            if (rules.isEmpty() && error == null) item { ManagerCard("暂无自定义规则", "新建的规则会追加在列表最下方。") }
            itemsIndexed(rules, key = { _, rule -> rule.id }) { index, rule ->
                Card(
                    modifier = Modifier
                        .zIndex(if (draggingId == rule.id) 2f else 0f)
                        .graphicsLayer {
                            if (draggingId == rule.id) {
                                translationY = dragDistance
                                scaleX = 1.04f
                                scaleY = 1.04f
                                shadowElevation = 18.dp.toPx()
                            }
                        },
                    colors = CardDefaults.cardColors(containerColor = if (draggingId == rule.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
                ) {
                    Row(
                        Modifier.fillMaxWidth().height(72.dp).padding(start = 14.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Rounded.DragHandle,
                            "长按拖动排序",
                            Modifier
                                .size(40.dp)
                                .padding(8.dp)
                                .pointerInput(rule.id, busy) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { draggingId = rule.id; dragDistance = 0f },
                                        onDragCancel = { draggingId = null; dragDistance = 0f },
                                        onDragEnd = {
                                            if (draggingId != null) save(rules.toList(), "规则顺序已保存", replaceUi = false)
                                            draggingId = null; dragDistance = 0f
                                        },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            dragDistance += amount.y
                                            val threshold = with(density) { 80.dp.toPx() }
                                            var current = rules.indexOfFirst { it.id == rule.id }
                                            while (current >= 0 && kotlin.math.abs(dragDistance) >= threshold) {
                                                val direction = if (dragDistance > 0f) 1 else -1
                                                val target = (current + direction).coerceIn(rules.indices)
                                                if (target == current) break
                                                val moved = rules.removeAt(current)
                                                rules.add(target, moved)
                                                dragDistance -= direction * threshold
                                                current = target
                                            }
                                            val visible = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == rule.id }
                                            if (visible != null) {
                                                val edge = with(density) { 96.dp.toPx() }
                                                val delta = when {
                                                    visible.offset < listState.layoutInfo.viewportStartOffset + edge -> -24f
                                                    visible.offset + visible.size > listState.layoutInfo.viewportEndOffset - edge -> 24f
                                                    else -> 0f
                                                }
                                                if (delta != 0f) scope.launch { listState.scrollBy(delta) }
                                            }
                                        },
                                    )
                                },
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            rule.value,
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        IconButton(onClick = { editing = index; value = rule.value }, enabled = !busy) { Icon(Icons.Rounded.Edit, "编辑") }
                        IconButton(onClick = { deleting = index }, enabled = !busy) { Icon(Icons.Rounded.Delete, "删除") }
                    }
                }
            }
        }
        if (rules.size > 1) {
            val firstRuleIndex = (listState.layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: 2).minus(2).coerceAtLeast(0)
            val fraction = firstRuleIndex.toFloat() / rules.lastIndex.coerceAtLeast(1)
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 5.dp)
                    .width(18.dp)
                    .fillMaxHeight(.64f)
                    .pointerInput(rules.size) {
                        fun quickMove(y: Float) {
                            val index = (rules.lastIndex * (y / size.height).coerceIn(0f, 1f)).toInt()
                            if (index != lastQuickIndex) {
                                lastQuickIndex = index
                                scope.launch { listState.scrollToItem(index + 2) }
                            }
                        }
                        detectTapGestures { quickMove(it.y) }
                    }
                    .pointerInput(rules.size) {
                        detectVerticalDragGestures(
                            onDragStart = { y ->
                                val index = (rules.lastIndex * (y.y / size.height).coerceIn(0f, 1f)).toInt()
                                lastQuickIndex = index
                                scope.launch { listState.scrollToItem(index + 2) }
                            },
                            onVerticalDrag = { change, _ ->
                                change.consume()
                                val index = (rules.lastIndex * (change.position.y / size.height).coerceIn(0f, 1f)).toInt()
                                if (index != lastQuickIndex) {
                                    lastQuickIndex = index
                                    scope.launch { listState.scrollToItem(index + 2) }
                                }
                            },
                        )
                    },
                contentAlignment = Alignment.TopCenter,
            ) {
                Box(Modifier.fillMaxHeight().width(4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest))
                Box(
                    Modifier
                        .fillMaxHeight(fraction.coerceIn(0f, 1f))
                        .padding(top = 1.dp)
                        .size(width = 8.dp, height = 36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
    }
    editing?.let { index ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (index < 0) "新建规则" else "编辑规则") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value,
                    { value = if (index < 0) it.replace("\r", "") else it.replace("\n", "").replace("\r", "") },
                    Modifier.fillMaxWidth(),
                    label = { Text(if (index < 0) "每行一条过滤规则" else "单条过滤规则") },
                    singleLine = index >= 0,
                    minLines = if (index < 0) 5 else 1,
                )
                Text("例如：||example.com^", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            } },
            confirmButton = { TextButton(onClick = {
                val savedRules = value.lines().map(String::trim).filter(String::isNotBlank)
                if (savedRules.isEmpty()) { error = "规则不能为空"; return@TextButton }
                val next = rules.toMutableList()
                if (index < 0) savedRules.distinct().filterNot { saved -> next.any { it.value == saved } }.forEach { next.add(EditableRule(nextRuleId++, it)) }
                else next[index] = next[index].copy(value = savedRules.first())
                save(next, if (index < 0) "已新建 ${savedRules.size} 条规则" else "规则已更新", if (index < 0) next.lastIndex else index)
                editing = null
            }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } },
        )
    }
    deleting?.let { index ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("确定删除本条规则吗？") },
            text = { Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(6.dp)) {
                Text(rules[index].value, Modifier.fillMaxWidth().padding(14.dp))
            } },
            confirmButton = { TextButton(onClick = {
            save(rules.filterIndexed { i, _ -> i != index }, "规则已删除")
            deleting = null
            }) { Text("确认删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
    if (confirmFactoryRestore) ConfirmDialog(
        "恢复出厂规则",
        "确定使用安装时备份的默认规则覆盖当前自定义规则吗？当前自定义修改将被替换。",
        "确认恢复",
        { confirmFactoryRestore = false },
    ) {
        confirmFactoryRestore = false
        scope.launch {
            if (busy) return@launch
            busy = true
            val result = withContext(Dispatchers.IO) { runCatching {
                val factory = requireControlSuccess(ModuleControlClient().execute("factory-rules")).output.lines().map(String::trim).filter(String::isNotBlank)
                api.post("/control/filtering/set_rules", JSONObject().put("rules", JSONArray(factory)))
                factory
            } }
            result.onSuccess { values ->
                rules.clear(); rules.addAll(values.map { EditableRule(nextRuleId++, it) })
                RulePreloadCache.values = values
                Toast.makeText(context, "已恢复出厂规则", Toast.LENGTH_SHORT).show()
                error = null
            }.onFailure { error = friendlyError(it) }
            busy = false
        }
    }
}

@Composable
private fun ProxySubscriptions() {
    val context = LocalContext.current; val control = remember { ModuleControlClient() }; val scope = rememberCoroutineScope(); val links = remember { mutableStateListOf<String>() }
    val notePrefs = remember { context.getSharedPreferences("proxy_subscription_notes", 0) }
    var noteRevision by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<Int?>(null) }; var value by remember { mutableStateOf("") }; var note by remember { mutableStateOf("") }; var deleting by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }; var status by remember { mutableStateOf("正在读取兼容状态") }
    fun persist(next: List<String>, added: Boolean = false, oldUrl: String? = null, savedUrl: String? = null, savedNote: String? = null, removedUrl: String? = null) = scope.launch {
        val subscriptionsChanged = next != links.toList()
        status = when {
            !subscriptionsChanged -> "正在保存备注"
            next.isEmpty() -> "正在关闭代理兼容"
            else -> "正在保存订阅兼容"
        }
        val result = withContext(Dispatchers.IO) { runCatching {
            if (!subscriptionsChanged) {
                null
            } else if (next.isEmpty()) {
                requireControlSuccess(control.execute("proxy-subscription-delete"))
            } else {
                val encoded = Base64.encodeToString(next.joinToString("\n").toByteArray(), Base64.NO_WRAP)
                val applied = control.execute("proxy-subscription-apply", encoded)
                if (applied.code == 64 && applied.output.contains("unsupported control action", true)) {
                    requireControlSuccess(control.execute("proxy-subscription-set", encoded))
                    requireControlSuccess(control.execute("proxy-enable"))
                } else {
                    requireControlSuccess(applied)
                }
            }
        } }
        result.onSuccess {
            notePrefs.edit().apply {
                if (oldUrl != null && oldUrl != savedUrl) remove(oldUrl)
                if (removedUrl != null) remove(removedUrl)
                if (savedUrl != null) {
                    if (savedNote.isNullOrBlank()) remove(savedUrl) else putString(savedUrl, savedNote.trim())
                }
            }.apply()
            noteRevision++
            links.clear(); links.addAll(next); error = null
            status = when {
                !subscriptionsChanged -> "备注已保存"
                next.isEmpty() -> "代理兼容已关闭"
                it?.output?.trim()?.startsWith("active") == true -> "检测到代理运行，兼容已生效"
                else -> "订阅已保存，代理运行时自动启用兼容"
            }
            if (added) Toast.makeText(context, "订阅链接已添加，代理运行时自动生效", Toast.LENGTH_SHORT).show()
        }.onFailure { error = friendlyError(it); status = "代理兼容应用失败" }
    }
    LaunchedEffect(Unit) {
        val subscriptions = withContext(Dispatchers.IO) { runCatching {
            val result = control.execute("proxy-subscription")
            // Older module builds returned code 1 for a valid empty subscription list.
            if (result.code != 0 && !(result.code == 1 && result.output.isBlank())) requireControlSuccess(result)
            result.output
        } }
        subscriptions.onSuccess {
            links.clear()
            links.addAll(it.lines().map(String::trim).filter(::validUrl))
        }.onFailure { error = friendlyError(it) }
        val compat = withContext(Dispatchers.IO) { runCatching { requireControlSuccess(control.execute("proxy-status")) } }
        compat.onSuccess { status = it.output.trim().ifBlank { "代理兼容状态未知" } }
            .onFailure { status = "代理兼容状态暂不可用，仍可添加订阅" }
    }
    PageList {
        item { PageTitle("代理订阅链接", "保存后会立即启用兼容并同步到代理配置") }
        item { ManagerCard("兼容状态", status) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = RoundedCornerShape(6.dp),
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("使用前请关闭系统代理", style = MaterialTheme.typography.titleSmall)
                    Text("为了保证订阅兼容功能生效，请关闭代理软件的“系统代理”功能。")
                    Text("Clash Meta：首页 → 设置 → 网络 → 系统代理")
                    Text("FlClash：下方工具 → 进阶配置 → 网络 → 系统代理")
                }
            }
        } }
        item { Button(onClick = { editing = -1; value = ""; note = "" }, Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("新增订阅链接") } }
        error?.let { item { ErrorText(it) } }
        if (links.isEmpty()) item { ManagerCard("暂无订阅链接", "点击上方按钮添加第一条订阅。") }
        itemsIndexed(links) { index, url -> ManagerCard(notePrefs.getString(url, "").orEmpty().also { noteRevision }.ifBlank { "订阅 ${index + 1}" }, url) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = { editing = index; value = url; note = notePrefs.getString(url, "").orEmpty() }) { Icon(Icons.Rounded.Edit, "编辑") }
            IconButton(onClick = { deleting = index }) { Icon(Icons.Rounded.Delete, "删除") }
        } } }
    }
    editing?.let { index -> AlertDialog(onDismissRequest = { editing = null }, title = { Text(if (index < 0) "新增订阅链接" else "编辑订阅链接") }, text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(note, { note = it.take(40) }, label = { Text("备注（选填）") }, singleLine = true)
        OutlinedTextField(value, { value = it }, label = { Text("HTTP(S) 链接") }, singleLine = true)
    } }, confirmButton = { TextButton(onClick = {
        if (validUrl(value)) { val next = links.toMutableList(); val old = if (index >= 0) next[index] else null; val saved = value.trim(); if (index < 0) next.add(saved) else next[index] = saved; persist(next.distinct(), added = index < 0, oldUrl = old, savedUrl = saved, savedNote = note); editing = null } else error = "请输入有效的 HTTP 或 HTTPS 订阅链接"
    }) { Text("保存") } }, dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } }) }
    deleting?.let { index -> ConfirmDialog("删除订阅链接", "确定删除这条订阅链接吗？此操作无法撤销。", "确认删除", { deleting = null }) {
        persist(links.filterIndexed { i, _ -> i != index }, removedUrl = links[index]); deleting = null
    } }
}

@Composable
private fun PageList(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    val navigationBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val theme = LocalThemeSettings.current.value
    val topInset = if (theme.style == UiStyle.MIUIX) {
        64.dp + WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    } else 16.dp
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = topInset, end = 16.dp, bottom = 112.dp + navigationBottom),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}
@Composable private fun PageTitle(title: String, subtitle: String) = Column(verticalArrangement = Arrangement.spacedBy(3.dp)) { Text(title, style = MaterialTheme.typography.headlineSmall); Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant) }
@Composable private fun SectionLabel(text: String) = Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
@Composable private fun EndpointMetric(value: String, label: String) = Column(Modifier.padding(end = 8.dp)) { Text(value, style = MaterialTheme.typography.labelLarge); Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
@Composable
private fun PauseDurationButton(
    label: String,
    duration: Long,
    enabled: Boolean,
    setProtection: (Boolean, Long) -> Unit,
    modifier: Modifier = Modifier,
) = OutlinedButton({ setProtection(false, duration) }, modifier, enabled = enabled) { Text(label) }

@Composable
private fun DurationField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier, maxLength: Int) =
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.filter(Char::isDigit).take(maxLength)) },
        modifier = modifier,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )

private fun formatPauseRemaining(duration: Long): String {
    val totalSeconds = ((duration + 999L) / 1_000L).coerceAtLeast(1L)
    val hours = totalSeconds / 3_600L
    val minutes = totalSeconds % 3_600L / 60L
    val seconds = totalSeconds % 60L
    return "${hours} 时 ${minutes} 分 ${seconds} 秒"
}
@Composable private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) = Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) { Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text(value, style = MaterialTheme.typography.titleLarge); Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) } }
@Composable private fun ManagerCard(title: String, text: String = "", content: (@Composable () -> Unit)? = null) = Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); if (text.isNotEmpty()) Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant); content?.invoke() } }
@Composable private fun EntryCard(title: String, text: String, action: () -> Unit) = ManagerCard(title, text) { TextButton(onClick = action) { Text("打开"); Icon(Icons.Rounded.ChevronRight, null) } }
@Composable private fun SwitchCard(title: String, text: String, checked: Boolean, enabled: Boolean = true, onChecked: (Boolean) -> Unit) = ManagerCard(title) { Row(verticalAlignment = Alignment.CenterVertically) { Text(text, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant); Switch(checked, onChecked, enabled = enabled) } }
@Composable private fun ErrorText(text: String) = Text(text, color = MaterialTheme.colorScheme.error)
@Composable private fun OperationMessage(text: String, failed: Boolean) = Surface(
    color = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
    shape = RoundedCornerShape(6.dp),
) { Text(text, Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), color = if (failed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface) }
@Composable private fun ConfirmDialog(title: String, text: String, confirm: String, dismiss: () -> Unit, action: () -> Unit) = AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Text(text) }, confirmButton = { TextButton(onClick = action) { Text(confirm) } }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
private fun validUrl(value: String) = value.trim().let { it.startsWith("http://") || it.startsWith("https://") }
private fun friendlyError(error: Throwable): String = when {
    error.message.isNullOrBlank() -> "操作失败：${error.javaClass.simpleName}"
    error.message!!.contains("Connection refused", true) -> "无法连接模块，请确认模块已安装并正在运行"
    else -> "操作失败：${error.message}"
}
