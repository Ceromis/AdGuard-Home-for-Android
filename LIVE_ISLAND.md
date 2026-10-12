# 灵动岛（ColorOS / HyperOS 顶部胶囊）接入说明

本目录是在 AdGuard Home for Android 管理器基础上新增的 ColorOS / HyperOS（澎湃 OS）灵动岛适配。
实现不修改 Magisk/KernelSU 模块脚本，全部位于管理器 App 源码中，构建后随 APK 一起安装。

## 设计说明

- 纯系统级接入，不自绘悬浮窗：由常驻前台服务持续投递 promoted ongoing 通知，
  交给 ColorOS 16 / HyperOS 2.0 / Android 16 的 Live Updates（流体云）渲染顶部胶囊。
- 胶囊状态每秒刷新：查询数、拦截数、拦截率、暂停倒计时都会同步到系统 UI。
- 胶囊内提供快捷操作：暂停 5/30/60 分钟、立即恢复、重启/启动服务、打开管理器。
- 不请求 Root、不申请悬浮窗权限、不触碰 SystemUI。

## 新增文件

```text
app/app/src/main/java/com/adguardhome/android/liveisland/
├── LiveIslandSettings.kt         设置模型、运行快照与控制动作
├── LiveIslandService.kt          前台服务：维护 Live Updates 通知与每秒刷新
├── LiveIslandContent.kt          设置页内 Compose 胶囊示意预览
├── LiveIslandSettingsPage.kt     设置页
└── LiveIslandBootReceiver.kt     开机自启接收器
```

## 修改的原有文件

- `AndroidManifest.xml`：开机广播权限，前台服务与接收器声明。
- `ManagerActivity.kt`：启动时按设置恢复灵动岛服务。
- `ui/Theme.kt`：抽出 `readThemeSettings` 与 `managerColorScheme`，供预览复用主题。
- `ui/ManagerApp.kt`：设置页新增“灵动岛”入口与子页面。
- `module/customize.sh`：处理旧管理器签名不匹配，自动卸载后安装新 APK。

## 系统要求

- 原生胶囊需要 Android 16（API 36）及以上，对应 ColorOS 16 / HyperOS 2.0 的流体云。
- API 36 以下会退回普通常驻通知，不显示胶囊。
- 暂停保护时胶囊显示倒计时；“暂停时显示”模式在保护正常时不生成胶囊。

## 构建要求

与项目 README 一致：Windows PowerShell + JDK 17 + Android SDK + NDK。构建完成后，
`app-debug.apk` 会按原 `build.ps1` 流程写入 Magisk 模块 ZIP。

```powershell
$env:ANDROID_HOME = "你的 Android SDK 路径"
.\build.ps1
```

## 使用

1. 打开管理器 → 设置 → 灵动岛。
2. 开启“启用灵动岛”，可选“始终显示 / 暂停时显示”“开机后自动启动”。
3. 展开胶囊可暂停/恢复保护、快速暂停 5/30/60 分钟、启停服务。

## 首次从旧版升级

旧版管理器 APK 与应用商店/模块内签到版本不同签名时，`pm install -r` 会返回
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`。模块安装脚本已处理该情况：检测到签名不匹配时，
先卸载旧管理器再安装新 APK。旧的模块数据保留在 `/data/adb/agh`，不受影响；管理器本地
设置（主题、灵动岛开关等）会被重置。

## 注意事项

- 胶囊每秒读取本地 `127.0.0.1:3000/3001`，模块未运行时不显示运行数据。
- 当前为 Beta 分支功能，可能存在不稳定因素或兼容性问题，请谨慎使用。
- 部分 ColorOS / HyperOS 版本需在系统设置中允许该应用“后台弹出界面”之外的常驻通知权限；
  关闭通知渠道或清进程后胶囊会消失，需要常驻时开启“开机后自动启动”。