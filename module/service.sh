#!/system/bin/sh
# ==============================================================================
# AdGuard Home for Android - 服务监督脚本
# ==============================================================================
# 功能：作为 Magisk/KernelSU 模块的常驻服务监督器，负责：
#   1. 启动和监控 AdGuard Home 核心进程
#   2. 启动和管理原生控制桥 (aghbridge)
#   3. 周期性同步网络状态和 DNS 接管
#   4. 日志维护和清理
#   5. 代理状态监控
#   6. 进程崩溃后的自动恢复
# ==============================================================================

# ------------------------------------------------------------------------------
# 基础路径配置
# ------------------------------------------------------------------------------
MODDIR=${0%/*}                                    # 模块根目录（脚本所在目录的父目录）
DATA_DIR=/data/adb/agh                           # 数据持久化目录，存放配置、日志、状态文件
CONFIG=$DATA_DIR/AdGuardHome.yaml                 # AdGuard Home 主配置文件
AGH_BIN=$MODDIR/system/bin/AdGuardHome            # AdGuard Home 二进制文件路径
CTL=$MODDIR/system/bin/aghctl                     # 控制脚本路径，用于管理服务生命周期
BRIDGE=$MODDIR/system/bin/aghbridge               # 原生控制桥二进制，监听本地端口接受控制命令
PROXY_CTL=$MODDIR/system/bin/aghproxy             # 代理兼容控制脚本

# ------------------------------------------------------------------------------
# 进程 ID 文件和状态文件
# ------------------------------------------------------------------------------
STATE_FILE=$DATA_DIR/adgyaml                          # 唯一运行时状态文件（adgyaml）
AGH_STATE_LIB=$MODDIR/system/bin/aghstate             # 共享状态读写实现
LOG_MAX_BYTES=${AGH_LOG_MAX_BYTES:-524288}        # 单个日志文件最大大小（默认 512KB）
LOG_KEEP_BYTES=${AGH_LOG_KEEP_BYTES:-262144}      # 日志裁剪后保留大小（默认 256KB）
LOG_MAINT_PID=                                    # 日志维护子进程 PID
PROXY_WATCH_PID=                                  # 代理监控子进程 PID

# ------------------------------------------------------------------------------
# 跨平台兼容函数
# 某些 Android 系统使用 toybox 替代 busybox，需要检测并使用正确的命令
# ------------------------------------------------------------------------------
portable_date() {
  if [ -x /system/bin/toybox ]; then
    /system/bin/toybox date "$@"
  else
    date "$@"
  fi
}

portable_sleep() {
  if [ -x /system/bin/toybox ]; then
    /system/bin/toybox sleep "$@"
  else
    sleep "$@"
  fi
}

# ------------------------------------------------------------------------------
# 统一运行时状态 (adgyaml)
# 读写实现集中在 system/bin/aghstate,旧版散落的小文件在首次运行时自动并入。
# ------------------------------------------------------------------------------
. "$AGH_STATE_LIB" 2>/dev/null || :
state_init >/dev/null 2>&1 || :
# 清理历史遗留的状态写入临时文件(正常情况下不存在)。
rm -f "$STATE_FILE".set.* 2>/dev/null || :

# ------------------------------------------------------------------------------
# 初始化目录结构和权限
# 创建必要的数据目录，并设置严格的安全权限（仅 root 可访问）
# ------------------------------------------------------------------------------
mkdir -p "$DATA_DIR" "$DATA_DIR/work" "$DATA_DIR/work/data/filters" "$DATA_DIR/logs"
chmod 700 "$DATA_DIR" "$DATA_DIR/work" "$DATA_DIR/work/data" "$DATA_DIR/work/data/filters"
chmod 755 "$AGH_BIN" "$CTL" "$BRIDGE" "$PROXY_CTL" "$MODDIR/system/bin/aghappclean"

# ==============================================================================
# 日志管理函数
# ==============================================================================

# ------------------------------------------------------------------------------
# trim_log_file - 裁剪单个日志文件
# 参数：$1 = 日志文件路径
# 功能：当日志文件超过 LOG_MAX_BYTES 时，只保留末尾 LOG_KEEP_BYTES 的内容
#       防止日志文件无限增长占用存储空间
# ------------------------------------------------------------------------------
trim_log_file() {
  local file=$1 size temp
  [ -f "$file" ] || return 0                                    # 文件不存在则跳过
  size=$(wc -c < "$file" 2>/dev/null) || return 0               # 获取文件大小
  case "$size" in
    ''|*[!0-9]*) return 0 ;;                                    # 大小无效则跳过
  esac
  [ "$size" -le "$LOG_MAX_BYTES" ] && return 0                  # 未超过限制则跳过
  temp=$file.trim.$$
  if tail -c "$LOG_KEEP_BYTES" "$file" > "$temp" 2>/dev/null; then
    # 使用 mv -f 做原子替换，避免 cat 写到一半被 SIGTERM / 系统重启打断，
    # 否则日志会从关键截断点开始丢失,而那通常正是事后诊断需要的现场。
    mv -f "$temp" "$file"
  else
    rm -f "$temp"
  fi
}

# ------------------------------------------------------------------------------
# trim_logs - 裁剪所有日志文件
# 功能：遍历日志目录，对每个 .log 文件执行裁剪
# ------------------------------------------------------------------------------
trim_logs() {
  local file
  for file in "$DATA_DIR"/logs/*.log; do
    trim_log_file "$file"
  done
}

# ------------------------------------------------------------------------------
# seconds_until_midnight - 计算距离午夜的秒数
# 返回：距离下一个午夜的秒数，用于定时执行日志维护
# ------------------------------------------------------------------------------
seconds_until_midnight() {
  local now hour minute second
  now=$(portable_date '+%H %M %S') || return 1
  set -- $now
  hour=${1#0}                                                   # 去除前导零
  minute=${2#0}
  second=${3#0}
  [ -n "$hour" ] || hour=0
  [ -n "$minute" ] || minute=0
  [ -n "$second" ] || second=0
  echo $((86400 - hour * 3600 - minute * 60 - second))
}

# ------------------------------------------------------------------------------
# maintain_logs - 日志维护主循环（子进程运行）
# 功能：在午夜时分执行日志裁剪，防止日志文件过大
#       通过后台运行，避免阻塞主监督循环
# ------------------------------------------------------------------------------
maintain_logs() {
  local delay sleeper
  trap '[ -n "$sleeper" ] && kill "$sleeper" 2>/dev/null; exit 0' HUP INT TERM
  while :; do
    delay=$(seconds_until_midnight) || delay=86400               # 计算等待时间
    portable_sleep "$delay" &                                    # 后台休眠等待午夜
    sleeper=$!
    wait "$sleeper" || exit 0                                    # 等待休眠结束或被中断
    sleeper=
    trim_logs                                                    # 执行日志裁剪
    portable_sleep 1                                             # 避免在同一午夜重复执行
  done
}

# ==============================================================================
# 代理状态监控函数
# ==============================================================================

# ------------------------------------------------------------------------------
# watch_proxy_state - 监控代理运行状态（子进程运行）
# 功能：每 3 秒检查一次代理状态，当检测到代理网络变化时重新安装 DNS 规则
#       这确保了代理启动/停止时 DNS 接管能正确更新
# ------------------------------------------------------------------------------
watch_proxy_state() {
  local result
  trap 'exit 0' HUP INT TERM
  while :; do
    result=$("$PROXY_CTL" watch 2>/dev/null) || result=
    case "$result" in
      "active detected="*)
        # VPN 网络出现或消失会改变 Android 的活动 netId
        # 在代理配置事务释放锁后立即重新安装拦截规则并重新绑定 DNSResolver
        "$CTL" rules >> "$DATA_DIR/logs/service.log" 2>&1 || \
          echo "$(portable_date '+%Y-%m-%d %H:%M:%S') resolver refresh after proxy transition failed" >> "$DATA_DIR/logs/service.log"
        ;;
    esac
    portable_sleep 3
  done
}

# ==============================================================================
# 主脚本初始化
# ==============================================================================

# 启动时裁剪一次日志
trim_logs

# 记录监督器启动
echo "$(portable_date '+%Y-%m-%d %H:%M:%S') service supervisor started" >> "$DATA_DIR/logs/service.log"

# ==============================================================================
# 控制桥管理函数
# ==============================================================================

# ------------------------------------------------------------------------------
# bridge_running - 检查控制桥是否正在运行
# 返回：0 = 运行中，1 = 未运行
# 检查逻辑：PID 文件存在 -> 进程存在 -> 进程名称匹配
# ------------------------------------------------------------------------------
bridge_running() {
  local pid
  pid=$(state_get bridge_pid 2>/dev/null)
  case "$pid" in
    ''|*[!0-9]*|0) return 1 ;;                                   # PID 无效或占位 0
  esac
  # 仅使用 kill -0 判断存活。某些 Magisk hide / Zygisk 隐藏模式下读 /proc/$pid/comm
  # 会返回空字符串,导致监督器误判桥已死并 fork 出新的 aghbridge,造成端口冲突与孤儿进程。
  kill -0 "$pid" 2>/dev/null
}

# ------------------------------------------------------------------------------
# start_bridge - 启动控制桥
# 功能：如果控制桥未运行，则启动它
#       控制桥监听 127.0.0.1:3001，接受来自管理器 App 的控制命令
# ------------------------------------------------------------------------------
start_bridge() {
  state_init || return 1
  bridge_running && return 0                                     # 已运行则跳过
  state_set bridge_pid 0
  "$BRIDGE" >> "$DATA_DIR/logs/bridge.log" 2>&1 &                # 后台启动控制桥
  state_set bridge_pid "$!"                                      # 记录 PID
  portable_sleep 1                                               # 等待进程启动
  bridge_running                                                 # 验证是否成功启动
}

# ------------------------------------------------------------------------------
# stop_bridge - 停止控制桥
# 功能：发送 SIGTERM 信号停止控制桥进程
# ------------------------------------------------------------------------------
stop_bridge() {
  local pid
  if bridge_running; then
    pid=$(state_get bridge_pid 2>/dev/null)
    case "$pid" in ''|*[!0-9]*|0) ;; *) kill "$pid" 2>/dev/null ;; esac
  fi
  state_set bridge_pid 0
}

# ==============================================================================
# 退出清理函数
# ==============================================================================

# ------------------------------------------------------------------------------
# cleanup_on_exit - 监督器退出时的清理处理
# 功能：停止所有子进程，停止 AdGuard Home 服务，记录退出日志
# 触发条件：收到 HUP、INT、TERM 信号
# ------------------------------------------------------------------------------
cleanup_on_exit() {
  trap - HUP INT TERM                                            # 清除信号处理
  [ -n "$LOG_MAINT_PID" ] && kill "$LOG_MAINT_PID" 2>/dev/null  # 停止日志维护子进程
  [ -n "$PROXY_WATCH_PID" ] && kill "$PROXY_WATCH_PID" 2>/dev/null  # 停止代理监控子进程
  stop_bridge                                                    # 停止控制桥
  "$CTL" stop >> "$DATA_DIR/logs/service.log" 2>&1               # 停止 AdGuard Home 服务
  echo "$(portable_date '+%Y-%m-%d %H:%M:%S') service supervisor stopped" >> "$DATA_DIR/logs/service.log"
  exit 0
}

# 注册退出清理函数
trap cleanup_on_exit HUP INT TERM

# ==============================================================================
# 启动后台子进程
# ==============================================================================

# 启动日志维护子进程（每天午夜裁剪日志）
maintain_logs &
LOG_MAINT_PID=$!

# 启动代理状态监控子进程（每 3 秒检查代理状态）
watch_proxy_state &
PROXY_WATCH_PID=$!

# ==============================================================================
# 配置文件初始化
# ==============================================================================

# 如果数据目录中没有配置文件，从模块中复制默认配置
if [ ! -s "$CONFIG" ]; then
  cp "$MODDIR/AdGuardHome.yaml" "$CONFIG"
  chmod 600 "$CONFIG"                                            # 设置配置文件权限
fi

# 启动控制桥
start_bridge || echo "$(portable_date '+%Y-%m-%d %H:%M:%S') control bridge failed to start" >> "$DATA_DIR/logs/service.log"

# ==============================================================================
# 清理残留的控制锁
# 解决进程被 SIGKILL 后锁目录残留导致 "busy" 错误的问题
# ==============================================================================
CONTROL_LOCK="$DATA_DIR/control.lock"
if [ -d "$CONTROL_LOCK" ]; then
  # 检查锁是否过期（超过 30 秒）
  if [ -f "$CONTROL_LOCK/owner" ]; then
    lock_pid=$(sed -n 's/^pid=\([0-9][0-9]*\).*/\1/p' "$CONTROL_LOCK/owner" 2>/dev/null | sed -n '1p')
    lock_action=$(sed -n 's/^action=//p' "$CONTROL_LOCK/owner" 2>/dev/null | sed -n '1p')
    # 如果持有锁的进程已死亡，清除锁
    if [ -n "$lock_pid" ] && ! kill -0 "$lock_pid" 2>/dev/null; then
      rm -rf "$CONTROL_LOCK" 2>/dev/null
      echo "$(portable_date '+%Y-%m-%d %H:%M:%S') cleaned stale control lock (dead pid=$lock_pid, action=$lock_action)" >> "$DATA_DIR/logs/service.log"
    fi
  else
    # 没有 owner 文件的孤立锁目录
    rm -rf "$CONTROL_LOCK" 2>/dev/null
    echo "$(portable_date '+%Y-%m-%d %H:%M:%S') cleaned orphan control lock directory" >> "$DATA_DIR/logs/service.log"
  fi
fi

# ==============================================================================
# 等待系统启动完成
# Android 系统启动完成后，sys.boot_completed 属性会设置为 "1"
# 在此之前不启动服务，避免依赖未就绪的系统服务
# ==============================================================================
while [ "$(getprop sys.boot_completed)" != "1" ]; do
  portable_sleep 2
done

# ------------------------------------------------------------------------------
# 处理手动禁用状态
# 用户通过管理器 App 点击"停止"会创建 DISABLED_FILE
# 重启后自动清除禁用状态，确保模块按承诺自动启动
# ------------------------------------------------------------------------------
boot_id=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)
disabled_id=$(state_get disabled_boot_id 2>/dev/null)
if [ -n "$disabled_id" ] && [ "$disabled_id" != "$boot_id" ]; then
  state_set disabled_boot_id ''
