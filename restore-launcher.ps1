# Vuelve al launcher de fabrica y quita este.
adb shell pm enable com.mitv.tvhome.atv
adb shell pm enable com.google.android.tvlauncher
adb shell cmd package set-home-activity com.mitv.tvhome.atv/.TvLauncher 2>$null
adb shell pm uninstall com.fa.tvlauncher
adb shell input keyevent KEYCODE_HOME
# Para reactivar tambien todo lo que se deshabilito: .\restore.ps1
