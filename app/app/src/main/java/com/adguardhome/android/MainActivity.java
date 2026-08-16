package com.adguardhome.android;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.util.Base64;

import com.topjohnwu.superuser.Shell;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class MainActivity extends Activity {
    static {
        Shell.enableVerboseLogging = false;
        Shell.setDefaultBuilder(Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(8));
    }

    private static final String CONTROL = "/data/adb/modules/adguardhome/system/bin/aghctl";
    private static final int PAGE_OVERVIEW = 0;
    private static final int PAGE_QUERY_LOG = 1;
    private static final int PAGE_FILTERS = 2;
    private static final int PAGE_SETTINGS = 3;
    private static final int PAGE_PROXY_PATHS = 4;
    private static final int PAGE_THEME = 5;
    private static final long OVERVIEW_REFRESH_INTERVAL_MS = 1000L;
    private static final int BLUE = Color.rgb(0, 103, 192);
    private static final int GREEN = Color.rgb(16, 124, 65);
    private static final int RED = Color.rgb(196, 43, 28);
    private static final int TEXT = Color.rgb(31, 35, 42);
    private static final int MUTED = Color.rgb(96, 103, 115);
    private static final int BACKGROUND = Color.rgb(246, 248, 252);
    private static final int SURFACE = Color.WHITE;
    private static final String PREF_PAUSE_UNTIL = "protection_pause_until";
    private static final String PREF_THEME = "theme_config";

    private String themeMode = "default";
    private boolean darkMode;
    private boolean monetEnabled;
    private int accentColor = BLUE;
    private boolean floatingBottomBar;
    private boolean blurBars;
    private boolean liquidGlass;
    private boolean gestureCompatibility;
    private int themeBackground = BACKGROUND;
    private int themeSurface = SURFACE;
    private int themeText = TEXT;
    private int themeMuted = MUTED;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService statusExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "agh-status-refresh");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService rootExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "agh-root-compat");
        thread.setDaemon(true);
        return thread;
    });
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ApiClient api = new ApiClient();
    private final ModuleControlClient moduleControl = new ModuleControlClient();
    private final TextView[] tabs = new TextView[4];
    private FrameLayout pageHost;
    private TextView headerState;
    private int activePage = -1;
    private boolean serviceRunning;
    private boolean apiConnected;
    private boolean protectionEnabled;
    private volatile long protectionPauseUntilMs;
    private JSONObject dnsInfo;
    private JSONArray userRules = new JSONArray();
    private ObjectAnimator headerPulse;
    private AlertDialog loadingDialog;
    private volatile boolean legacyRootTimedOut;
    private boolean moduleOperationRunning;
    private boolean appVisible;
    private OnBackInvokedCallback backInvokedCallback;
    private boolean overviewRefreshInFlight;
    private TextView overviewStatusTitle;
    private TextView overviewStatusDetail;
    private TextView overviewQueries;
    private TextView overviewBlocked;
    private TextView overviewRate;
    private TextView protectionSwitchState;
    private Switch protectionSwitch;
    private boolean updatingProtectionSwitch;
    private Switch proxyCompatibilitySwitch;
    private TextView proxyCompatibilityState;
    private boolean updatingProxyCompatibility;
    private final Runnable overviewRefresh = this::refreshOverview;
    private final Runnable protectionCountdown = this::refreshProtectionCountdown;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadTheme();
        applyThemePalette();
        protectionPauseUntilMs = getPreferences(MODE_PRIVATE).getLong(PREF_PAUSE_UNTIL, 0L);
        getWindow().setStatusBarColor(themeBackground);
        getWindow().setNavigationBarColor(themeBackground);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        applyGestureCompatibility();
        View shell = buildApplicationShell();
        setContentView(shell);
        shell.requestApplyInsets();
        registerBackNavigation();
        showPage(PAGE_OVERVIEW);
    }

    private void applyGestureCompatibility() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        getWindow().setNavigationBarContrastEnforced(!gestureCompatibility);
        getWindow().getDecorView().setSystemUiVisibility((darkMode ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR)
                | (gestureCompatibility ? View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION : 0));
    }

    private void loadTheme() {
        android.content.SharedPreferences p = getSharedPreferences(PREF_THEME, MODE_PRIVATE);
        themeMode = p.getString("mode", "default");
        darkMode = p.getBoolean("dark", false);
        monetEnabled = p.getBoolean("monet", false);
        accentColor = p.getInt("accent", BLUE);
        floatingBottomBar = p.getBoolean("floating", false);
        blurBars = p.getBoolean("blur", false);
        liquidGlass = p.getBoolean("liquid", false);
        gestureCompatibility = p.getBoolean("gesture", false);
    }

    private void saveTheme() {
        getSharedPreferences(PREF_THEME, MODE_PRIVATE).edit()
                .putString("mode", themeMode).putBoolean("dark", darkMode)
                .putBoolean("monet", monetEnabled).putInt("accent", accentColor)
                .putBoolean("floating", floatingBottomBar).putBoolean("blur", blurBars)
                .putBoolean("liquid", liquidGlass).putBoolean("gesture", gestureCompatibility).apply();
    }

    private void applyThemePalette() {
        int baseAccent = BLUE;
        if (monetEnabled && Build.VERSION.SDK_INT >= 31) {
            try { baseAccent = getResources().getColor(android.R.color.system_accent1_600, getTheme()); } catch (Exception ignored) { }
        }
        accentColor = baseAccent;
        if ("miuix".equals(themeMode)) {
            themeBackground = darkMode ? Color.rgb(18, 20, 24) : Color.rgb(246, 248, 252);
            themeSurface = darkMode ? Color.rgb(31, 34, 40) : Color.WHITE;
        } else if ("material".equals(themeMode)) {
            themeBackground = darkMode ? Color.rgb(20, 20, 20) : Color.rgb(248, 249, 251);
            themeSurface = darkMode ? Color.rgb(35, 35, 35) : Color.WHITE;
        } else {
            themeBackground = darkMode ? Color.rgb(22, 24, 28) : BACKGROUND;
            themeSurface = darkMode ? Color.rgb(32, 35, 41) : SURFACE;
        }
        themeText = darkMode ? Color.rgb(240, 242, 246) : TEXT;
        themeMuted = darkMode ? Color.rgb(173, 180, 191) : MUTED;
    }

    @Override
    public void onBackPressed() {
        navigateBack();
    }

    private void registerBackNavigation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        backInvokedCallback = this::navigateBack;
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, backInvokedCallback);
    }

    private void navigateBack() {
        if (activePage == PAGE_PROXY_PATHS || activePage == PAGE_THEME) {
            showPage(PAGE_SETTINGS);
        } else {
            finish();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        appVisible = true;
        if (activePage == PAGE_OVERVIEW) scheduleOverviewRefresh(0);
    }

    @Override
    protected void onPause() {
        appVisible = false;
        mainHandler.removeCallbacks(overviewRefresh);
        mainHandler.removeCallbacks(protectionCountdown);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && backInvokedCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backInvokedCallback);
            backInvokedCallback = null;
        }
        if (headerPulse != null) headerPulse.cancel();
        mainHandler.removeCallbacks(overviewRefresh);
        mainHandler.removeCallbacks(protectionCountdown);
        hideLoading();
        executor.shutdownNow();
        statusExecutor.shutdownNow();
        rootExecutor.shutdownNow();
        super.onDestroy();
    }

    private View buildApplicationShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(themeBackground);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(0, bars.top, 0, bars.bottom);
            }
            return insets;
        });

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20), dp(9), dp(20), dp(7));
        header.setBackgroundColor("miuix".equals(themeMode) && blurBars ? withAlpha(themeSurface, darkMode ? 224 : 210) : themeBackground);
        LinearLayout titleBlock = new LinearLayout(this);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        titleBlock.addView(text("AdGuard Home", 22, TEXT, true));
        titleBlock.addView(text("本地 DNS 防护中心", 12, MUTED, false));
        header.addView(titleBlock, new LinearLayout.LayoutParams(0, dp(62), 1));
        headerState = text("正在连接", 12, accentColor, true);
        headerState.setGravity(Gravity.CENTER);
        headerState.setPadding(dp(14), 0, dp(14), 0);
        headerState.setBackground(rounded(withAlpha(accentColor, 28), 16));
        header.addView(headerState, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)));
        root.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(78)));

        pageHost = new FrameLayout(this);
        root.addView(pageHost, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp("miuix".equals(themeMode) && floatingBottomBar ? 72 : 66));
        if ("miuix".equals(themeMode) && floatingBottomBar) barParams.setMargins(dp(16), dp(4), dp(16), dp(8));
        root.addView(buildTabBar(), barParams);
        return root;
    }

    private View buildTabBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.setPadding(dp(10), dp(6), dp(10), dp(7));
        boolean miuix = "miuix".equals(themeMode);
        boolean floating = miuix && floatingBottomBar;
        boolean glass = floating && liquidGlass;
        bar.setBackground(glass ? glassBackground() : rounded(miuix && blurBars ? withAlpha(themeSurface, darkMode ? 224 : 210) : themeSurface, floating ? 20 : 0));
        if (floating) { bar.setElevation(dp(glass ? 16 : 9)); bar.setPadding(dp(14), dp(6), dp(14), dp(8)); }
        String[] labels = {"概览", "查询", "过滤", "设置"};
        for (int i = 0; i < labels.length; i++) {
            final int page = i;
            TextView tab = text(labels[i], 13, MUTED, false);
            tab.setGravity(Gravity.CENTER);
            tab.setOnClickListener(view -> showPage(page));
            tabs[i] = tab;
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(50), 1);
            params.setMargins(dp(3), 0, dp(3), 0);
            bar.addView(tab, params);
        }
        return bar;
    }

    private void showPage(int page) {
        if (activePage == page) return;
        mainHandler.removeCallbacks(overviewRefresh);
        mainHandler.removeCallbacks(protectionCountdown);
        activePage = page;
        for (int i = 0; i < tabs.length; i++) {
            boolean selected = i == page;
            tabs[i].setTextColor(selected ? accentColor : themeMuted);
            tabs[i].setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
            tabs[i].setBackground(selected ? rounded(withAlpha(accentColor, 28), 8) : rounded(Color.TRANSPARENT, 8));
        }
        View content;
        if (page == PAGE_QUERY_LOG) content = buildQueryLogPage();
        else if (page == PAGE_FILTERS) content = buildFiltersPage();
        else if (page == PAGE_SETTINGS) content = buildSettingsPage();
        else if (page == PAGE_PROXY_PATHS) content = buildProxyPathsPage();
        else if (page == PAGE_THEME) content = buildThemePage();
        else content = buildOverviewPage();
        pageHost.removeAllViews();
        pageHost.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        content.setAlpha(0f);
        content.setTranslationY(dp(10));
        content.animate().alpha(1f).translationY(0f).setDuration(210).setInterpolator(new DecelerateInterpolator()).start();
        if (page == PAGE_PROXY_PATHS) mainHandler.post(this::showSystemProxyNotice);
        if (page == PAGE_OVERVIEW && appVisible) scheduleOverviewRefresh(OVERVIEW_REFRESH_INTERVAL_MS);
    }

    private void showSystemProxyNotice() {
        if (activePage != PAGE_PROXY_PATHS || isFinishing()) return;

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_VERTICAL);
        content.setPadding(dp(24), dp(28), dp(24), dp(24));
        content.setBackgroundColor(BACKGROUND);

        content.addView(text("使用前请关闭系统代理", 24, TEXT, true));
        content.addView(text("为了保证订阅兼容和广告拦截正常生效，请先关闭代理软件中的“系统代理”功能。", 15, MUTED, false),
                matchWrapMargins(0, 12, 0, 24));

        LinearLayout clashMeta = card();
        clashMeta.addView(text("Clash Meta", 16, TEXT, true));
        clashMeta.addView(text("首页 - 设置 - 网络 - 系统代理", 14, BLUE, false), matchWrapMargins(0, 8, 0, 0));
        content.addView(clashMeta, matchWrapMargins(0, 0, 0, 10));

        LinearLayout flClash = card();
        flClash.addView(text("FlClash", 16, TEXT, true));
        flClash.addView(text("下方 Tab 栏 - 工具 - 进阶配置 - 网络 - 系统代理", 14, BLUE, false), matchWrapMargins(0, 8, 0, 0));
        content.addView(flClash, matchWrapMargins(0, 0, 0, 24));

        new AlertDialog.Builder(this).setTitle("使用前请关闭系统代理")
                .setView(content).setCancelable(false)
                .setPositiveButton("我已关闭系统代理", null).show();
    }

    private View buildOverviewPage() {
        LinearLayout column = pageColumn();
        overviewStatusTitle = text("正在检查服务状态", 20, TEXT, true);
        overviewStatusDetail = text("正在连接本地 API", 13, MUTED, false);
        LinearLayout statusCard = card();
        statusCard.addView(overviewStatusTitle);
        statusCard.addView(space(7));
        statusCard.addView(overviewStatusDetail);
        statusCard.addView(space(18));
        LinearLayout endpoints = new LinearLayout(this);
        endpoints.setOrientation(LinearLayout.HORIZONTAL);
        endpoints.addView(metric("127.0.0.1:5353", "本地 DNS"), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        endpoints.addView(metric("3000", "管理端口"), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        endpoints.addView(metric("ARM64", "内置服务"), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        statusCard.addView(endpoints);
        column.addView(statusCard, matchWrapMargins(0, 0, 0, 14));

        column.addView(sectionTitle("今日概览"));
        LinearLayout stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        overviewQueries = metricValue("--", "DNS 请求");
        overviewBlocked = metricValue("--", "已拦截");
        overviewRate = metricValue("--", "拦截率");
        stats.addView(statBlock(overviewQueries), new LinearLayout.LayoutParams(0, dp(88), 1));
        stats.addView(statBlock(overviewBlocked), new LinearLayout.LayoutParams(0, dp(88), 1));
        stats.addView(statBlock(overviewRate), new LinearLayout.LayoutParams(0, dp(88), 1));
        column.addView(stats, matchWrapMargins(0, 7, 0, 16));

        column.addView(sectionTitle("保护控制"));
        LinearLayout protectionPanel = card();
        LinearLayout switchRow = new LinearLayout(this);
        switchRow.setOrientation(LinearLayout.HORIZONTAL);
        switchRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout switchLabels = new LinearLayout(this);
        switchLabels.setOrientation(LinearLayout.VERTICAL);
        switchLabels.addView(text("广告过滤", 14, TEXT, true));
        protectionSwitchState = text("正在读取保护状态", 12, MUTED, false);
        switchLabels.addView(protectionSwitchState, matchWrapMargins(0, 3, 0, 0));
        switchRow.addView(switchLabels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        protectionSwitch = new Switch(this);
        protectionSwitch.setShowText(false);
        protectionSwitch.setEnabled(apiConnected);
        protectionSwitch.setChecked(apiConnected && protectionEnabled);
        protectionSwitchState.setText(!apiConnected ? "正在读取保护状态"
                : protectionEnabled ? "保护已开启" : "保护已暂停");
        protectionSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (!updatingProtectionSwitch) setProtection(checked, 0);
        });
        switchRow.addView(protectionSwitch, new LinearLayout.LayoutParams(dp(52), dp(40)));
        protectionPanel.addView(switchRow);
        protectionPanel.addView(space(12));

        int neutralButton = Color.rgb(238, 240, 244);
        LinearLayout firstPauseRow = new LinearLayout(this);
        firstPauseRow.setOrientation(LinearLayout.HORIZONTAL);
        firstPauseRow.addView(actionButton("恢复保护", neutralButton, GREEN, () -> setProtection(true, 0)), gridCell(1, 0, 0));
        firstPauseRow.addView(actionButton("暂停 5 分钟", neutralButton, TEXT, () -> setProtection(false, 300000)), gridCell(1, 8, 0));
        protectionPanel.addView(firstPauseRow);

        LinearLayout secondPauseRow = new LinearLayout(this);
        secondPauseRow.setOrientation(LinearLayout.HORIZONTAL);
        secondPauseRow.addView(actionButton("暂停 1 小时", neutralButton, TEXT, () -> setProtection(false, 3600000)), gridCell(1, 0, 8));
        secondPauseRow.addView(actionButton("暂停 24 小时", neutralButton, TEXT, () -> setProtection(false, 86400000)), gridCell(1, 8, 8));
        protectionPanel.addView(secondPauseRow);
        column.addView(protectionPanel, matchWrapMargins(0, 8, 0, 17));

        column.addView(sectionTitle("服务控制"));
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.addView(actionButton("启动", BLUE, Color.WHITE, () -> serviceAction("start", "服务已启动")), new LinearLayout.LayoutParams(0, dp(48), 1));
        controls.addView(actionButton("重启", Color.rgb(235, 237, 241), TEXT, () -> serviceAction("restart", "服务已重启")), marginWeight(1, 8));
        controls.addView(actionButton("停止", Color.rgb(253, 235, 235), RED, () -> serviceAction("stop", "服务已停止")), marginWeight(1, 8));
        column.addView(controls, matchWrapMargins(0, 8, 0, 20));

        refreshOverview();
        return scroll(column);
    }

    private void refreshOverview() {
        mainHandler.removeCallbacks(overviewRefresh);
        if (activePage != PAGE_OVERVIEW || overviewRefreshInFlight || overviewStatusTitle == null) return;
        overviewRefreshInFlight = true;
        if (overviewQueries.getText().toString().equals("--")) setHeaderState("正在连接", BLUE, true);
        statusExecutor.execute(() -> {
            JSONObject status = null;
            JSONObject stats = null;
            String apiError = "";

            try {
                status = api.get("/control/status");
                stats = api.get("/control/stats");
            } catch (Exception exception) {
                apiError = friendlyError(exception);
            }

            ShellResult root = null;
            boolean running = status != null;
            if (!running) {
                root = rootCommand(CONTROL + " status");
                running = root.output.trim().equals("running");
            }

            if (running && status == null) {
                try {
                    status = api.get("/control/status");
                    stats = api.get("/control/stats");
                    apiError = "";
                } catch (Exception exception) {
                    apiError = friendlyError(exception);
                }
            }
            serviceRunning = running;
            apiConnected = status != null;
            protectionEnabled = status != null && status.optBoolean("protection_enabled", true);
            boolean finalRunning = running;
            boolean finalApiConnected = status != null;
            ShellResult finalRoot = root;
            JSONObject finalStats = stats;
            String finalApiError = apiError;
            mainHandler.post(() -> {
                overviewRefreshInFlight = false;
                if (activePage != PAGE_OVERVIEW || overviewStatusTitle == null) return;
                if (protectionEnabled) {
                    protectionPauseUntilMs = 0L;
                    persistPauseUntil();
                }
                if (!finalRunning) {
                    boolean rootAvailable = finalRoot != null && finalRoot.exitCode == 0;
                    overviewStatusTitle.setText(rootAvailable ? "AdGuard Home 已停止" : "无法连接 AdGuard Home");
                    overviewStatusDetail.setText(rootAvailable ? "点击启动恢复本地 DNS 防护"
                            : finalApiError + "；模块控制桥不可用，请更新并重启模块");
                    setHeaderState(rootAvailable ? "服务停止" : "连接失败", RED, false);
                } else if (!finalApiConnected) {
                    overviewStatusTitle.setText("进程已运行，但 API 连接失败");
                    overviewStatusDetail.setText(finalApiError);
                    setHeaderState("API 异常", RED, false);
                } else if (protectionEnabled) {
                    overviewStatusTitle.setText("广告拦截正在运行");
                    overviewStatusDetail.setText("本地 DNS、查询日志和过滤规则均可用");
                    setHeaderState("已受保护", GREEN, false);
                } else {
                    overviewStatusTitle.setText("广告拦截已暂停");
                    overviewStatusDetail.setText("服务仍在运行，可随时恢复保护");
                    setHeaderState("保护暂停", Color.rgb(154, 96, 0), false);
                }
                updateProtectionControls(finalApiConnected);
                if (finalStats != null) {
                    long total = finalStats.optLong("num_dns_queries", 0);
                    long denied = finalStats.optLong("num_blocked_filtering", 0);
                    overviewQueries.setText(formatNumber(total));
                    overviewBlocked.setText(formatNumber(denied));
                    overviewRate.setText(total == 0 ? "0%" : String.format(Locale.US, "%.1f%%", denied * 100.0 / total));
                }
                if (appVisible) scheduleOverviewRefresh(OVERVIEW_REFRESH_INTERVAL_MS);
            });
        });
    }

    private void scheduleOverviewRefresh(long delayMs) {
        mainHandler.removeCallbacks(overviewRefresh);
        if (appVisible && activePage == PAGE_OVERVIEW) mainHandler.postDelayed(overviewRefresh, delayMs);
    }

    private View buildQueryLogPage() {
        LinearLayout column = pageColumn();
        LinearLayout heading = headingWithAction("查询日志", "清空", () -> confirm("清空查询日志", "此操作会删除当前查询记录。", () -> apiAction("/control/querylog_clear", new JSONObject(), "查询日志已清空", () -> showPageAgain(PAGE_QUERY_LOG))));
        column.addView(heading);
        TextView subtitle = text("最近的 DNS 请求及处理结果", 13, MUTED, false);
        column.addView(subtitle, matchWrapMargins(0, 3, 0, 12));
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        TextView loading = text("正在读取查询记录...", 14, MUTED, false);
        list.addView(loading, matchWrapMargins(4, 18, 4, 18));
        column.addView(list);
        loadQueryLog(list);
        return scroll(column);
    }

    private void loadQueryLog(LinearLayout list) {
        executor.execute(() -> {
            try {
                JSONObject result = api.get("/control/querylog?limit=50");
                JSONArray entries = result.optJSONArray("data");
                mainHandler.post(() -> {
                    list.removeAllViews();
                    if (entries == null || entries.length() == 0) {
                        list.addView(emptyState("还没有查询记录"));
                        return;
                    }
                    for (int i = 0; i < entries.length(); i++) {
                        JSONObject entry = entries.optJSONObject(i);
                        if (entry != null) list.addView(queryRow(entry), matchWrapMargins(0, 0, 0, 8));
                    }
                });
            } catch (Exception exception) {
                showLoadError(list, exception);
            }
        });
    }

    private View queryRow(JSONObject entry) {
        JSONObject question = entry.optJSONObject("question");
        String domain = question == null ? "未知域名" : question.optString("name", "未知域名");
        String type = question == null ? "" : question.optString("type", "");
        String reason = entry.optString("reason", "");
        boolean denied = reason.contains("Filtered") || reason.contains("Blocked") || entry.optBoolean("blocked", false);
        String client = entry.optString("client", "本机");
        LinearLayout row = card();
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        TextView domainView = text(domain, 14, TEXT, true);
        domainView.setSingleLine(true);
        top.addView(domainView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView state = text(denied ? "已拦截" : "已放行", 11, denied ? RED : GREEN, true);
        state.setPadding(dp(9), dp(4), dp(9), dp(4));
        state.setBackground(rounded(denied ? Color.rgb(253, 235, 235) : Color.rgb(225, 246, 235), 12));
        top.addView(state);
        row.addView(top);
        row.addView(space(8));
        row.addView(text(type + "  ·  " + client + (reason.isEmpty() ? "" : "  ·  " + reason), 11, MUTED, false));
        return row;
    }

    private View buildFiltersPage() {
        LinearLayout column = pageColumn();
        column.addView(headingWithAction("过滤规则", "添加订阅", this::showAddFilterDialog));
        column.addView(text("管理订阅列表与自定义规则条目", 13, MUTED, false), matchWrapMargins(0, 3, 0, 12));
        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.addView(actionButton("更新全部订阅", BLUE, Color.WHITE, () -> apiAction("/control/filtering/refresh", json("whitelist", false), "过滤订阅已更新", () -> showPageAgain(PAGE_FILTERS))), new LinearLayout.LayoutParams(0, dp(46), 1));
        tools.addView(actionButton("编辑用户规则", Color.rgb(235, 237, 241), TEXT, this::showUserRulesDialog), marginWeight(1, 8));
        column.addView(tools, matchWrapMargins(0, 0, 0, 14));
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.addView(text("正在读取过滤规则...", 14, MUTED, false), matchWrapMargins(4, 18, 4, 18));
        column.addView(list);
        loadFilters(list);
        return scroll(column);
    }

    private void loadFilters(LinearLayout list) {
        executor.execute(() -> {
            try {
                JSONObject result = api.get("/control/filtering/status");
                JSONArray filters = result.optJSONArray("filters");
                userRules = result.optJSONArray("user_rules") == null ? new JSONArray() : result.optJSONArray("user_rules");
                mainHandler.post(() -> {
                    list.removeAllViews();
                    LinearLayout userCard = card();
                    userCard.addView(text("用户自定义规则", 15, TEXT, true));
                    userCard.addView(text(userRules.length() + " 条规则，点击上方按钮可逐条编辑", 12, MUTED, false), matchWrapMargins(0, 6, 0, 0));
                    list.addView(userCard, matchWrapMargins(0, 0, 0, 9));
                    if (filters == null || filters.length() == 0) {
                        list.addView(emptyState("当前没有过滤订阅"));
                        return;
                    }
                    for (int i = 0; i < filters.length(); i++) {
                        JSONObject filter = filters.optJSONObject(i);
                        if (filter != null) list.addView(filterRow(filter), matchWrapMargins(0, 0, 0, 9));
                    }
                });
            } catch (Exception exception) {
                showLoadError(list, exception);
            }
        });
    }

    private View filterRow(JSONObject filter) {
        String name = filter.optString("name", "未命名订阅");
        String url = filter.optString("url", "");
        int rules = filter.optInt("rules_count", 0);
        boolean enabled = filter.optBoolean("enabled", true);
        LinearLayout card = card();
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        TextView nameView = text(name, 15, TEXT, true);
        nameView.setSingleLine(true);
        details.addView(nameView);
        details.addView(text(rules + " 条规则", 12, MUTED, false), matchWrapMargins(0, 5, 0, 0));
        top.addView(details, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Switch toggle = new Switch(this);
        toggle.setChecked(enabled);
        toggle.setOnCheckedChangeListener((button, checked) -> {
            JSONObject data = new JSONObject();
            try {
                data.put("enabled", checked).put("name", name).put("url", url);
                apiAction("/control/filtering/set_url", new JSONObject().put("url", url).put("data", data), checked ? "订阅已启用" : "订阅已停用", null);
            } catch (Exception exception) {
                toast(friendlyError(exception));
            }
        });
        top.addView(toggle);
        card.addView(top);
        TextView urlView = text(url, 11, MUTED, false);
        urlView.setMaxLines(2);
        card.addView(urlView, matchWrapMargins(0, 9, 0, 8));
        Button remove = smallButton("删除订阅", RED, () -> confirm("删除过滤订阅", "确定删除“" + name + "”吗？", () -> apiAction("/control/filtering/remove_url", json("url", url, "whitelist", false), "订阅已删除", () -> showPageAgain(PAGE_FILTERS))));
        card.addView(remove, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)));
        return card;
    }

    private View buildSettingsPage() {
        LinearLayout column = pageColumn();
        column.addView(text("设置", 24, TEXT, true));
        column.addView(text("DNS、缓存、系统接管与模块维护", 13, MUTED, false), matchWrapMargins(0, 3, 0, 14));

        LinearLayout proxyEntry = card();
        proxyEntry.addView(text("Clash / Mihomo 代理兼容", 16, TEXT, true));
        proxyEntry.addView(text("请关闭代理软件的系统代理后，再管理订阅链接。", 12, MUTED, false), matchWrapMargins(0, 7, 0, 10));
        proxyEntry.addView(actionButton("订阅兼容", withAlpha(BLUE, 28), BLUE, () -> showPage(PAGE_PROXY_PATHS)), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        column.addView(proxyEntry, matchWrapMargins(0, 0, 0, 10));

        LinearLayout themeEntry = card();
        themeEntry.addView(text("主题", 16, TEXT, true));
        themeEntry.addView(text("内置 Miuix、Material 与默认主题，支持暗色、Monet 和强调色。", 12, MUTED, false), matchWrapMargins(0, 7, 0, 10));
        themeEntry.addView(actionButton("打开主题设置", withAlpha(accentColor, 28), accentColor, () -> showPage(PAGE_THEME)), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        column.addView(themeEntry, matchWrapMargins(0, 0, 0, 10));

        LinearLayout gestureEntry = card();
        gestureEntry.addView(text("预览性手势兼容", 16, TEXT, true));
        gestureEntry.addView(text("为实验性手势导航保留兼容开关。", 12, MUTED, false), matchWrapMargins(0, 7, 0, 4));
        Switch gestureSwitch = new Switch(this);
        gestureSwitch.setText("启用手势兼容"); gestureSwitch.setTextColor(TEXT);
        gestureSwitch.setChecked(gestureCompatibility);
        gestureSwitch.setOnCheckedChangeListener((b, c) -> { gestureCompatibility = c; saveTheme(); applyGestureCompatibility(); });
        gestureEntry.addView(gestureSwitch);
        column.addView(gestureEntry, matchWrapMargins(0, 0, 0, 10));

        LinearLayout dnsCard = card();
        dnsCard.addView(text("上游 DNS", 16, TEXT, true));
        TextView upstreams = text("正在读取...", 12, MUTED, false);
        upstreams.setMaxLines(5);
        dnsCard.addView(upstreams, matchWrapMargins(0, 8, 0, 11));
        dnsCard.addView(smallButton("编辑上游服务器", BLUE, this::showUpstreamDialog), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));

        LinearLayout filteringCard = card();
        filteringCard.addView(text("过滤与缓存", 16, TEXT, true));
        filteringCard.addView(text("规则更新后立即生效，清除缓存不会删除配置。", 12, MUTED, false), matchWrapMargins(0, 7, 0, 12));
        LinearLayout filterActions = new LinearLayout(this);
        filterActions.setOrientation(LinearLayout.HORIZONTAL);
        filterActions.addView(actionButton("更新过滤器", BLUE, Color.WHITE, () -> apiAction("/control/filtering/refresh", json("whitelist", false), "过滤器已更新", null)), new LinearLayout.LayoutParams(0, dp(45), 1));
        filterActions.addView(actionButton("清除 DNS 缓存", Color.rgb(235, 237, 241), TEXT, () -> apiAction("/control/cache_clear", new JSONObject(), "DNS 缓存已清除", null)), marginWeight(1, 8));
        filteringCard.addView(filterActions);
        column.addView(filteringCard, matchWrapMargins(0, 0, 0, 10));

        column.addView(dnsCard, matchWrapMargins(0, 0, 0, 10));

        LinearLayout systemCard = card();
        systemCard.addView(text("Android 系统 DNS", 16, TEXT, true));
        systemCard.addView(text("接管后必须通过真实域名解析测试；失败会立即恢复原 DNS，避免整机断网。", 12, MUTED, false), matchWrapMargins(0, 7, 0, 12));
        LinearLayout systemActions = new LinearLayout(this);
        systemActions.setOrientation(LinearLayout.HORIZONTAL);
        systemActions.addView(actionButton("安全重试接管", BLUE, Color.WHITE,
                () -> rootAction(CONTROL + " rules", "DNS 接管检查已完成", null)),
                new LinearLayout.LayoutParams(0, dp(45), 1));
        systemActions.addView(actionButton("恢复系统网络", Color.rgb(253, 235, 235), RED,
                () -> rootAction(CONTROL + " recover", "系统 DNS 已恢复，服务已停止",
                        () -> showPageAgain(PAGE_OVERVIEW))), marginWeight(1, 8));
        systemCard.addView(systemActions);
        column.addView(systemCard, matchWrapMargins(0, 0, 0, 10));

        LinearLayout proxyCard = card();
        proxyCard.addView(text("Clash / Mihomo 兼容", 16, TEXT, true));
        LinearLayout proxySwitchRow = new LinearLayout(this);
        proxySwitchRow.setOrientation(LinearLayout.HORIZONTAL);
        proxySwitchRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout proxyLabels = new LinearLayout(this);
        proxyLabels.setOrientation(LinearLayout.VERTICAL);
        proxyLabels.addView(text("代理 DNS 兼容", 14, TEXT, true));
        proxyCompatibilityState = text("正在读取兼容状态...", 12, MUTED, false);
        proxyLabels.addView(proxyCompatibilityState, matchWrapMargins(0, 3, 0, 0));
        proxySwitchRow.addView(proxyLabels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        proxyCompatibilitySwitch = new Switch(this);
        proxyCompatibilitySwitch.setShowText(false);
        proxyCompatibilitySwitch.setEnabled(false);
        proxyCompatibilitySwitch.setOnCheckedChangeListener((button, checked) -> {
            if (!updatingProxyCompatibility) setProxyCompatibility(checked);
        });
        proxySwitchRow.addView(proxyCompatibilitySwitch, new LinearLayout.LayoutParams(dp(52), dp(40)));
        proxyCard.addView(proxySwitchRow);
        proxyCard.addView(text("只需填写代理订阅链接。开启后会自动识别已安装代理 App 的配置，备份并适配 DNS；关闭时自动恢复原配置。", 12, MUTED, false), matchWrapMargins(0, 10, 0, 0));
        proxyCard.addView(actionButton("订阅兼容  ›", Color.rgb(235, 242, 252), BLUE,
                () -> showPage(PAGE_PROXY_PATHS)), matchWrapMargins(0, 12, 0, 0));
        proxyCard.setVisibility(View.GONE);
        column.addView(proxyCard, matchWrapMargins(0, 0, 0, 10));

        LinearLayout moduleCard = card();
        moduleCard.addView(text("模块维护", 16, TEXT, true));
        moduleCard.addView(text("作者 Ceromis", 12, MUTED, true), matchWrapMargins(0, 7, 0, 0));
        moduleCard.addView(text("配置目录 /data/adb/agh\n默认账户 root / root\n管理端口 127.0.0.1:3000", 12, MUTED, false), matchWrapMargins(0, 7, 0, 12));
        moduleCard.addView(smallButton("重启 AdGuard Home", BLUE, () -> serviceAction("restart", "服务已重启")), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));
        column.addView(moduleCard, matchWrapMargins(0, 0, 0, 20));
        loadDnsInfo(upstreams);
        return scroll(column);
    }

    private View buildProxyPathsPage() {
        LinearLayout column = pageColumn();
        column.addView(headingWithAction("订阅兼容", "返回设置", () -> showPage(PAGE_SETTINGS)));
        column.addView(text("管理多个代理订阅链接。保存后会自动识别代理 App 配置并接入 AdGuard Home，关闭兼容后恢复原配置。", 13, MUTED, false), matchWrapMargins(0, 7, 0, 14));

        LinearLayout subscriptionCard = card();
        subscriptionCard.addView(text("代理订阅链接", 16, TEXT, true));
        subscriptionCard.addView(text("每行填写一个订阅链接。保存后会按顺序接入代理配置，并自动开启兼容。", 12, MUTED, false), matchWrapMargins(0, 7, 0, 10));
        LinearLayout subscriptionList = new LinearLayout(this);
        subscriptionList.setOrientation(LinearLayout.VERTICAL);
        subscriptionCard.addView(actionButton("新增订阅链接", BLUE, Color.WHITE,
                () -> showSingleSubscriptionDialog("", -1, subscriptionList)),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        subscriptionList.addView(emptyState("正在读取订阅链接..."));
        subscriptionCard.addView(subscriptionList, matchWrapMargins(0, 10, 0, 0));
        column.addView(subscriptionCard, matchWrapMargins(0, 0, 0, 10));
        loadProxySubscriptions(subscriptionList);

        return scroll(column);
    }

    private View buildThemePage() {
        LinearLayout column = pageColumn();
        column.addView(headingWithAction("主题设置", "返回设置", () -> showPage(PAGE_SETTINGS)));
        column.addView(text("选择主题风格并调整显示效果，修改会立即应用。", 13, themeMuted, false), matchWrapMargins(0, 6, 0, 14));
        LinearLayout style = card(); style.addView(text("主题风格", 16, themeText, true));
        String[] names = {"默认", "Miuix", "Material"};
        for (int i = 0; i < names.length; i++) { final int choice = i; Button b = actionButton(names[i], choice == themeIndex() ? withAlpha(accentColor, 36) : themeSurface, choice == themeIndex() ? accentColor : themeText, () -> { themeMode = choice == 1 ? "miuix" : choice == 2 ? "material" : "default"; saveTheme(); applyThemePalette(); buildAndReplaceShell(); }); style.addView(b, matchWrapMargins(0, 8, 0, 0)); }
        column.addView(style, matchWrapMargins(0, 0, 0, 10));
        LinearLayout options = card(); options.addView(text("显示选项", 16, themeText, true));
        options.addView(themeSwitch("暗色模式", darkMode, v -> { darkMode = v; saveTheme(); applyThemePalette(); buildAndReplaceShell(); }));
        options.addView(themeSwitch("启用 Monet 动态颜色", monetEnabled, v -> { monetEnabled = v; saveTheme(); applyThemePalette(); buildAndReplaceShell(); }));
        if ("miuix".equals(themeMode)) {
            options.addView(text("Miuix 底栏效果", 13, themeMuted, true), matchWrapMargins(0, 12, 0, 0));
            options.addView(themeSwitch("悬浮底栏", floatingBottomBar, v -> { floatingBottomBar = v; saveTheme(); buildAndReplaceShell(); }));
            options.addView(themeSwitch("顶栏和底栏模糊", blurBars, v -> { blurBars = v; saveTheme(); buildAndReplaceShell(); }));
            options.addView(themeSwitch("悬浮底栏液态玻璃", liquidGlass, v -> { liquidGlass = v; saveTheme(); buildAndReplaceShell(); }));
        }
        column.addView(options, matchWrapMargins(0, 0, 0, 10));
        return scroll(column);
    }

    private int themeIndex() { return "miuix".equals(themeMode) ? 1 : "material".equals(themeMode) ? 2 : 0; }
    private Switch themeSwitch(String label, boolean checked, final java.util.function.Consumer<Boolean> action) { Switch s = new Switch(this); s.setText(label); s.setTextColor(themeText); s.setChecked(checked); s.setOnCheckedChangeListener((b, v) -> action.accept(v)); return s; }

    /* Legacy dialog retained temporarily only for source compatibility; theme settings use PAGE_THEME. */
    private void showThemeDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(8), 0, dp(8), 0);
        String[] modes = {"默认", "Miuix", "Material"};
        android.widget.Spinner mode = new android.widget.Spinner(this);
        mode.setAdapter(new android.widget.ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, modes));
        mode.setSelection("miuix".equals(themeMode) ? 1 : "material".equals(themeMode) ? 2 : 0);
        form.addView(text("主题风格", 13, themeMuted, false)); form.addView(mode);
        Switch dark = new Switch(this); dark.setText("暗色模式"); dark.setTextColor(themeText); dark.setChecked(darkMode); form.addView(dark);
        Switch monet = new Switch(this); monet.setText("启用 Monet 动态颜色"); monet.setTextColor(themeText); monet.setChecked(monetEnabled); form.addView(monet);
        Switch floating = new Switch(this); floating.setText("Miuix 悬浮底栏"); floating.setTextColor(themeText); floating.setChecked(floatingBottomBar); form.addView(floating);
        Switch blur = new Switch(this); blur.setText("顶栏和底栏模糊"); blur.setTextColor(themeText); blur.setChecked(blurBars); form.addView(blur);
        Switch liquid = new Switch(this); liquid.setText("悬浮底栏液态玻璃"); liquid.setTextColor(themeText); liquid.setChecked(liquidGlass); form.addView(liquid);
        new AlertDialog.Builder(this).setTitle("主题设置").setView(form).setNegativeButton("取消", null).setPositiveButton("应用", (d, w) -> {
            themeMode = mode.getSelectedItemPosition() == 1 ? "miuix" : mode.getSelectedItemPosition() == 2 ? "material" : "default";
            darkMode = dark.isChecked(); monetEnabled = monet.isChecked(); floatingBottomBar = floating.isChecked(); blurBars = blur.isChecked(); liquidGlass = liquid.isChecked();
            saveTheme(); applyThemePalette(); getWindow().setStatusBarColor(themeBackground); getWindow().setNavigationBarColor(themeBackground); activePage = -1; buildAndReplaceShell();
        }).show();
    }

    private void buildAndReplaceShell() { int target = activePage; View shell = buildApplicationShell(); setContentView(shell); shell.requestApplyInsets(); activePage = -1; showPage(target == PAGE_THEME ? PAGE_THEME : PAGE_SETTINGS); }


    private void loadProxyPaths(LinearLayout list) {
        executor.execute(() -> {
            try {
                ModuleControlClient.Result result = moduleControl.execute("proxy-paths");
                if (result.code != 0) throw new IOException(result.output.trim());
                List<String[]> entries = new ArrayList<>();
                for (String line : result.output.split("\\r?\\n")) {
                    if (line.trim().isEmpty()) continue;
                    String[] fields = line.split("\\t", 4);
                    if (fields.length >= 3) {
                        String urls = "";
                        if (fields.length == 4 && !fields[3].isEmpty()) {
                            try {
                                urls = new String(Base64.decode(fields[3], Base64.DEFAULT), java.nio.charset.StandardCharsets.UTF_8);
                            } catch (IllegalArgumentException ignored) {
                            }
                        }
                        entries.add(new String[]{fields[0], fields[1], fields[2], urls});
                    }
                }
                mainHandler.post(() -> {
                    if (activePage != PAGE_PROXY_PATHS) return;
                    list.removeAllViews();
                    if (entries.isEmpty()) {
                        list.addView(emptyState("尚未收录配置文件。开启兼容后会自动检测，也可以手动添加。"));
                        return;
                    }
                    for (String[] entry : entries) {
                        list.addView(proxyPathCard(entry[0], entry[1], entry[2], entry[3]), matchWrapMargins(0, 0, 0, 9));
                    }
                });
            } catch (Exception exception) {
                showLoadError(list, exception);
            }
        });
    }

    private View proxyPathCard(String source, String group, String path, String subscriptionUrls) {
        LinearLayout card = card();
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        String sourceLabel = "auto".equals(source) ? "自动检测" : "手动添加";
        TextView badge = text(sourceLabel, 12, "auto".equals(source) ? GREEN : BLUE, true);
        badge.setPadding(dp(9), dp(4), dp(9), dp(4));
        badge.setBackground(rounded(withAlpha("auto".equals(source) ? GREEN : BLUE, 24), 12));
        heading.addView(badge);
        LinearLayout.LayoutParams groupParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        groupParams.setMargins(dp(10), 0, 0, 0);
        heading.addView(text(proxyGroupLabel(group), 12, MUTED, false), groupParams);
        card.addView(heading);
        TextView pathView = text(path, 13, TEXT, false);
        pathView.setTextIsSelectable(true);
        card.addView(pathView, matchWrapMargins(0, 10, 0, 10));
        if (!subscriptionUrls.isEmpty()) {
            card.addView(text("检测到的订阅链接", 12, MUTED, true));
            TextView urlsView = text(subscriptionUrls, 12, BLUE, false);
            urlsView.setTextIsSelectable(true);
            card.addView(urlsView, matchWrapMargins(0, 5, 0, 10));
        }
        card.addView(smallButton("删除", RED, () -> {
                    confirm("删除配置路径", "确定从目录中删除此路径吗？自动检测项目仍在使用时会被再次收录。", () -> deleteProxyPath(path));
                }),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)));
        return card;
    }

    private String proxyGroupLabel(String group) {
        if ("cmfa_alpha".equals(group)) return "Clash Meta for Android Alpha";
        if ("cmfa_meta".equals(group)) return "Clash Meta for Android";
        if ("clash_android".equals(group)) return "Clash for Android";
        if ("box_bll".equals(group)) return "Box for Root";
        if ("clash_module".equals(group)) return "Clash 模块";
        if ("custom".equals(group)) return "其他代理应用";
        return group;
    }

    private void loadProxySubscriptions(LinearLayout list) {
        executor.execute(() -> {
            try {
                ModuleControlClient.Result result = moduleControl.execute("proxy-subscription");
                if (result.code != 0) throw new IOException(result.output.trim());
                String current = result.output.trim();
                mainHandler.post(() -> {
                    if (activePage != PAGE_PROXY_PATHS) return;
                    list.removeAllViews();
                    List<String> urls = subscriptionLines(current);
                    if (urls.isEmpty()) {
                        list.addView(emptyState("尚未填写订阅链接"));
                        return;
                    }
                    for (int index = 0; index < urls.size(); index++) {
                        list.addView(subscriptionItem(urls, index, list), matchWrapMargins(0, 0, 0, 8));
                    }
                });
            } catch (Exception exception) {
                mainHandler.post(() -> {
                    if (activePage != PAGE_PROXY_PATHS) return;
                    list.removeAllViews();
                    list.addView(text("暂时无法读取已保存链接，请先新增订阅链接。", 13, MUTED, false));
                });
            }
        });
    }

    private List<String> subscriptionLines(String value) {
        List<String> urls = new ArrayList<>();
        for (String line : value.split("\\r?\\n")) {
            String url = line.trim();
            if ((url.startsWith("http://") || url.startsWith("https://")) && !urls.contains(url)) urls.add(url);
        }
        return urls;
    }

    private View subscriptionItem(List<String> urls, int index, LinearLayout list) {
        LinearLayout item = card();
        TextView value = text(urls.get(index), 13, TEXT, false);
        value.setTextIsSelectable(true);
        item.addView(value, matchWrapMargins(0, 0, 0, 9));
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.addView(smallButton("编辑", BLUE, () -> showSingleSubscriptionDialog(urls.get(index), index, list)),
                new LinearLayout.LayoutParams(0, dp(38), 1));
        Button delete = smallButton("删除", RED, () -> confirm("删除此订阅链接", "确定删除这一条订阅链接吗？",
                () -> confirm("再次确认删除", "删除后该链接将从代理配置中移除。", () -> {
                    List<String> updated = new ArrayList<>(urls);
                    updated.remove(index);
                    saveSubscriptionList(updated, list);
                })));
        LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38));
        deleteParams.setMargins(dp(8), 0, 0, 0);
        actions.addView(delete, deleteParams);
        item.addView(actions);
        return item;
    }

    private void showSingleSubscriptionDialog(String current, int index, LinearLayout list) {
        EditText input = dialogInput(current, "https://example.com/subscription");
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(index < 0 ? "新增订阅链接" : "编辑订阅链接")
                .setView(input).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            String url = input.getText().toString().trim();
            if (!(url.startsWith("http://") || url.startsWith("https://")) || url.indexOf(' ') >= 0
                    || url.indexOf('\n') >= 0 || url.indexOf('\r') >= 0 || url.indexOf('\t') >= 0) {
                toast("请输入有效的 http:// 或 https:// 订阅链接");
                return;
            }
            List<String> updated = new ArrayList<>(urlsFromList(list));
            if (updated.contains(url) && (index < 0 || !url.equals(updated.get(index)))) {
                toast("该订阅链接已存在");
                return;
            }
            if (index < 0) updated.add(url); else updated.set(index, url);
            dialog.dismiss();
            saveSubscriptionList(updated, list);
        }));
        dialog.show();
    }

    private List<String> urlsFromList(LinearLayout list) {
        List<String> urls = new ArrayList<>();
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            if (!(child instanceof ViewGroup)) continue;
            View first = ((ViewGroup) child).getChildAt(0);
            if (first instanceof TextView) urls.add(((TextView) first).getText().toString());
        }
        return urls;
    }

    private void saveSubscriptionList(List<String> urls, LinearLayout list) {
        String value = join(urls, "\n");
        if (value.isEmpty()) {
            deleteProxySubscription();
            return;
        }
        changeProxySubscription("proxy-subscription-set", value, "订阅链接已保存");
        executor.execute(() -> mainHandler.postDelayed(() -> loadProxySubscriptions(list), 700));
    }

    private void showAddProxyPathDialog() {
        EditText input = dialogInput("", "绝对路径：具体 YAML 文件或配置目录");
        new AlertDialog.Builder(this).setTitle("新增文件或目录").setMessage("请输入设备上的绝对路径。目录内的 YAML 文件会被递归扫描。")
                .setView(input).setNegativeButton("取消", null)
                .setPositiveButton("添加", (dialog, which) -> {
                    String path = input.getText().toString().trim();
                    if (path.isEmpty()) {
                        toast("请输入文件或目录路径");
                        return;
                    }
                    changeProxyPath("proxy-path-add", path, "配置路径已添加");
                }).show();
    }

    private void deleteProxyPath(String path) {
        changeProxyPath("proxy-path-delete", path, "配置路径已删除");
    }

    private void changeProxyPath(String action, String path, String success) {
        if (!showLoading("正在更新配置文件目录...")) return;
        executor.execute(() -> {
            try {
                String encoded = Base64.encodeToString(path.getBytes(java.nio.charset.StandardCharsets.UTF_8), Base64.NO_WRAP);
                ModuleControlClient.Result result = moduleControl.execute(action, encoded);
                mainHandler.post(() -> {
                    hideLoading();
                    if (result.code == 0) {
                        toast(success);
                        showPageAgain(PAGE_PROXY_PATHS);
                    } else {
                        toast(result.output.contains("invalid-path") ? "路径无效：必须是安全目录中的 YAML 文件或现有目录"
                                : result.output.contains("unsupported-config") ? "该文件不是可适配的 Clash / Mihomo 配置" : rootFailureMessage(new ShellResult(result.code, result.output)));
                    }
                });
            } catch (Exception exception) {
                mainHandler.post(() -> {
                    hideLoading();
                    toast(friendlyError(exception));
                });
            }
        });
    }

    private void showSubscriptionDialog(String current) {
        EditText input = dialogInput(current, "每行一个 http:// 或 https:// 订阅链接");
        input.setMinLines(6);
        input.setSingleLine(false);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("订阅兼容")
                .setMessage("可填写多个订阅链接，每行一个。保存后会按顺序接入代理配置。")
                .setView(input).setNegativeButton("取消", null)
                .setPositiveButton("保存", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
                    String urls = input.getText().toString().trim();
                    String[] lines = urls.split("\\r?\\n");
                    java.util.HashSet<String> unique = new java.util.HashSet<>();
                    boolean valid = !urls.isEmpty();
                    for (String line : lines) {
                        String url = line.trim();
                        if (url.isEmpty() || !(url.startsWith("https://") || url.startsWith("http://"))
                                || url.indexOf('\t') >= 0 || url.indexOf(' ') >= 0 || !unique.add(url)) {
                            valid = false;
                            break;
                        }
                    }
                    if (!valid) {
                        toast("请每行填写一个不重复的 http:// 或 https:// 订阅链接");
                        return;
                    }
                    dialog.dismiss();
                    changeProxySubscription("proxy-subscription-set", urls, "订阅链接已保存");
                }));
        dialog.show();
    }

    private void deleteProxySubscription() {
        changeProxySubscription("proxy-subscription-delete", null, "订阅链接已删除");
    }

    private void changeProxySubscription(String action, String value, String success) {
        if (!showLoading("正在更新代理订阅设置...")) return;
        executor.execute(() -> {
            try {
                ModuleControlClient.Result result = value == null ? moduleControl.execute(action)
                        : moduleControl.execute(action, Base64.encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8), Base64.NO_WRAP));
                final ShellResult activation = result.code == 0 && "proxy-subscription-set".equals(action)
                        ? rootCommand(CONTROL + " proxy-enable") : null;
                mainHandler.post(() -> {
                    hideLoading();
                    boolean completed = result.code == 0 && (activation == null || activation.exitCode == 0);
                    if (completed) {
                        toast("proxy-subscription-set".equals(action) ? "订阅已保存，代理兼容已自动开启" : success);
                        showPageAgain(PAGE_PROXY_PATHS);
                    } else {
                        toast(result.output.contains("invalid-url") ? "订阅链接格式无效" : activation != null
                                ? rootFailureMessage(activation) : rootFailureMessage(new ShellResult(result.code, result.output)));
                    }
                });
            } catch (Exception exception) {
                mainHandler.post(() -> {
                    hideLoading();
                    toast(friendlyError(exception));
                });
            }
        });
    }

    private void loadProxyStatus() {
        executor.execute(() -> {
            ShellResult result = rootCommand(CONTROL + " proxy-status");
            String output = result.output == null ? "" : result.output.trim();
            boolean enabledMarker = result.exitCode == 0 && output.startsWith("enabled");
            boolean enabled = enabledMarker;
            mainHandler.post(() -> {
                if (proxyCompatibilitySwitch == null || proxyCompatibilityState == null) return;
                updatingProxyCompatibility = true;
                proxyCompatibilitySwitch.setChecked(enabled);
                proxyCompatibilitySwitch.setEnabled(result.exitCode == 0);
                proxyCompatibilityState.setText(result.exitCode == 0
                        ? enabled ? "兼容已开启，配置会自动保持同步"
                        : enabledMarker ? "未检测到可管理的代理配置，兼容未生效" : "兼容未开启"
                        : "模块暂不支持此功能，请更新并重启模块");
                updatingProxyCompatibility = false;
            });
        });
    }

    private void setProxyCompatibility(boolean enabled) {
        if (proxyCompatibilitySwitch == null || proxyCompatibilityState == null) return;
        updatingProxyCompatibility = true;
        proxyCompatibilitySwitch.setEnabled(false);
        proxyCompatibilityState.setText(enabled ? "正在备份并适配代理配置..." : "正在恢复代理配置...");
        executor.execute(() -> {
            ShellResult result = rootCommand(CONTROL + (enabled ? " proxy-enable" : " proxy-disable"));
            mainHandler.post(() -> {
                if (proxyCompatibilitySwitch == null || proxyCompatibilityState == null) return;
                boolean success = result.exitCode == 0;
                proxyCompatibilitySwitch.setChecked(success ? enabled : !enabled);
                proxyCompatibilitySwitch.setEnabled(true);
                proxyCompatibilityState.setText(success
                        ? enabled ? "兼容已开启，配置会自动保持同步" : "兼容已关闭，原配置已恢复"
                        : enabled ? "启用失败，已保持关闭" : "关闭失败，兼容仍保持开启");
                updatingProxyCompatibility = false;
                toast(success ? enabled ? "代理兼容已启用" : "代理兼容已关闭" : rootFailureMessage(result));
            });
        });
    }

    private void loadDnsInfo(TextView upstreams) {
        executor.execute(() -> {
            try {
                dnsInfo = api.get("/control/dns_info");
                JSONArray list = dnsInfo.optJSONArray("upstream_dns");
                List<String> lines = jsonLines(list);
                mainHandler.post(() -> upstreams.setText(lines.isEmpty() ? "未配置上游 DNS" : join(lines, "\n")));
            } catch (Exception exception) {
                mainHandler.post(() -> upstreams.setText(friendlyError(exception)));
            }
        });
    }

    private void showUpstreamDialog() {
        if (dnsInfo == null) {
            toast("DNS 设置仍在读取，请稍后重试");
            return;
        }
        EditText input = dialogInput(join(jsonLines(dnsInfo.optJSONArray("upstream_dns")), "\n"), "每行一个 DNS 地址");
        new AlertDialog.Builder(this).setTitle("编辑上游 DNS").setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> {
                    try {
                        JSONArray upstreams = linesArray(input.getText().toString());
                        JSONObject updated = new JSONObject(dnsInfo.toString());
                        updated.put("upstream_dns", upstreams);
                        apiAction("/control/dns_config", updated, "上游 DNS 已保存", () -> showPageAgain(PAGE_SETTINGS));
                    } catch (Exception exception) {
                        toast(friendlyError(exception));
                    }
                }).show();
    }

    private void showUserRulesDialog() {
        EditText input = dialogInput(join(jsonLines(userRules), "\n"), "每行一条 AdGuard 规则");
        input.setMinLines(12);
        new AlertDialog.Builder(this).setTitle("编辑用户规则").setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> {
                    try {
                        JSONArray rules = linesArray(input.getText().toString());
                        apiAction("/control/filtering/set_rules", new JSONObject().put("rules", rules), "用户规则已保存", () -> showPageAgain(PAGE_FILTERS));
                    } catch (Exception exception) {
                        toast(friendlyError(exception));
                    }
                }).show();
    }

    private void showAddFilterDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(22), 0, dp(22), 0);
        EditText name = dialogInput("", "订阅名称");
        EditText url = dialogInput("", "过滤规则 URL");
        form.addView(name);
        form.addView(url, matchWrapMargins(0, 8, 0, 0));
        new AlertDialog.Builder(this).setTitle("添加过滤订阅").setView(form)
                .setNegativeButton("取消", null)
                .setPositiveButton("添加", (dialog, which) -> {
                    String filterUrl = url.getText().toString().trim();
                    String filterName = name.getText().toString().trim();
                    if (filterUrl.isEmpty()) {
                        toast("请输入过滤规则 URL");
                        return;
                    }
                    apiAction("/control/filtering/add_url", json("name", filterName.isEmpty() ? filterUrl : filterName, "url", filterUrl, "whitelist", false), "过滤订阅已添加", () -> showPageAgain(PAGE_FILTERS));
                }).show();
    }

    private void setProtection(boolean enabled, long duration) {
        JSONObject body = new JSONObject();
        try {
            body.put("enabled", enabled).put("duration", duration);
        } catch (Exception ignored) {
        }
        apiAction("/control/protection", body, enabled ? "广告过滤已恢复" : "广告过滤已按时暂停", () -> {
            protectionEnabled = enabled;
            protectionPauseUntilMs = !enabled && duration > 0
                    ? System.currentTimeMillis() + duration : 0L;
            persistPauseUntil();
            if (protectionPauseUntilMs > 0L) {
                PauseCountdownService.start(this, protectionPauseUntilMs);
            } else {
                PauseCountdownService.cancel(this);
            }
            apiConnected = true;
            updateProtectionControls(true);
            scheduleOverviewRefresh(0);
        });
    }

    private void updateProtectionControls(boolean connected) {
        if (protectionSwitch == null || protectionSwitchState == null) return;
        updatingProtectionSwitch = true;
        protectionSwitch.setEnabled(connected);
        protectionSwitch.setChecked(connected && protectionEnabled);
        updatingProtectionSwitch = false;
        protectionSwitchState.setText(!connected ? "服务连接后可用"
                : protectionEnabled ? "保护已开启" : "保护已暂停");
        refreshProtectionCountdown();
    }

    private void refreshProtectionCountdown() {
        mainHandler.removeCallbacks(protectionCountdown);
        if (protectionSwitchState == null || !apiConnected || protectionEnabled) return;
        long remaining = protectionPauseUntilMs - System.currentTimeMillis();
        if (remaining <= 0) {
            if (protectionPauseUntilMs != 0L) {
                protectionPauseUntilMs = 0L;
                persistPauseUntil();
                scheduleOverviewRefresh(0);
            }
            protectionSwitchState.setText("\u4FDD\u62A4\u5DF2\u6682\u505C");
            return;
        }
        String countdown = formatPauseRemaining(remaining);
        protectionSwitchState.setText("\u4FDD\u62A4\u5DF2\u6682\u505C \u00B7 \u5269\u4F59 " + countdown);
        if (overviewStatusDetail != null && activePage == PAGE_OVERVIEW) {
            overviewStatusDetail.setText("\u6682\u505C\u5269\u4F59 " + countdown + "，\u5230\u65F6\u5C06\u81EA\u52A8\u6062\u590D");
        }
        if (appVisible && activePage == PAGE_OVERVIEW) {
            mainHandler.postDelayed(protectionCountdown, 1000L);
        }
    }

    private String formatPauseRemaining(long remainingMs) {
        long totalSeconds = Math.max(1L, (remainingMs + 999L) / 1000L);
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0) return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds);
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    private void persistPauseUntil() {
        getPreferences(MODE_PRIVATE).edit().putLong(PREF_PAUSE_UNTIL, protectionPauseUntilMs).apply();
    }

    private void serviceAction(String action, String message) {
        rootAction(CONTROL + " " + action, message, () -> showPageAgain(PAGE_OVERVIEW));
    }

    private void apiAction(String path, JSONObject body, String success, Runnable completed) {
        if (!showLoading("正在应用更改，请稍候")) return;
        executor.execute(() -> {
            try {
                api.post(path, body);
                mainHandler.post(() -> {
                    hideLoading();
                    toast(success);
                    if (completed != null) completed.run();
                });
            } catch (Exception exception) {
                mainHandler.post(() -> {
                    hideLoading();
                    toast(friendlyError(exception));
                });
            }
        });
    }

    private void rootAction(String command, String success, Runnable completed) {
        if (moduleOperationRunning) {
            toast("操作正在执行，请稍候");
            return;
        }
        moduleOperationRunning = true;
        toast("操作已提交");
        executor.execute(() -> {
            ShellResult result = rootCommand(command);
            mainHandler.post(() -> {
                moduleOperationRunning = false;
                if (result.exitCode == 0) {
                    toast(success);
                    if (completed != null) completed.run();
                } else {
                    toast(rootFailureMessage(result));
                }
            });
        });
    }

    private ShellResult rootCommand(String command) {
        String action = moduleAction(command);
        if (action != null) {
            try {
                ModuleControlClient.Result result = moduleControl.execute(action);
                return new ShellResult(result.code, result.output);
            } catch (IOException ignored) {
                // Older module builds do not include the Root control bridge.
            }
        }

        if (legacyRootTimedOut) {
            return new ShellResult(124, "旧模块 Root 兼容通道已超时熔断");
        }
        Future<ShellResult> request = rootExecutor.submit(() -> legacyRootCommand(command));
        try {
            return request.get(10, TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            legacyRootTimedOut = true;
            request.cancel(true);
            return new ShellResult(124, "旧模块 Root 操作超过 10 秒，已终止等待");
        } catch (InterruptedException exception) {
            request.cancel(true);
            Thread.currentThread().interrupt();
            return new ShellResult(130, "Root 操作已取消");
        } catch (ExecutionException exception) {
            return new ShellResult(126, "旧模块 Root 兼容通道执行失败");
        }
    }

    private ShellResult legacyRootCommand(String command) {
        Shell shell = acquireRootShell();
        if (shell == null) {
            return new ShellResult(126, "模块控制桥不可用，且 KernelSU/SukiSU 未向本应用提供 Root Shell");
        }

        Shell.Result result = shell.newJob().add(command).exec();
        if (result.getCode() == Shell.Result.JOB_NOT_EXECUTED) {
            closeRootShell(shell);
            shell = acquireRootShell();
            if (shell == null) return new ShellResult(126, "Root Shell 已失效且无法重建");
            result = shell.newJob().add(command).exec();
        }
        return new ShellResult(result.getCode(), shellOutput(result));
    }

    private Shell acquireRootShell() {
        if (legacyRootTimedOut) return null;
        Shell cached = Shell.getCachedShell();
        if (cached != null && cached.isAlive() && cached.isRoot()) return cached;
        if (cached != null) closeRootShell(cached);

        if (Boolean.FALSE.equals(Shell.isAppGrantedRoot())) return null;
        try {
            Shell shell = Shell.getShell();
            if (shell.isAlive() && shell.isRoot()) return shell;
            closeRootShell(shell);
        } catch (Exception ignored) {
            Shell failed = Shell.getCachedShell();
            if (failed != null) closeRootShell(failed);
        }
        return null;
    }

    private String moduleAction(String command) {
        String prefix = CONTROL + " ";
        if (!command.startsWith(prefix)) return null;
        String action = command.substring(prefix.length()).trim();
        if (action.equals("start") || action.equals("stop") || action.equals("restart")
                || action.equals("status") || action.equals("rules") || action.equals("recover")
                || action.equals("proxy-enable") || action.equals("proxy-disable")
                || action.equals("proxy-status") || action.equals("proxy-sync")) {
            return action;
        }
        return null;
    }

    private String rootFailureMessage(ShellResult result) {
        String output = result.output == null ? "" : result.output.trim();
        if (result.exitCode == 126) {
            return "请更新并重启 AdGuard Home 模块；新版控制桥无需单独授予 App Root 权限";
        }
        if (result.exitCode == 124) {
            return "旧模块 Root 操作已超时；请更新并重启模块以永久绕过 KSU 授权问题";
        }
        if (output.equals("restore-failed")) return "系统 DNS 恢复失败，为避免断网已保持服务运行";
        if (output.contains("no-supported-config")) return "未检测到受支持的 Clash/Mihomo 配置，兼容未启用";
        if (output.length() > 180) output = output.substring(0, 180) + "...";
        return output.isEmpty() ? "模块控制操作失败（状态码 " + result.exitCode + "）" : output;
    }

    private void closeRootShell(Shell shell) {
        try {
            shell.close();
        } catch (IOException ignored) {
        }
    }

    private String shellOutput(Shell.Result result) {
        StringBuilder output = new StringBuilder();
        for (String line : result.getOut()) output.append(line).append('\n');
        for (String line : result.getErr()) output.append(line).append('\n');
        return output.toString();
    }

    private boolean showLoading(String message) {
        if (isFinishing() || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed())) return false;
        if (loadingDialog != null && loadingDialog.isShowing()) return false;

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setGravity(Gravity.CENTER_VERTICAL);
        content.setPadding(dp(24), dp(20), dp(24), dp(20));

        ProgressBar progress = new ProgressBar(this);
        content.addView(progress, new LinearLayout.LayoutParams(dp(32), dp(32)));
        TextView label = text(message, 14, TEXT, false);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        labelParams.setMargins(dp(16), 0, 0, 0);
        content.addView(label, labelParams);

        loadingDialog = new AlertDialog.Builder(this).setView(content).create();
        loadingDialog.setCancelable(false);
        loadingDialog.setCanceledOnTouchOutside(false);
        loadingDialog.show();
        return true;
    }

    private void hideLoading() {
        if (loadingDialog == null) return;
        AlertDialog dialog = loadingDialog;
        loadingDialog = null;
        if (dialog.isShowing()) dialog.dismiss();
    }

    private void showPageAgain(int page) {
        activePage = -1;
        showPage(page);
    }

    private void setHeaderState(String label, int color, boolean animate) {
        headerState.setText(label);
        headerState.setTextColor(color);
        headerState.setBackground(rounded(withAlpha(color, 28), 16));
        if (headerPulse != null) headerPulse.cancel();
        headerState.setAlpha(1f);
        if (animate) {
            headerPulse = ObjectAnimator.ofFloat(headerState, View.ALPHA, 0.48f, 1f);
            headerPulse.setDuration(850);
            headerPulse.setRepeatCount(ObjectAnimator.INFINITE);
            headerPulse.setRepeatMode(ObjectAnimator.REVERSE);
            headerPulse.start();
        }
    }

    private LinearLayout pageColumn() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(18), dp(8), dp(18), dp(22));
        return column;
    }

    private ScrollView scroll(View content) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        scroll.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(15), dp(16), dp(15));
        int radius = "miuix".equals(themeMode) ? 16 : "material".equals(themeMode) ? 12 : 8;
        card.setBackground(rounded(themeSurface, radius));
        card.setElevation(dp("material".equals(themeMode) ? 2 : 1));
        return card;
    }

    private GradientDrawable glassBackground() {
        GradientDrawable drawable = rounded(withAlpha(themeSurface, darkMode ? 205 : 190), 22);
        drawable.setStroke(dp(1), withAlpha(darkMode ? Color.WHITE : accentColor, darkMode ? 42 : 58));
        return drawable;
    }

    private LinearLayout headingWithAction(String title, String action, Runnable listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(text(title, 24, TEXT, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(smallButton(action, BLUE, listener), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));
        return row;
    }

    private TextView sectionTitle(String value) {
        return text(value, 16, themeText, true);
    }

    private LinearLayout metric(String value, String label) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.addView(text(value, 14, TEXT, true));
        block.addView(text(label, 11, MUTED, false), matchWrapMargins(0, 4, 0, 0));
        return block;
    }

    private TextView metricValue(String value, String label) {
        TextView view = text(value, 19, TEXT, true);
        view.setTag(label);
        return view;
    }

    private LinearLayout statBlock(TextView value) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setGravity(Gravity.CENTER);
        block.setBackground(rounded(themeSurface, 8));
        block.addView(value);
        block.addView(text(String.valueOf(value.getTag()), 11, MUTED, false), matchWrapMargins(0, 5, 0, 0));
        return block;
    }

    private Button actionButton(String label, int background, int foreground, Runnable listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(13);
        button.setTextColor(foreground);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(14), 0, dp(14), 0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setBackground(ripple(background, foreground, 7));
        button.setOnClickListener(view -> listener.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        params.setMargins(0, 0, dp(8), 0);
        button.setLayoutParams(params);
        return button;
    }

    private Button smallButton(String label, int color, Runnable listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(12);
        button.setTextColor(color);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setPadding(dp(13), 0, dp(13), 0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setBackground(ripple(withAlpha(color, 22), color, 7));
        button.setOnClickListener(view -> listener.run());
        return button;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color == TEXT ? themeText : color == MUTED ? themeMuted : color);
        view.setLetterSpacing(0f);
        view.setTypeface(Typeface.create("sans", bold ? Typeface.BOLD : Typeface.NORMAL));
        return view;
    }

    private View emptyState(String message) {
        TextView view = text(message, 14, themeMuted, false);
        view.setGravity(Gravity.CENTER);
        view.setBackground(rounded(themeSurface, 8));
        view.setPadding(dp(16), dp(32), dp(16), dp(32));
        return view;
    }

    private EditText dialogInput(String value, String hint) {
        EditText input = new EditText(this);
        input.setText(value);
        input.setHint(hint);
        input.setTextSize(14);
        input.setTextColor(themeText);
        input.setHintTextColor(Color.rgb(130, 136, 146));
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setPadding(dp(12), dp(11), dp(12), dp(11));
        input.setBackground(rounded(Color.rgb(242, 244, 248), 7));
        return input;
    }

    private void confirm(String title, String message, Runnable confirmed) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(message).setNegativeButton("取消", null).setPositiveButton("确定", (dialog, which) -> confirmed.run()).show();
    }

    private void showLoadError(LinearLayout list, Exception exception) {
        mainHandler.post(() -> {
            list.removeAllViews();
            list.addView(emptyState(friendlyError(exception)));
        });
    }

    private JSONObject json(Object... values) {
        JSONObject object = new JSONObject();
        try {
            for (int i = 0; i + 1 < values.length; i += 2) object.put(String.valueOf(values[i]), values[i + 1]);
        } catch (Exception ignored) {
        }
        return object;
    }

    private JSONArray linesArray(String value) {
        JSONArray array = new JSONArray();
        for (String line : value.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) array.put(trimmed);
        }
        return array;
    }

    private List<String> jsonLines(JSONArray array) {
        List<String> result = new ArrayList<>();
        if (array == null) return result;
        for (int i = 0; i < array.length(); i++) {
            String value = array.optString(i, "").trim();
            if (!value.isEmpty()) result.add(value);
        }
        return result;
    }

    private String join(List<String> values, String separator) {
        StringBuilder output = new StringBuilder();
        for (String value : values) {
            if (output.length() > 0) output.append(separator);
            output.append(value);
        }
        return output.toString();
    }

    private String formatNumber(long value) {
        if (value >= 1000000) return String.format(Locale.US, "%.1fM", value / 1000000.0);
        if (value >= 1000) return String.format(Locale.US, "%.1fK", value / 1000.0);
        return String.valueOf(value);
    }

    private String friendlyError(Throwable error) {
        String value = error.getMessage();
        if (value == null || value.trim().isEmpty()) value = error.getClass().getSimpleName();
        if (value.contains("Connection refused") || value.contains("ECONNREFUSED") || value.contains("Failed to connect")) return "无法连接 127.0.0.1:3000，请检查模块日志 /data/adb/agh/logs/AdGuardHome.log";
        return value;
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private RippleDrawable ripple(int color, int rippleColor, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(withAlpha(rippleColor, 44)), rounded(color, radius), rounded(Color.WHITE, radius));
    }

    private int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private Space space(int height) {
        Space view = new Space(this);
        view.setLayoutParams(new LinearLayout.LayoutParams(1, dp(height)));
        return view;
    }

    private LinearLayout.LayoutParams matchWrapMargins(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private LinearLayout.LayoutParams marginWeight(float weight, int left) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), weight);
        params.setMargins(dp(left), 0, 0, 0);
        return params;
    }

    private LinearLayout.LayoutParams gridCell(float weight, int left, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(50), weight);
        params.setMargins(dp(left), dp(top), 0, 0);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class ShellResult {
        final int exitCode;
        final String output;

        ShellResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }
}
