#!/system/bin/sh
# ==============================================================================
# AdGuard Home for Android - 模块卸载脚本 (uninstall.sh)
# ==============================================================================
# 功能：模块卸载时执行完整的清理和恢复，包括：
#   1. 停止代理兼容并恢复配置
#   2. 停止 AdGuard Home 服务
#   3. 清理 iptables 规则和 DNS 接管
#   4. 恢复私有 DNS 设置
#   5. 恢复 IFW (Intent Firewall) 状态
#   6. 终止所有相关进程
#   7. 卸载配套 Android App
#   8. 清理挂载点和模块目录
#
# 执行时机：模块被卸载时
# 恢复策略：如果恢复失败，保留数据目录用于诊断
# ==============================================================================

# ==============================================================================
# 路径配置
# ==============================================================================
MODDIR=${0%/*}                                                # 模块根目录
CTL=$MODDIR/system/bin/aghctl                                 # 控制脚本
APP_CLEANER=$MODDIR/system/bin/aghappclean                    # 系统策略清理器
DATA_DIR=/data/adb/agh                                        # 数据目录
LOG_DIR=$DATA_DIR/logs                                        # 日志目录
UNINSTALL_LOG=$LOG_DIR/uninstall.log                          # 卸载日志文件
IFW_DIR=/data/system/ifw                                      # IFW 目录
IFW_BACKUP=$DATA_DIR/ifw-backup                               # IFW 备份目录
restore_ok=1                                                  # 恢复状态标志

# 初始化日志
mkdir -p "$LOG_DIR"
log() { echo "$(date '+%Y-%m-%d %H:%M:%S') uninstall: $*" >> "$UNINSTALL_LOG"; }
log "started"

# ==============================================================================
# IFW 恢复函数
# ==============================================================================
# 根据安装时备份的状态恢复 IFW 目录：
#   - present: 从备份恢复所有 IFW 文件
#   - absent: 删除 IFW 目录（恢复为安装前状态）
# ==============================================================================
restore_ifw() {
  local state
  [ -f "$IFW_BACKUP/created" ] || { log "IFW backup missing"; return 1; }
  state=$(cat "$IFW_BACKUP/state" 2>/dev/null)
  case "$state" in
    present)
      # IFW 在安装前存在，从备份恢复
      mkdir -p "$IFW_DIR" || return 1
      rm -rf "$IFW_DIR"/* 2>/dev/null || return 1
      cp -a "$IFW_BACKUP/files/." "$IFW_DIR/" 2>> "$UNINSTALL_LOG" || return 1
      restorecon -RF "$IFW_DIR" >> "$UNINSTALL_LOG" 2>&1 || :   # 恢复 SELinux context
      ;;
    absent)
      # IFW 在安装前不存在，删除目录
      rm -rf "$IFW_DIR" 2>/dev/null || return 1
      ;;
    *) log "invalid IFW backup state"; return 1 ;;
  esac
  log "IFW state restored"
}

# ==============================================================================
# 步骤 1：停止服务和恢复配置
# ==============================================================================
# 按顺序执行：
#   1. 禁用代理兼容并恢复代理配置
#   2. 停止 AdGuard Home 服务（包括 DNS 恢复）
#   3. 清理 iptables 规则和 DNS 接管状态
# ==============================================================================
if [ -x "$CTL" ]; then
  "$CTL" proxy-disable >> "$UNINSTALL_LOG" 2>&1 || { log "proxy restore failed"; restore_ok=0; }
  "$CTL" stop >> "$UNINSTALL_LOG" 2>&1 || { log "service stop or DNS restore failed"; restore_ok=0; }
  "$CTL" cleanup >> "$UNINSTALL_LOG" 2>&1 || { log "firewall/resolver cleanup failed"; restore_ok=0; }
fi

# ==============================================================================
# 步骤 2：停止系统策略清理器和恢复 IFW
# ==============================================================================
[ -x "$APP_CLEANER" ] && "$APP_CLEANER" --stop >> "$UNINSTALL_LOG" 2>&1 || { log "private DNS restore failed"; restore_ok=0; }
restore_ifw || { log "IFW restore failed"; restore_ok=0; }

# ==============================================================================
# 步骤 3：终止所有相关进程
# ==============================================================================
# 先读统一运行时配置 adgyaml，再兜底旧版 PID 文件，向对应进程发送 SIGTERM，
# 并等待最多 5 秒确认退出，避免后续 rm -rf 删数据目录时仍有进程写入。
# ==============================================================================
terminate_pid() {
  local pid=$1
  case "$pid" in
    ''|*[!0-9]*) return 0 ;;
  esac
  kill "$pid" 2>/dev/null || :
  waited=0
  while [ "$waited" -lt 25 ]; do
    kill -0 "$pid" 2>/dev/null || return 0
    sleep 0.2
    waited=$((waited + 1))
  done
  if kill -0 "$pid" 2>/dev/null; then
    log "pid=$pid still alive after SIGTERM; sending SIGKILL"
    kill -9 "$pid" 2>/dev/null || :
  fi
}

for key in pid bridge_pid appclean_pid; do
  pid=$(sed -n "s|^$key: ||p" "$DATA_DIR/adgyaml" 2>/dev/null | sed -n '1p')
  terminate_pid "$pid"
done
for pid_file in "$DATA_DIR/agh.pid" "$DATA_DIR/aghbridge.pid" "$DATA_DIR/appclean.pid"; do
  [ -s "$pid_file" ] || continue
  terminate_pid "$(cat "$pid_file" 2>/dev/null)"
done

# ==============================================================================
# 步骤 4：卸载配套 Android App
# ==============================================================================
pm uninstall com.adguardhome.android >/dev/null 2>&1

# ==============================================================================
# 步骤 5：清理数据目录
# ==============================================================================
# 只有在所有恢复操作都成功时才删除数据目录
# 如果恢复失败，保留数据用于问题诊断
# ==============================================================================
if [ "$restore_ok" = 1 ]; then
  log "system state restored; removing module data"
  rm -rf "$DATA_DIR"
else
  log "restore incomplete; preserving $DATA_DIR for recovery and diagnostics"
fi

# ==============================================================================
# 步骤 6：清理挂载点和模块目录
# ==============================================================================
# 卸载可能存在的绑定挂载，然后删除模块目录
# umount 的 2>/dev/null 确保即使没有挂载也不会报错
# ==============================================================================
umount "$DATA_DIR" 2>/dev/null
umount "$MODDIR/system/bin" 2>/dev/null
log "mount cleanup attempted"

# 删除模块目录
rm -rf "$MODDIR" 2>/dev/null
log "module directory removed"
