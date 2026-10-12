#!/system/bin/sh
# ==============================================================================
# AdGuard Home for Android - 模块安装脚本 (customize.sh)
# ==============================================================================
# 功能：Magisk/KernelSU 模块安装时执行，负责：
#   1. 验证设备架构（仅支持 arm64-v8a）
#   2. 备份系统 IFW（Intent Firewall）状态
#   3. 备份出厂用户规则
#   4. 备份私有 DNS 设置
#   5. 安装配套的 Android 管理器 App
#
# 执行时机：模块首次安装或升级时
# 执行环境：Magisk/KernelSU 的模块安装框架
# ==============================================================================

# ------------------------------------------------------------------------------
# 安装信息提示
# 在模块管理器界面显示安装进度和基本信息
# ------------------------------------------------------------------------------
ui_print "- Installing bundled ARM64 AdGuard Home"
ui_print "- Native Android app provides the management panel"
ui_print "- Local API default: http://127.0.0.1:3000 (configurable in the app)"
ui_print "- Default credentials: root / root"

# ==============================================================================
# 路径配置
# ==============================================================================
DATA_DIR=/data/adb/agh                                        # 数据持久化目录
IFW_DIR=/data/system/ifw                                      # Android Intent Firewall 目录
IFW_BACKUP=$DATA_DIR/ifw-backup                               # IFW 备份目录
FACTORY_RULES_BACKUP=$DATA_DIR/factory-user-rules.txt          # 出厂用户规则备份

# 初始化日志目录
mkdir -p "$DATA_DIR/logs"
echo "$(date '+%Y-%m-%d %H:%M:%S') module installation started" >> "$DATA_DIR/logs/install.log"

# ==============================================================================
# IFW 备份
# ==============================================================================
# IFW (Intent Firewall) 是 Android 的应用启动防火墙
# 某些 IFW 规则会阻止 AdGuard Home 的正常运行
# 因此在安装时备份原始状态，卸载时恢复
#
# 备份策略：
#   - 首次安装：完整备份 IFW 目录
#   - 升级安装：保留首次备份，不覆盖
#   - 记录 IFW 目录是否存在（present/absent）
# ==============================================================================
if [ ! -f "$IFW_BACKUP/created" ]; then
  rm -rf "$IFW_BACKUP.tmp"
  mkdir -p "$IFW_BACKUP.tmp/files" || abort "Unable to prepare IFW backup."

  if [ -d "$IFW_DIR" ]; then
    # IFW 目录存在，备份所有文件
    echo present > "$IFW_BACKUP.tmp/state"
    cp -a "$IFW_DIR/." "$IFW_BACKUP.tmp/files/" 2>> "$DATA_DIR/logs/install.log" || abort "Unable to back up /data/system/ifw."
  else
    # IFW 目录不存在，记录状态
    echo absent > "$IFW_BACKUP.tmp/state"
  fi

  date '+%Y-%m-%d %H:%M:%S' > "$IFW_BACKUP.tmp/created"
  chmod 700 "$IFW_BACKUP.tmp" "$IFW_BACKUP.tmp/files"
  mv "$IFW_BACKUP.tmp" "$IFW_BACKUP" || abort "Unable to save IFW backup."
  echo "$(date '+%Y-%m-%d %H:%M:%S') IFW state backed up" >> "$DATA_DIR/logs/install.log"
fi

# ==============================================================================
# 出厂用户规则备份
# ==============================================================================
# 从默认配置文件中提取用户规则，保存为出厂规则备份
# 用于管理器 App 的"恢复出厂规则"功能
# ==============================================================================
# 出厂规则备份与运行配置替换统一由安装末尾的 "aghctl rules-sync" 完成。


