# 等待 GitHub Actions 构建完成 -> 下载 APK -> adb 安装 -> 自动删除下载目录
# 用法: powershell -File install-beta.ps1 -RunId <run_id>
param(
    [Parameter(Mandatory = $true)][string]$RunId,
    [string]$Repo = "GeniusQA/XiaoCan"
)

$ErrorActionPreference = "Continue"
$gh = "D:\GitHub CLI\gh.exe"
$maxWait = 1200
$elapsed = 0
$status = ""

while ($elapsed -lt $maxWait) {
    $status = (& $gh -R $Repo run view $RunId --json status -q .status 2>$null)
    if ($status -eq "completed") { break }
    Start-Sleep -Seconds 20
    $elapsed += 20
}
Write-Host "STATUS=$status"
if ($status -ne "completed") { Write-Host "TIMEOUT"; exit 1 }

$conclusion = (& $gh -R $Repo run view $RunId --json conclusion -q .conclusion 2>$null)
Write-Host "CONCLUSION=$conclusion"
if ($conclusion -ne "success") { Write-Host "BUILD FAILED"; exit 1 }

# 下载到桌面 build_dl 目录，不占用项目目录；安装后自动删除
$tmp = Join-Path ([Environment]::GetFolderPath("Desktop")) "build_dl\beta_$RunId"
Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
& $gh -R $Repo run download $RunId -D $tmp 2>&1
$apk = Get-ChildItem -Path $tmp -Recurse -Filter *.apk | Select-Object -First 1
if (-not $apk) { Write-Host "NO APK"; Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue; exit 1 }
Write-Host "APK=$($apk.FullName)"

$installOutput = adb install -r "$($apk.FullName)" 2>&1
Write-Host $installOutput

# 无论安装成功与否都清理下载目录，避免 APK 积压；安装失败时退出码非 0
Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Host "CLEANED=$tmp"
