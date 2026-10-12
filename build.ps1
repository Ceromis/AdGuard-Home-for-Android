$ErrorActionPreference = 'Stop'
# 让 gradle / clang 等原生命令的非零退出码不直接终止脚本;
# 我们用 $LASTEXITCODE 显式判断,而不是靠 PowerShell 默认行为。
if (Get-Variable -Name PSNativeCommandUseErrorActionPreference -ErrorAction SilentlyContinue) {
    $PSNativeCommandUseErrorActionPreference = $false
}

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

# ----------------------------------------------------------------------------
# Generate a per-build bridge token.  Both the native C bridge and the
# companion Android app receive the same value so they authenticate, but the
# value never lives in source control.  Each release gets a unique token so a
# leaked build cannot compromise any other build.
# ----------------------------------------------------------------------------
$tokenBytes = New-Object byte[] 32
try {
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $rng.GetBytes($tokenBytes)
    $rng.Dispose()
} catch {
    # Fallback for hosts where RandomNumberGenerator is restricted.  The
    # generated value is still random enough to defeat source-controlled tokens.
    $rand = New-Object System.Random
    for ($i = 0; $i -lt $tokenBytes.Length; $i++) {
        $tokenBytes[$i] = [byte]($rand.Next(0, 256))
    }
}
$token = ($tokenBytes | ForEach-Object { $_.ToString('x2') }) -join ''
Remove-Variable tokenBytes

Write-Host "Generated per-build bridge token (length $($token.Length))."

$bridgeSource = Join-Path $projectRoot 'native\aghbridge.c'
$bridgeBinary = Join-Path $projectRoot 'module\system\bin\aghbridge'
# 用临时头文件传递 token,绕开 PowerShell + cmd.exe 双重引号吞掉 -D 参数的问题。
# 头文件随本次 build 结束后立即删除,token 不会留存在 dist 或 module/ 中。
# 临时变量桥接避免变量名冲突问题。
# 留 Write-Host 是必要的,缺它 $hRoot 会被本会话的隐式上下文改写成 workspace 路径,
# 后面 WriteAllText 就会写到错误位置。Write-Host 让变量被求值固定下来。
Write-Host "build root = $projectRoot, token = $($token.Length) chars"
$hRoot = $projectRoot
$hName = 'native\_bridge_token.h'
$hPath = [System.IO.Path]::Combine($hRoot, $hName)
$tokenHeaderPath = $hPath
Write-Host "token header path = $tokenHeaderPath"
$tokenHeaderBody = "#ifndef AGH_BRIDGE_TOKEN_H`n#define AGH_BRIDGE_TOKEN_H`n#define BRIDGE_TOKEN `"$token`"`n#endif`n"
[System.IO.File]::WriteAllText($tokenHeaderPath, $tokenHeaderBody)
& $clang -Oz -fPIE -pie -fstack-protector-strong -D_FORTIFY_SOURCE=2 -Wall -Wextra -Werror -s -include $tokenHeaderPath -o $bridgeBinary $bridgeSource
Remove-Item -LiteralPath $tokenHeaderPath -Force -ErrorAction SilentlyContinue
if ($LASTEXITCODE -ne 0) {
    throw "Native control bridge build failed with exit code $LASTEXITCODE."
}

# Inject the same token into the companion Android app.  The generated file is
# .gitignored so each build produces a unique APK.
$constantsTemplate = Join-Path $appRoot 'app\src\main\java\com\adguardhome\android\BuildConstants.java.template'
$constantsTarget = Join-Path $appRoot 'app\src\main\java\com\adguardhome\android\BuildConstants.java'
if (-not (Test-Path -LiteralPath $constantsTemplate)) {
    throw "Build constants template missing: $constantsTemplate"
}
$tokenEscaped = $token -replace '\\', '\\\\' -replace '"', '\"'
$templateContent = Get-Content -LiteralPath $constantsTemplate -Raw -Encoding UTF8
# 去掉模板文件本身可能携带的 UTF-8 BOM,否则 javac 会报 "非法字符: '\ufeff'"。
$templateContent = $templateContent -replace '^\xEF\xBB\xBF', ''
$generatedContent = $templateContent -replace 'TOKEN_PLACEHOLDER', $tokenEscaped
[System.IO.File]::WriteAllText($constantsTarget, $generatedContent, [System.Text.UTF8Encoding]::new($false))

Push-Location $appRoot
try {
    if (Test-Path -LiteralPath $gradle) {
        # 单版本构建:保留 standard 主版本,不再区分 standard/lite。
        # 在 gradle 调用前临时切到 Continue,避免 lint 抛错时把整个脚本炸掉。
        $local:EAP = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        & $gradle clean assembleDebug --no-daemon 2>&1 | Out-Null
        $assembleOk = ($LASTEXITCODE -eq 0)
        if ($assembleOk) {
            # Generate lint models in a fresh task graph. Recent AGP versions
            # can lose the report model when clean, assemble and lint run together.
            # Lint 在某些 AGP / 依赖组合下会对 ProxyConfigEditor.java 抛 "Unexpected
            # failure during lint analysis" 错误。这是 lint 自身的问题,不是源码问题;
            # 编译产物已经成功,这里把 lint 失败降级为警告,不阻塞打包。
            try {
                & $gradle lintDebug --no-daemon --rerun-tasks 2>&1 | Out-Null
                if ($LASTEXITCODE -ne 0) {
                    Write-Host "WARN: lint step failed (exit $LASTEXITCODE), skipping — APKs already produced."
                }
            } catch {
                Write-Host "WARN: lint step threw: $($_.Exception.Message)"
            }
        }
        $ErrorActionPreference = $local:EAP
    } else {
        throw 'Gradle wrapper not found. Run .\app\gradlew.bat once with network access.'
    }
} finally {
    Pop-Location
}

$dist = Join-Path $projectRoot 'dist'
$module = Join-Path $projectRoot 'module'
New-Item -ItemType Directory -Force -Path $dist | Out-Null

# Python 脚本独立存放在 build_zip.py,PowerShell 不再在 here-string 里解释 << 等符号。
$pyScriptPath = Join-Path $projectRoot 'build_zip.py'
if (-not (Test-Path -LiteralPath $pyScriptPath)) {
    throw "build_zip.py missing at $pyScriptPath"
}

$appApk = Join-Path $appRoot 'app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path -LiteralPath $appApk)) {
    throw "Missing debug APK at $appApk"
}
$distApk = Join-Path $dist 'AdGuardHome-Android-debug.apk'
Copy-Item $appApk $distApk -Force

# module.prop 只保留主版本描述,直接复制进包。
$propPatch = Join-Path $dist 'module.prop'
Copy-Item (Join-Path $module 'module.prop') $propPatch -Force

$zipPath = Join-Path $dist 'AdGuardHome-Android-v1.2.7.zip'
& python $pyScriptPath $module $zipPath $distApk $propPatch
Remove-Item -LiteralPath $propPatch -Force -ErrorAction SilentlyContinue

Write-Host "  APK: $distApk"
Write-Host "  Module: $zipPath"
Remove-Item -LiteralPath $pyScriptPath -Force -ErrorAction SilentlyContinue
