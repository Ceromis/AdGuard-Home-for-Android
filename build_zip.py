#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Pack the Magisk/KernelSU module zip for AdGuard Home for Android.

Usage: build_zip.py <module_dir> <output_zip> <app_apk> <module_prop>
The app APK is embedded as system/etc/agh-dns-helper.apk and module.prop is
replaced by the freshly copied release version.
"""
import os
import sys
import time
import zipfile

def add(zf, path, arcname, mode):
    zi = zipfile.ZipInfo(arcname)
    zi.compress_type = zipfile.ZIP_DEFLATED
    zi.external_attr = (mode & 0xFFFF) << 16
    mtime = os.path.getmtime(path)
    zi.date_time = time.localtime(mtime)[:6]
    with open(path, "rb") as f:
        zf.writestr(zi, f.read())

def main():
    if len(sys.argv) != 5:
        print("usage: build_zip.py <module_dir> <output_zip> <app_apk> <module_prop>", file=sys.stderr)
        return 1
    module_dir, output_zip, app_apk, module_prop = sys.argv[1:5]

    root_scripts = ["action.sh", "customize.sh", "post-fs-data.sh", "service.sh", "uninstall.sh"]
    with zipfile.ZipFile(output_zip, "w", zipfile.ZIP_DEFLATED) as zf:
        add(zf, os.path.join(module_dir, "AdGuardHome.yaml"), "AdGuardHome.yaml", 0o644)
        for name in root_scripts:
            add(zf, os.path.join(module_dir, name), name, 0o755)
        seed_dir = os.path.join(module_dir, "seed")
        if os.path.isdir(seed_dir):
            for root, _dirs, files in os.walk(seed_dir):
                for name in sorted(files):
                    full = os.path.join(root, name)
                    rel = os.path.relpath(full, module_dir).replace(os.sep, "/")
                    add(zf, full, rel, 0o644)
        system_dir = os.path.join(module_dir, "system")
        if os.path.isdir(system_dir):
            for root, _dirs, files in os.walk(system_dir):
                for name in sorted(files):
                    full = os.path.join(root, name)
                    rel = os.path.relpath(full, module_dir).replace(os.sep, "/")
                    mode = 0o755 if rel.startswith("system/bin/") else 0o644
                    add(zf, full, rel, mode)
        add(zf, module_prop, "module.prop", 0o644)
        add(zf, app_apk, "system/etc/agh-dns-helper.apk", 0o644)
    print("packed: %s" % output_zip)
    return 0

if __name__ == "__main__":
    sys.exit(main())