# ==============================================================================
# 私有 DNS 设置备份
# ==============================================================================
# 备份用户当前的私有 DNS 配置（模式和主机名）
# 卸载时恢复，避免模块安装影响用户的 DNS 隐私设置
# 升级时保留首次备份
# ==============================================================================
# 私有 DNS 备份并入统一状态文件 adgyaml。
AGH_STATE_LIB="$MODPATH/system/bin/aghstate"
if [ -r "$AGH_STATE_LIB" ]; then
  . "$AGH_STATE_LIB"
  if [ "$(state_get private_dns_saved)" != "1" ]; then
    state_set private_dns_mode "$(settings get global private_dns_mode 2>/dev/null)" || abort "Unable to back up private DNS settings."
    state_set private_dns_specifier "$(settings get global private_dns_specifier 2>/dev/null)" || abort "Unable to back up private DNS settings."
    state_set private_dns_saved 1 || abort "Unable to back up private DNS settings."
  fi
fi

# ==============================================================================
# 架构验证
# ==============================================================================
# 本模块仅包含 ARM64 二进制文件，不支持其他架构
# 在非 arm64-v8a 设备上安装会导致服务无法启动
# ==============================================================================
if [ "$(getprop ro.product.cpu.abi)" != "arm64-v8a" ]; then
  abort "This package contains an ARM64 binary and requires arm64-v8a."
fi

# 修复部分管理器解压后脚本丢失执行位的问题。
chmod 755 "$MODPATH"/*.sh "$MODPATH"/system/bin/* 2>/dev/null

# ==============================================================================
# 安装配套 Android App
# ==============================================================================
# 安装打包在模块中的管理器 APK
# 安装成功后删除 APK 以节省空间
# 安装失败则中止模块安装
# ==============================================================================
APP_APK="$MODPATH/system/etc/agh-dns-helper.apk"
if [ ! -s "$APP_APK" ]; then
  abort "Bundled Android app is missing from the module package."
fi

ui_print "- Installing bundled Android app"
install_output=$(/system/bin/pm install -r "$APP_APK" 2>&1)
case "$install_output" in
  *Success*)
    echo "$(date '+%Y-%m-%d %H:%M:%S') companion app installed" >> "$DATA_DIR/logs/install.log"
    ui_print "- Android app installed successfully"
    rm -f "$APP_APK"                                            # 安装成功，删除 APK
    ;;
  *INSTALL_FAILED_UPDATE_INCOMPATIBLE*)
    ui_print "- Existing app signature differs, replacing old companion app"
    echo "$(date '+%Y-%m-%d %H:%M:%S') removed incompatible app before signature migration" >> "$DATA_DIR/logs/install.log"
    /system/bin/pm uninstall --user 0 com.adguardhome.android.standard >/dev/null 2>&1
    /system/bin/pm uninstall com.adguardhome.android.standard >/dev/null 2>&1
    /system/bin/pm uninstall --user 0 com.adguardhome.android >/dev/null 2>&1
    /system/bin/pm uninstall com.adguardhome.android >/dev/null 2>&1
    install_output=$(/system/bin/pm install "$APP_APK" 2>&1)
    case "$install_output" in
      *Success*)
        echo "$(date '+%Y-%m-%d %H:%M:%S') companion app installed after signature migration" >> "$DATA_DIR/logs/install.log"
        ui_print "- Android app installed successfully"
        rm -f "$APP_APK"
        ;;
      *)
        echo "$(date '+%Y-%m-%d %H:%M:%S') companion app installation failed after uninstall: $install_output" >> "$DATA_DIR/logs/install.log"
        abort "Android app installation failed: $install_output"
        ;;
    esac
    ;;
  *)
    echo "$(date '+%Y-%m-%d %H:%M:%S') companion app installation failed: $install_output" >> "$DATA_DIR/logs/install.log"
    abort "Android app installation failed: $install_output"    # 安装失败，中止
    ;;
esac

# ==============================================================================
# 同步新版本规则到正在运行的配置(存在才执行,失败不阻断安装)
# ==============================================================================
if [ -f "$MODPATH/system/bin/aghctl" ]; then
  sh "$MODPATH/system/bin/aghctl" rules-sync >> "$DATA_DIR/logs/install.log" 2>&1 || \
    ui_print "- Rule sync skipped, see /data/adb/agh/logs/install.log"
fi
