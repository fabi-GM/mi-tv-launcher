# Instala el launcher en el Mi TV Stick SIN fijarlo todavía como Home.
# Uso (PowerShell, con el Stick conectado por adb):   .\install.ps1
param([string]$Apk = "$PSScriptRoot\app\build\outputs\apk\debug\app-debug.apk")

if (-not (Test-Path $Apk)) { Write-Error "No encuentro el APK: $Apk (compila primero, ver README)"; exit 1 }

adb install -r $Apk
# Permisos para leer el nombre de la red Wi-Fi (opcional; sin ellos el boton dice solo "Wi-Fi")
adb shell pm grant com.fa.tvlauncher android.permission.ACCESS_COARSE_LOCATION
adb shell pm grant com.fa.tvlauncher android.permission.ACCESS_FINE_LOCATION

# Abre el launcher como una app normal para probarlo
adb shell am start -n com.fa.tvlauncher/.MainActivity
New-Item -ItemType Directory -Force -Path "$PSScriptRoot\shots" | Out-Null
Start-Sleep -Seconds 3
adb exec-out screencap -p | Set-Content -Encoding Byte "$PSScriptRoot\shots\01-launcher.png"
Write-Host "Listo. Captura en shots\01-launcher.png. Aun NO es tu Home."
