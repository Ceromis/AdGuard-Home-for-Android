#!/system/bin/sh

MODDIR=${0%/*}
DATA_DIR=/data/adb/agh
mkdir -p "$DATA_DIR/logs"
chmod 755 "$MODDIR/system/bin/AdGuardHome" "$MODDIR/system/bin/aghctl" "$MODDIR/system/bin/aghbridge" "$MODDIR/system/bin/aghproxy" "$MODDIR/system/bin/aghappclean"
echo "$(date '+%Y-%m-%d %H:%M:%S') post-fs-data permissions applied" >> "$DATA_DIR/logs/install.log"
