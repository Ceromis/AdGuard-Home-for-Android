#!/system/bin/sh

OUTPUT=${AGH_DUMPSYS_OUTPUT:-/storage/emulated/0/Download/dumpsys.zip}
TMP_ROOT=${AGH_DUMPSYS_TMP_ROOT:-/data/local/tmp}
TMP_DIR=$TMP_ROOT/agh-dumpsys.$$

portable_date() {
  if [ -x /system/bin/toybox ]; then
    /system/bin/toybox date "$@"
  else
    date "$@"
  fi
}

cleanup() {
  case "$TMP_DIR" in
    /data/local/tmp/agh-dumpsys.[0-9]*|/dev/agh-dumpsys.[0-9]*)
      rm -rf "$TMP_DIR" 2>/dev/null
      ;;
  esac
}

find_helper_apk() {
  local candidate apk
  for candidate in \
      /data/adb/modules/adguardhome/system/etc/agh-dns-helper.apk \
      /data/adb/modules_update/adguardhome/system/etc/agh-dns-helper.apk; do
    if [ -f "$candidate" ]; then
      echo "$candidate"
      return 0
    fi
  done
  apk=$(pm path com.adguardhome.android 2>/dev/null \
    | sed -n 's/^package://p' \
    | sed -n '1p')
  [ -n "$apk" ] && [ -f "$apk" ] || return 1
  echo "$apk"
}

publish_permissions() {
  local file=$1
  chown 1023:1023 "$file" 2>/dev/null || :
  chmod 664 "$file" 2>/dev/null || :
  restorecon "$file" >/dev/null 2>&1 || :
}

trap cleanup EXIT HUP INT TERM
case "$TMP_ROOT" in
  /data/local/tmp|/dev) ;;
  *) echo "Unsupported temporary directory: $TMP_ROOT" >&2; exit 64 ;;
esac

mkdir -p "$TMP_DIR" "${OUTPUT%/*}" || exit 1

{
  echo "AdGuard Home for Android connectivity diagnostics"
  echo "captured_at=$(portable_date '+%Y-%m-%d %H:%M:%S %z' 2>/dev/null)"
  echo "uid=$(id 2>/dev/null)"
  echo "android_release=$(getprop ro.build.version.release 2>/dev/null)"
  echo "sdk=$(getprop ro.build.version.sdk 2>/dev/null)"
  echo "device=$(getprop ro.product.model 2>/dev/null)"
  echo "fingerprint=$(getprop ro.build.fingerprint 2>/dev/null)"
} > "$TMP_DIR/metadata.log"

{
  echo "===== dumpsys connectivity ====="
  dumpsys connectivity 2>&1
} > "$TMP_DIR/dumpsys.log"

{
  echo "===== ip rule ====="
  ip rule 2>&1
  echo "===== IPv4 routes (all tables) ====="
  ip -4 route show table all 2>&1
  echo "===== IPv6 routes (all tables) ====="
  ip -6 route show table all 2>&1
  echo "===== addresses ====="
  ip addr 2>&1
  echo "===== private DNS ====="
  echo "mode=$(settings get global private_dns_mode 2>/dev/null)"
  echo "specifier=$(settings get global private_dns_specifier 2>/dev/null)"
} > "$TMP_DIR/network-context.log"

chmod 600 "$TMP_DIR"/*.log 2>/dev/null || :

HELPER_APK=$(find_helper_apk 2>/dev/null) || HELPER_APK=
if [ -x /system/bin/app_process64 ]; then
  APP_PROCESS=/system/bin/app_process64
elif [ -x /system/bin/app_process ]; then
  APP_PROCESS=/system/bin/app_process
else
  APP_PROCESS=
fi

if [ -n "$HELPER_APK" ] && [ -n "$APP_PROCESS" ]; then
  CLASSPATH="$HELPER_APK" "$APP_PROCESS" /system/bin \
    com.adguardhome.android.LogExporter "$TMP_DIR" "$OUTPUT" || exit 1
  publish_permissions "$OUTPUT"
  echo "$OUTPUT"
  exit 0
fi

FALLBACK=${OUTPUT%.zip}.tar.gz
if command -v tar >/dev/null 2>&1 && command -v gzip >/dev/null 2>&1; then
  tar -czf "$FALLBACK.tmp" -C "$TMP_DIR" . || exit 1
  mv "$FALLBACK.tmp" "$FALLBACK" || exit 1
  publish_permissions "$FALLBACK"
  echo "$FALLBACK"
  exit 0
fi

echo "No ZIP exporter or tar/gzip implementation is available" >&2
exit 1
