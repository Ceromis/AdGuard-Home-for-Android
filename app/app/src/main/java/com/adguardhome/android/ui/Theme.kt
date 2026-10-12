package com.adguardhome.android.ui

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowInsetsControllerCompat
import com.adguardhome.android.ManagerApplication
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

enum class UiStyle { MIUIX, DEFAULT, MATERIAL }

data class ThemeSettings(
    val style: UiStyle = UiStyle.MIUIX,
    val dark: Boolean = false,
    val monet: Boolean = false,
    val floatingBar: Boolean = false,
    val blurBars: Boolean = false,
    val predictiveBack: Boolean = false,
    val accent: Int = 0xFF0067C0.toInt(),
)

val LocalThemeSettings = compositionLocalOf { mutableStateOf(ThemeSettings()) }

@Composable
fun ManagerTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val settings = remember { mutableStateOf(readThemeSettings(context, systemDark)) }
    val value = settings.value
    val colors = managerColorScheme(value, value.dark)


    LaunchedEffect(value.dark) {
        val window = (context as? Activity)?.window ?: return@LaunchedEffect
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !value.dark
            isAppearanceLightNavigationBars = !value.dark
        }
    }

    val themedContent: @Composable () -> Unit = {
        MaterialTheme(colorScheme = colors) {
            // Miuix 容器只提供它自己的 LocalContentColor,Material 文本拿不到,
            // 会退回默认黑色;这里在根节点补上跟随主题的内容色。
            CompositionLocalProvider(
                LocalContentColor provides colors.onBackground,
                LocalThemeSettings provides settings,
                content = content,
            )
        }
    }
    // Keep one stable theme node around the app. Replacing the root wrapper when
    // switching style disposes the pager and makes the manager appear to restart.
    MiuixTheme(
        controller = ThemeController(
            colorSchemeMode = if (value.dark) ColorSchemeMode.Dark else ColorSchemeMode.Light,
            keyColor = colors.primary,
            isDark = value.dark,
        ),
        content = themedContent,
    )
}


fun readThemeSettings(context: Context, systemDark: Boolean = isNightMode(context)): ThemeSettings {
    val prefs = context.getSharedPreferences("theme_config", 0)
    return ThemeSettings(
        style = when (prefs.getString("mode", "miuix")) {
            "default" -> UiStyle.DEFAULT
            "material" -> UiStyle.MATERIAL
            else -> UiStyle.MIUIX
        },
        dark = prefs.getBoolean("dark", systemDark),
        monet = prefs.getBoolean("monet", false),
        floatingBar = prefs.getBoolean("floating", false),
        blurBars = prefs.getBoolean("blur", false),
        predictiveBack = prefs.getBoolean("gesture", false),
        accent = prefs.getInt("accent", 0xFF0067C0.toInt()),
    )
}

@Composable
fun managerColorScheme(settings: ThemeSettings, dark: Boolean): androidx.compose.material3.ColorScheme {
    val context = LocalContext.current
    val accent = Color(settings.accent)
    val accentOn = if (accent.luminance() > 0.48f) Color(0xFF111111) else Color.White
    val accentContainer = if (dark) {
        Color(
            red = accent.red * 0.38f + 0.08f,
            green = accent.green * 0.38f + 0.08f,
            blue = accent.blue * 0.38f + 0.08f,
        )
    } else {
        Color(
            red = accent.red * 0.18f + 0.82f,
            green = accent.green * 0.18f + 0.82f,
            blue = accent.blue * 0.18f + 0.82f,
        )
    }
    val fixedLight = lightColorScheme(
        primary = accent,
        onPrimary = accentOn,
        primaryContainer = accentContainer,
        onPrimaryContainer = if (accentContainer.luminance() > 0.48f) Color(0xFF111111) else Color.White,
        secondary = Color(0xFF006C4C),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFD8E7DF),
        onSecondaryContainer = Color(0xFF10211A),
        tertiary = Color(0xFF565E71),
        tertiaryContainer = Color(0xFFE1E2E8),
        background = Color(0xFFF6F8FC),
        onBackground = Color(0xFF191C20),
        surface = Color.White,
        onSurface = Color(0xFF191C20),
        surfaceVariant = Color(0xFFE1E3E8),
        onSurfaceVariant = Color(0xFF44474E),
        surfaceTint = Color.Transparent,
        outline = Color(0xFF74777F),
        surfaceContainer = Color.White,
        surfaceContainerLow = Color.White,
        surfaceContainerLowest = Color.White,
        surfaceContainerHigh = Color(0xFFF0F2F6),
        surfaceContainerHighest = Color(0xFFE8EBF0),
    )
    val fixedDark = darkColorScheme(
        primary = accent,
        onPrimary = accentOn,
        primaryContainer = accentContainer,
        onPrimaryContainer = if (accentContainer.luminance() > 0.48f) Color(0xFF111111) else Color.White,
        secondary = Color(0xFF6FDBAF),
        secondaryContainer = Color(0xFF253B33),
        background = Color(0xFF16181C),
        surface = Color(0xFF202329),
        surfaceVariant = Color(0xFF44474E),
        surfaceTint = Color.Transparent,
        surfaceContainer = Color(0xFF202329),
        surfaceContainerLow = Color(0xFF1B1E23),
        surfaceContainerLowest = Color(0xFF121418),
        surfaceContainerHigh = Color(0xFF292D34),
        surfaceContainerHighest = Color(0xFF33373E),
    )
    return if (settings.monet && Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) fixedDark else fixedLight
}

private fun isNightMode(context: Context): Boolean =
    (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

fun saveTheme(context: android.content.Context, value: ThemeSettings) {
    context.getSharedPreferences("theme_config", 0).edit()
        .putString("mode", value.style.name.lowercase())
        .putBoolean("dark", value.dark)
        .putBoolean("monet", value.monet)
        .putBoolean("floating", value.floatingBar)
        .putBoolean("blur", value.blurBars)
        .putBoolean("gesture", value.predictiveBack)
        .putInt("accent", value.accent)
        .apply()
    ManagerApplication.setPredictiveBack(context.applicationInfo, value.predictiveBack)
}
