#!/system/bin/sh

MODDIR=${0%/*}
CTL=$MODDIR/system/bin/aghctl
APP_CLEANER=$MODDIR/system/bin/aghappclean
DATA_DIR=/data/adb/agh
LOG_DIR=$DATA_DIR/logs
UNINSTALL_LOG=$LOG_DIR/uninstall.log
IFW_DIR=/data/system/ifw
IFW_BACKUP=$DATA_DIR/ifw-backup
restore_ok=1

mkdir -p "$LOG_DIR"
log() { echo "$(date '+%Y-%m-%d %H:%M:%S') uninstall: $*" >> "$UNINSTALL_LOG"; }
log "started"

restore_ifw() {
  local state
  [ -f "$IFW_BACKUP/created" ] || { log "IFW backup missing"; return 1; }
  state=$(cat "$IFW_BACKUP/state" 2>/dev/null)
  case "$state" in
    present)
      mkdir -p "$IFW_DIR" || return 1
      rm -rf "$IFW_DIR"/* 2>/dev/null || return 1
      cp -a "$IFW_BACKUP/files/." "$IFW_DIR/" 2>> "$UNINSTALL_LOG" || return 1
      restorecon -RF "$IFW_DIR" >> "$UNINSTALL_LOG" 2>&1 || :
      ;;
    absent)
      rm -rf "$IFW_DIR" 2>/dev/null || return 1
      ;;
    *) log "invalid IFW backup state"; return 1 ;;
  esac
  log "IFW state restored"
}

if [ -x "$CTL" ]; then
  "$CTL" proxy-disable >> "$UNINSTALL_LOG" 2>&1 || { log "proxy restore failed"; restore_ok=0; }
  "$CTL" stop >> "$UNINSTALL_LOG" 2>&1 || { log "service stop or DNS restore failed"; restore_ok=0; }
  "$CTL" cleanup >> "$UNINSTALL_LOG" 2>&1 || { log "firewall/resolver cleanup failed"; restore_ok=0; }
fi

[ -x "$APP_CLEANER" ] && "$APP_CLEANER" --stop >> "$UNINSTALL_LOG" 2>&1 || { log "private DNS restore failed"; restore_ok=0; }
restore_ifw || { log "IFW restore failed"; restore_ok=0; }

for pid_file in "$DATA_DIR/agh.pid" "$DATA_DIR/aghbridge.pid" "$DATA_DIR/appclean.pid"; do
  [ -s "$pid_file" ] || continue
  pid=$(cat "$pid_file" 2>/dev/null)
  case "$pid" in
    ''|*[!0-9]*) ;;
    *) kill "$pid" 2>/dev/null ;;
  esac
done

# customize.sh installs the companion app.  A full module uninstall also
# removes that package and the module-owned persistent working directory.
pm uninstall com.adguardhome.android >/dev/null 2>&1
if [ "$restore_ok" = 1 ]; then
  log "system state restored; removing module data"
  rm -rf "$DATA_DIR"
else
  log "restore incomplete; preserving $DATA_DIR for recovery and diagnostics"
fi
