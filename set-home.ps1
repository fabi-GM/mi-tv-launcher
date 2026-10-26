# Fija el launcher como Home. Ejecutalo SOLO cuando install.ps1 se vea bien.
adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
adb shell cmd package set-home-activity com.fa.tvlauncher/.MainActivity
adb shell input keyevent KEYCODE_HOME
Start-Sleep -Seconds 2
# Debe nombrar com.fa.tvlauncher; si dice otra cosa, el cambio no tomo efecto:
adb shell dumpsys activity activities | Select-String "mFocusedApp|mResumedActivity"