fi

# ==============================================================================
# 主监督循环
# ==============================================================================
# 功能：持续监控 AdGuard Home 服务状态，实现：
#   - 服务崩溃后的自动恢复（指数退避重试）
#   - 手动停止状态的处理
#   - 周期性网络同步（每 10 秒）
#   - 控制桥状态维护
#
# 状态转换逻辑：
#   - DISABLED_FILE 存在 → 执行清理，等待用户重新启用
#   - 服务未运行 → 执行 cleanup + start 序列
#   - 服务运行中 → 执行周期性 sync
# ==============================================================================

manual_cleaned=0           # 是否已执行手动停止的清理
failures=0                 # 连续启动失败次数（用于指数退避）
sync_ticks=0               # 同步计数器（每 2 个 tick 执行一次 sync）

while :; do
  # 确保控制桥始终运行
  start_bridge || echo "$(portable_date '+%Y-%m-%d %H:%M:%S') control bridge restart failed" >> "$DATA_DIR/logs/service.log"

  if [ -n "$(state_get disabled_boot_id 2>/dev/null)" ]; then
    # ----------------------------------------------------------------
    # 状态 1：用户手动停止了过滤
    # 执行一次清理后等待用户重新启用
    # ----------------------------------------------------------------
    if [ "$manual_cleaned" != "1" ]; then
      "$CTL" cleanup >> "$DATA_DIR/logs/service.log" 2>&1
      manual_cleaned=1
    fi
    failures=0
    sync_ticks=0

  elif [ "$("$CTL" status 2>/dev/null)" != "running" ]; then
    # ----------------------------------------------------------------
    # 状态 2：服务未运行（可能是崩溃或首次启动）
    # 执行 cleanup + start 序列尝试恢复
    # ----------------------------------------------------------------
    manual_cleaned=0
    sync_ticks=0

    # 启动前清理残留的控制锁
    if [ -d "$CONTROL_LOCK" ]; then
      lock_pid=$(sed -n 's/^pid=\([0-9][0-9]*\).*/\1/p' "$CONTROL_LOCK/owner" 2>/dev/null | sed -n '1p')
      if [ -n "$lock_pid" ] && ! kill -0 "$lock_pid" 2>/dev/null; then
        rm -rf "$CONTROL_LOCK" 2>/dev/null
        echo "$(portable_date '+%Y-%m-%d %H:%M:%S') cleaned stale lock before service restart" >> "$DATA_DIR/logs/service.log"
      fi
    fi

    # 执行清理和启动序列
    "$CTL" cleanup >> "$DATA_DIR/logs/service.log" 2>&1
    if "$CTL" start >> "$DATA_DIR/logs/service.log" 2>&1 && [ "$("$CTL" status 2>/dev/null)" = "running" ]; then
      failures=0                                               # 启动成功，重置失败计数
    else
      # 启动失败，使用指数退避策略避免高频重试
      failures=$((failures + 1))
      case "$failures" in
        1) retry_delay=5 ;;                                    # 第 1 次失败：等待 5 秒
        2) retry_delay=10 ;;                                   # 第 2 次失败：等待 10 秒
        3) retry_delay=20 ;;                                   # 第 3 次失败：等待 20 秒
        *) retry_delay=60 ;;                                   # 后续失败：等待 60 秒
      esac
      echo "$(portable_date '+%Y-%m-%d %H:%M:%S') start failed; retrying in ${retry_delay}s" >> "$DATA_DIR/logs/service.log"
      portable_sleep "$retry_delay"
      continue                                                 # 跳过后续的 sleep，立即重试
    fi

  else
    # ----------------------------------------------------------------
    # 状态 3：服务正常运行
    # 执行周期性同步（网络状态、DNS 接管、代理兼容）
    # ----------------------------------------------------------------
    manual_cleaned=0
    failures=0
    sync_ticks=$((sync_ticks + 1))

    # 每 2 个 tick（10 秒）执行一次同步
    if [ "$sync_ticks" -ge 2 ]; then
      # 同步操作包括：
      #   - 检测网络变化并更新 DNS 接管
      #   - 同步 IPv6 DNS 回退规则
      #   - 更新代理兼容配置
      #   - 启动/维护系统策略清理器
      "$CTL" sync >> "$DATA_DIR/logs/service.log" 2>&1
      sync_ticks=0
    fi
  fi

  # 主循环休眠 5 秒
  portable_sleep 5
done
