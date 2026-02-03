#!/usr/bin/env powershell
# VideoShuffle Deploy Script - Build and Install to TV Boxes

$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$adb = "C:\Users\ARCHAY WAKODIKAR\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$apkPath = "$ProjectRoot\app\build\outputs\apk\release\app-release.apk"
$devices = @("100.64.0.15:33385", "100.64.0.22:45021")

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "VideoShuffle Build & Deploy" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

# Step 0: Connect to devices via ADB
Write-Host "`n[0/4] Connecting to devices..." -ForegroundColor Yellow
foreach ($device in $devices) {
    Write-Host "Connecting to $device..." -ForegroundColor Gray
    & $adb connect $device
}

Write-Host "`nConnected devices:" -ForegroundColor Yellow
& $adb devices

# Check for unauthorized devices
$deviceList = & $adb devices
if ($deviceList -match "unauthorized" -or $deviceList -match "offline") {
    Write-Host "`n[!] AUTHORIZATION REQUIRED:" -ForegroundColor Red
    Write-Host "    Please check the TV boxes and accept the USB debugging prompt." -ForegroundColor Yellow
    Write-Host "    If any device is offline, reconnect ADB on that device." -ForegroundColor Yellow
    Write-Host "    Press Enter once you've accepted on all devices..." -ForegroundColor Yellow
    Read-Host
    
    Write-Host "`nChecking device status..." -ForegroundColor Yellow
    & $adb devices
}

# Step 1: Build APK
Write-Host "`n[1/4] Building APK..." -ForegroundColor Yellow
Push-Location $ProjectRoot
& .\gradlew assembleRelease
Pop-Location

if (-not (Test-Path $apkPath)) {
    Write-Host "[ERROR] APK not found at $apkPath" -ForegroundColor Red
    exit 1
}

Write-Host "[OK] APK built successfully" -ForegroundColor Green

# Step 2: Install to TV Boxes
foreach ($device in $devices) {
    Write-Host "`n[2/4] Installing to $device..." -ForegroundColor Yellow
    
    # Force stop the app before installing to prevent "fail to install" errors
    Write-Host "Stopping existing app on $device..." -ForegroundColor Gray
    & $adb -s $device shell am force-stop com.arc.videoshuffle

    # Uninstall to avoid signature mismatch between debug/release
    & $adb -s $device uninstall com.arc.videoshuffle | Out-Null

    & $adb -s $device install $apkPath
    
    if ($LASTEXITCODE -eq 0) {
        Write-Host "[OK] Installed successfully to $device" -ForegroundColor Green
    }
    else {
        Write-Host "[FAIL] Failed to install to $device" -ForegroundColor Red
    }
}

# Step 3: List devices
Write-Host "`n[3/4] Connected devices:" -ForegroundColor Yellow
& $adb devices

# Step 4: Launch app on devices
Write-Host "`n[4/4] Launching app..." -ForegroundColor Yellow
foreach ($device in $devices) {
    Write-Host "Starting VideoShuffle on $device..." -ForegroundColor Gray
    & $adb -s $device shell am start -n com.arc.videoshuffle/.MainActivity
}

Write-Host "`n========================================" -ForegroundColor Cyan
Write-Host "Deploy Complete!" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
