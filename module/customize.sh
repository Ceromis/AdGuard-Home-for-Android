#!/system/bin/sh

ui_print "- Installing bundled ARM64 AdGuard Home"
ui_print "- Native Android app provides the management panel"
ui_print "- Local API: http://127.0.0.1:3000"
ui_print "- Default credentials: root / root"

DATA_DIR=/data/adb/agh
IFW_DIR=/data/system/ifw
IFW_BACKUP=$DATA_DIR/ifw-backup
FACTORY_RULES_BACKUP=$DATA_DIR/factory-user-rules.txt
mkdir -p "$DATA_DIR/logs"
echo "$(date '+%Y-%m-%d %H:%M:%S') module installation started" >> "$DATA_DIR/logs/install.log"

# Keep the first pre-module IFW snapshot across updates.  Never overwrite it
# after the cleaner has started removing rules.
if [ ! -f "$IFW_BACKUP/created" ]; then
  rm -rf "$IFW_BACKUP.tmp"
  mkdir -p "$IFW_BACKUP.tmp/files" || abort "Unable to prepare IFW backup."
  if [ -d "$IFW_DIR" ]; then
    echo present > "$IFW_BACKUP.tmp/state"
    cp -a "$IFW_DIR/." "$IFW_BACKUP.tmp/files/" 2>> "$DATA_DIR/logs/install.log" || abort "Unable to back up /data/system/ifw."
  else
    echo absent > "$IFW_BACKUP.tmp/state"
  fi
  date '+%Y-%m-%d %H:%M:%S' > "$IFW_BACKUP.tmp/created"
  chmod 700 "$IFW_BACKUP.tmp" "$IFW_BACKUP.tmp/files"
  mv "$IFW_BACKUP.tmp" "$IFW_BACKUP" || abort "Unable to save IFW backup."
  echo "$(date '+%Y-%m-%d %H:%M:%S') IFW state backed up" >> "$DATA_DIR/logs/install.log"
fi

# Keep an untouched copy of the bundled default custom rules for the manager's
# "restore factory rules" action. This is separate from IFW and uninstall data.
if [ ! -s "$FACTORY_RULES_BACKUP" ]; then
  awk 'found { if ($0 ~ /^[^ ]/) exit; if ($0 ~ /^  - /) { sub(/^  - /, ""); gsub(/^'\''|'\''$/, ""); print } } /^user_rules:/ { found=1 }' "$MODPATH/AdGuardHome.yaml" > "$FACTORY_RULES_BACKUP.tmp" || abort "Unable to back up factory rules."
  [ -s "$FACTORY_RULES_BACKUP.tmp" ] || abort "Factory rules backup is empty."
  chmod 600 "$FACTORY_RULES_BACKUP.tmp"
  mv "$FACTORY_RULES_BACKUP.tmp" "$FACTORY_RULES_BACKUP"
fi

# Save the user's private DNS preference before the cleaner first forces it
# off.  Upgrades keep the original snapshot.
PRIVATE_DNS_STATE=$DATA_DIR/private-dns.state
if [ ! -s "$PRIVATE_DNS_STATE" ]; then
  {
    echo "mode=$(settings get global private_dns_mode 2>/dev/null)"
    echo "specifier=$(settings get global private_dns_specifier 2>/dev/null)"
  } > "$PRIVATE_DNS_STATE.tmp" || abort "Unable to back up private DNS settings."
  chmod 600 "$PRIVATE_DNS_STATE.tmp"
  mv "$PRIVATE_DNS_STATE.tmp" "$PRIVATE_DNS_STATE"
fi

if [ "$(getprop ro.product.cpu.abi)" != "arm64-v8a" ]; then
  abort "This package contains an ARM64 binary and requires arm64-v8a."
fi

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
    rm -f "$APP_APK"
    ;;
  *)
    echo "$(date '+%Y-%m-%d %H:%M:%S') companion app installation failed: $install_output" >> "$DATA_DIR/logs/install.log"
    abort "Android app installation failed: $install_output"
    ;;
esac
