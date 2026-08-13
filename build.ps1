$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$appRoot = Join-Path $projectRoot 'app'
$sdkRoot = if ($env:ANDROID_HOME) {
    $env:ANDROID_HOME
} elseif ($env:ANDROID_SDK_ROOT) {
    $env:ANDROID_SDK_ROOT
} else {
    Join-Path $env:LOCALAPPDATA 'Android\Sdk'
}
if (-not (Test-Path -LiteralPath $sdkRoot)) {
    throw 'Android SDK not found. Set ANDROID_HOME or ANDROID_SDK_ROOT.'
}
$env:ANDROID_HOME = $sdkRoot
$env:ANDROID_SDK_ROOT = $sdkRoot
$gradle = Join-Path $appRoot 'gradlew.bat'
$ndk = Get-ChildItem (Join-Path $sdkRoot 'ndk') -Directory | Sort-Object Name -Descending | Select-Object -First 1
if (-not $ndk) {
    throw 'Android NDK not found. Install it under the configured Android SDK.'
}
$clang = Join-Path $ndk.FullName 'toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android26-clang.cmd'
if (-not (Test-Path -LiteralPath $clang)) {
    throw "ARM64 Android compiler not found: $clang"
}
$bridgeSource = Join-Path $projectRoot 'native\aghbridge.c'
$bridgeBinary = Join-Path $projectRoot 'module\system\bin\aghbridge'
& $clang -Oz -fPIE -pie -fstack-protector-strong -D_FORTIFY_SOURCE=2 -Wall -Wextra -Werror -s -o $bridgeBinary $bridgeSource
if ($LASTEXITCODE -ne 0) {
    throw "Native control bridge build failed with exit code $LASTEXITCODE."
}
Push-Location $appRoot
try {
    if (Test-Path -LiteralPath $gradle) {
        & $gradle clean assembleDebug lintDebug --no-daemon
    } else {
        throw 'Gradle wrapper not found. Run .\app\gradlew.bat once with network access.'
    }
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle failed with exit code $LASTEXITCODE."
    }
} finally {
    Pop-Location
}

$dist = Join-Path $projectRoot 'dist'
$module = Join-Path $projectRoot 'module'
$appApk = Join-Path $appRoot 'app\build\outputs\apk\debug\app-debug.apk'
New-Item -ItemType Directory -Force -Path $dist | Out-Null
Copy-Item $appApk (Join-Path $dist 'AdGuardHome-Android-debug.apk') -Force

$zipPath = Join-Path $dist 'AdGuardHome-Android-v1.2.2-0.107.78.zip'
$python = @'
import os
import sys
import zipfile

source, destination, helper_apk = sys.argv[1], sys.argv[2], sys.argv[3]
with zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
    written = set()
    for root, dirs, files in os.walk(source):
        for name in files:
            path = os.path.join(root, name)
            relative = os.path.relpath(path, source).replace(os.sep, "/")
            info = zipfile.ZipInfo(relative)
            info.create_system = 3
            info.compress_type = zipfile.ZIP_DEFLATED
            executable = name in {"service.sh", "post-fs-data.sh", "uninstall.sh", "action.sh", "customize.sh", "aghctl", "aghproxy", "aghbridge", "aghappclean", "AdGuardHome"}
            info.external_attr = ((0o755 if executable else 0o644) & 0xFFFF) << 16
            with open(path, "rb") as stream:
                archive.writestr(info, stream.read())
            written.add(relative)
    helper_name = "system/etc/agh-dns-helper.apk"
    if helper_name not in written:
        info = zipfile.ZipInfo(helper_name)
        info.create_system = 3
        info.compress_type = zipfile.ZIP_DEFLATED
        info.external_attr = (0o644 & 0xFFFF) << 16
        with open(helper_apk, "rb") as stream:
            archive.writestr(info, stream.read())
'@
$python | python - $module $zipPath $appApk
Write-Host "APK: $dist\AdGuardHome-Android-debug.apk"
Write-Host "Module: $zipPath"
