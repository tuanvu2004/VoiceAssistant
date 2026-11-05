# Build, cài lên điện thoại và mở app bằng một lệnh: .\run.ps1
Set-Location $PSScriptRoot
.\gradlew installDebug
if ($LASTEXITCODE -eq 0) {
    adb shell am start -n com.tuanvu.voiceassistant/.MainActivity
}
