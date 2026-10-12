#!/system/bin/sh
# ==============================================================================
# AdGuard Home for Android - 模块操作脚本 (action.sh)
# ==============================================================================
# 功能：SukiSU/Magisk 点击"执行"后,读取实际端口并立即用浏览器打开 WebUI。
#      取端口 -> am start,不等待、不重启,失败原因直接回显到管理器。
# ==============================================================================

DATA_DIR=/data/adb/agh
CONFIG=$DATA_DIR/AdGuardHome.yaml
CTL=/data/adb/modules/adguardhome/system/bin/aghctl
LOG=$DATA_DIR/logs/action.log
AM=/system/bin/am
CMD=/system/bin/cmd

say() {
  echo "$*"
  if command -v ui_print >/dev/null 2>&1; then
    ui_print "$*" >/dev/null 2>&1
  fi
  return 0
}

mkdir -p "$DATA_DIR/logs" 2>/dev/null
echo "$(date '+%Y-%m-%d %H:%M:%S') module action requested webui" >> "$LOG" 2>/dev/null

# 取实际端口:先直接解析 YAML,失败再走 aghctl,最后兜底 3000
PORT=$(awk '
  /^http:/ { section = "http"; next }
  /^[a-zA-Z_]+:/ { section = "" }
  section == "http" && /^  address:/ {
    line = $0
    sub(/^.*:/, "", line)
    gsub(/[^0-9]/, "", line)
    if (line ~ /^[0-9]+$/) print line
    exit
  }
' "$CONFIG" 2>/dev/null)
case "$PORT" in
  ''|*[!0-9]*) PORT=$("$CTL" port-get 2>/dev/null) ;;
esac
case "$PORT" in ''|*[!0-9]*) PORT=3000 ;; esac
if [ "$PORT" -lt 1 ] || [ "$PORT" -gt 65535 ]; then
  PORT=3000
fi
URL="http://127.0.0.1:$PORT/"

say "AdGuard Home WebUI: $URL"
say "默认账号密码: root / root"

# 服务没跑就在后台拉起,不阻塞本次跳转
case "$("$CTL" status 2>/dev/null)" in
  *running*) ;;
  *)
    "$CTL" start >> "$LOG" 2>&1 &
    say "服务未运行,已在后台启动,页面打不开请稍后刷新"
    ;;
esac

if [ ! -x "$AM" ]; then
  say "未找到 am,请手动访问上面的地址"
  exit 0
fi

# 优先解析默认浏览器并显式启动(比隐式 VIEW 更稳)
COMP=
if [ -x "$CMD" ]; then
  COMP=$("$CMD" package resolve-activity --brief --user 0 -a android.intent.action.VIEW -d "$URL" 2>/dev/null | tail -n 1)
fi

OUT=
case "$COMP" in
  */*)
    OUT=$("$AM" start --user 0 -n "$COMP" -a android.intent.action.VIEW -d "$URL" -f 0x10000000 2>&1)
    ;;
esac

case "$OUT" in
  ''|*Error*|*error*|*Exception*|*blocked*|*Permission*|*denied*)
    # 解析或显式启动失败时,回退到最直接的隐式跳转
    OUT=$("$AM" start -a android.intent.action.VIEW -d "$URL" -f 0x10000000 2>&1)
    ;;
esac

echo "$OUT" >> "$LOG" 2>/dev/null
case "$OUT" in
  *Error*|*error*|*Exception*|*blocked*|*Permission*|*denied*)
    say "跳转失败: $OUT"
    say "请检查系统是否禁止后台弹出界面,或手动访问上面的地址"
    ;;
  *)
    say "已调用浏览器打开"
    ;;
esac
exit 0
