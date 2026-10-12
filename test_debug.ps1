$ErrorActionPreference = 'Continue'

# 可选:按本机环境设置 SDK 路径;未设置时使用环境变量或默认 SDK 位置。
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk" }
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
Set-Location (Split-Path -Parent $MyInvocation.MyCommand.Path)

Write-Host "=== Debug ==="
Write-Host "MyCommand.Path = $($MyInvocation.MyCommand.Path)"
Write-Host "PSCommandPath = $PSCommandPath"
Write-Host "PWD = $PWD"

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Write-Host "projectRoot = $projectRoot"
$test = Join-Path $projectRoot 'native\_bridge_token.h'
Write-Host "test path = $test"
exit 0
