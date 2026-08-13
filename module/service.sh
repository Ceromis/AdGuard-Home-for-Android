#!/system/bin/sh

MODDIR=${0%/*}
DATA_DIR=/data/adb/agh
CONFIG=$DATA_DIR/AdGuardHome.yaml
AGH_BIN=$MODDIR/system/bin/AdGuardHome
CTL=$MODDIR/system/bin/aghctl
BRIDGE=$MODDIR/system/bin/aghbridge
PROXY_CTL=$MODDIR/system/bin/aghproxy
BRIDGE_PID=$DATA_DIR/aghbridge.pid
DISABLED_FILE=$DATA_DIR/disabled
LOG_MAX_BYTES=${AGH_LOG_MAX_BYTES:-524288}
LOG_KEEP_BYTES=${AGH_LOG_KEEP_BYTES:-262144}
LOG_MAINT_PID=
PROXY_WATCH_PID=

mkdir -p "$DATA_DIR" "$DATA_DIR/work" "$DATA_DIR/logs"
chmod 700 "$DATA_DIR"
chmod 755 "$AGH_BIN" "$CTL" "$BRIDGE" "$PROXY_CTL" "$MODDIR/system/bin/aghappclean"

trim_log_file() {
  local file=$1 size temp
  [ -f "$file" ] || return 0
  size=$(wc -c < "$file" 2>/dev/null) || return 0
  case "$size" in
    ''|*[!0-9]*) return 0 ;;
  esac
  [ "$size" -le "$LOG_MAX_BYTES" ] && return 0
  temp=$file.trim.$$
  if tail -c "$LOG_KEEP_BYTES" "$file" > "$temp" 2>/dev/null; then
    cat "$temp" > "$file"
  fi
  rm -f "$temp"
}

trim_logs() {
  local file
  for file in "$DATA_DIR"/logs/*.log; do
    trim_log_file "$file"
  done
}

seconds_until_midnight() {
  local now hour minute second
  now=$(date '+%H %M %S') || return 1
  set -- $now
  hour=${1#0}
  minute=${2#0}
  second=${3#0}
  [ -n "$hour" ] || hour=0
  [ -n "$minute" ] || minute=0
  [ -n "$second" ] || second=0
  echo $((86400 - hour * 3600 - minute * 60 - second))
}

maintain_logs() {
  local delay sleeper
  trap '[ -n "$sleeper" ] && kill "$sleeper" 2>/dev/null; exit 0' HUP INT TERM
  while :; do
    delay=$(seconds_until_midnight) || delay=86400
    sleep "$delay" &
    sleeper=$!
    wait "$sleeper" || exit 0
    sleeper=
    trim_logs
    # Avoid computing another zero-length delay during the same midnight.
    sleep 1
  done
}

watch_proxy_state() {
  local result
  trap 'exit 0' HUP INT TERM
  while :; do
    result=$("$PROXY_CTL" watch 2>/dev/null) || result=
    case "$result" in
      "active detected="*|restored)
        # A VPN network appearing or disappearing changes Android's active
        # netId.  Reinstall interception and rebind DNSResolver immediately
        # after the proxy configuration transaction has released its lock.
        "$CTL" rules >> "$DATA_DIR/logs/service.log" 2>&1 || \
          echo "$(date '+%Y-%m-%d %H:%M:%S') resolver refresh after proxy transition failed" >> "$DATA_DIR/logs/service.log"
        ;;
    esac
    sleep 1
  done
}

trim_logs
echo "$(date '+%Y-%m-%d %H:%M:%S') service supervisor started" >> "$DATA_DIR/logs/service.log"

bridge_running() {
  local pid
  [ -s "$BRIDGE_PID" ] || return 1
  pid=$(cat "$BRIDGE_PID" 2>/dev/null)
  case "$pid" in
    ''|*[!0-9]*) return 1 ;;
  esac
  kill -0 "$pid" 2>/dev/null && [ "$(cat "/proc/$pid/comm" 2>/dev/null)" = "aghbridge" ]
}

start_bridge() {
  bridge_running && return 0
  rm -f "$BRIDGE_PID"
  "$BRIDGE" >> "$DATA_DIR/logs/bridge.log" 2>&1 &
  echo $! > "$BRIDGE_PID"
  sleep 1
  bridge_running
}

stop_bridge() {
  if bridge_running; then
    kill "$(cat "$BRIDGE_PID")" 2>/dev/null
  fi
  rm -f "$BRIDGE_PID"
}

cleanup_on_exit() {
  trap - HUP INT TERM
  [ -n "$LOG_MAINT_PID" ] && kill "$LOG_MAINT_PID" 2>/dev/null
  [ -n "$PROXY_WATCH_PID" ] && kill "$PROXY_WATCH_PID" 2>/dev/null
  stop_bridge
  "$CTL" stop >> "$DATA_DIR/logs/service.log" 2>&1
  echo "$(date '+%Y-%m-%d %H:%M:%S') service supervisor stopped" >> "$DATA_DIR/logs/service.log"
  exit 0
}

trap cleanup_on_exit HUP INT TERM
maintain_logs &
LOG_MAINT_PID=$!
watch_proxy_state &
PROXY_WATCH_PID=$!

if [ ! -s "$CONFIG" ]; then
  cp "$MODDIR/AdGuardHome.yaml" "$CONFIG"
  chmod 600 "$CONFIG"
fi

start_bridge || echo "$(date '+%Y-%m-%d %H:%M:%S') control bridge failed to start" >> "$DATA_DIR/logs/service.log"

while [ "$(getprop sys.boot_completed)" != "1" ]; do
  sleep 2
done

# A manual stop is valid only for the current boot.  Rebooting keeps the
# module's promised autostart behavior while still making the App's Stop
# button meaningful during normal use.
boot_id=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)
if [ -f "$DISABLED_FILE" ] && [ "$(cat "$DISABLED_FILE" 2>/dev/null)" != "$boot_id" ]; then
  rm -f "$DISABLED_FILE"
fi

# Keep supervising for the lifetime of the module.  A process crash is a
# recoverable event: clean interception first, then retry the service.  The
# sync pass follows network-id changes so IPv6 DNS continues using localhost.
manual_cleaned=0
failures=0
sync_ticks=0
while :; do
  start_bridge || echo "$(date '+%Y-%m-%d %H:%M:%S') control bridge restart failed" >> "$DATA_DIR/logs/service.log"
  if [ -f "$DISABLED_FILE" ]; then
    if [ "$manual_cleaned" != "1" ]; then
      "$CTL" cleanup >> "$DATA_DIR/logs/service.log" 2>&1
      manual_cleaned=1
    fi
    failures=0
    sync_ticks=0
  elif [ "$("$CTL" status 2>/dev/null)" != "running" ]; then
    manual_cleaned=0
    sync_ticks=0
    "$CTL" cleanup >> "$DATA_DIR/logs/service.log" 2>&1
    if "$CTL" start >> "$DATA_DIR/logs/service.log" 2>&1 && [ "$("$CTL" status 2>/dev/null)" = "running" ]; then
      failures=0
    else
      failures=$((failures + 1))
      case "$failures" in
        1) retry_delay=5 ;;
        2) retry_delay=10 ;;
        3) retry_delay=20 ;;
        *) retry_delay=60 ;;
      esac
      echo "$(date '+%Y-%m-%d %H:%M:%S') start failed; retrying in ${retry_delay}s" >> "$DATA_DIR/logs/service.log"
      sleep "$retry_delay"
      continue
    fi
  else
    manual_cleaned=0
    failures=0
    sync_ticks=$((sync_ticks + 1))
    if [ "$sync_ticks" -ge 2 ]; then
      # A manual control action owns the same lock; skip this tick while it is busy.
      "$CTL" sync >> "$DATA_DIR/logs/service.log" 2>&1
      sync_ticks=0
    fi
  fi
  sleep 5
done
