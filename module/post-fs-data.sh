#!/system/bin/sh
# ==============================================================================
# AdGuard Home for Android - 开机早期权限设置脚本 (post-fs-data.sh)
# ==============================================================================
# 功能：在 Android 系统启动的 post-fs-data 阶段执行
#       此阶段在文件系统挂载后、zygote 启动前
#
# 主要职责：
#   1. 创建数据目录结构
#   2. 设置关键二进制文件的可执行权限
#
# 执行时机：每次设备启动时（早于 service.sh）
# 为什么需要：某些 ROM 或模块管理器可能在模块更新后重置文件权限
# ==============================================================================

MODDIR=${0%/*}                                                # 模块根目录
DATA_DIR=/data/adb/agh                                        # 数据目录

# 创建日志目录
mkdir -p "$DATA_DIR/logs"

# 设置所有二进制文件的可执行权限
# 确保 service.sh 启动时这些文件可以正常执行
chmod 755 "$MODDIR/system/bin/AdGuardHome" \
          "$MODDIR/system/bin/aghctl" \
          "$MODDIR/system/bin/aghbridge" \
          "$MODDIR/system/bin/aghproxy" \
          "$MODDIR/system/bin/aghappclean"

# 记录执行日志
echo "$(date '+%Y-%m-%d %H:%M:%S') post-fs-data permissions applied" >> "$DATA_DIR/logs/install.log"
