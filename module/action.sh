#!/system/bin/sh

DATA_DIR=/data/adb/agh
mkdir -p "$DATA_DIR/logs"
echo "$(date '+%Y-%m-%d %H:%M:%S') module action requested restart" >> "$DATA_DIR/logs/action.log"
if /data/adb/modules/adguardhome/system/bin/aghctl restart >> "$DATA_DIR/logs/action.log" 2>&1; then
  ui_print "AdGuard Home restarted. Open the companion Android app to manage it."
else
  ui_print "AdGuard Home restart failed. See /data/adb/agh/logs/action.log."
  exit 1
fi
