# Launcher estilo Apple TV para Mi TV Stick (MDZ-24-AA)

Launcher nativo en Java, sin dependencias (solo el framework de Android). Fondo negro,
dock redondeado, resaltado de cristal que salta entre mosaicos, resplandor del color del
banner, reloj fino arriba a la izquierda y botones Entradas / Wi-Fi / Ajustes.
Tipografia Outfit (licencia OFL, ver `app/src/main/assets/fonts/OFL.txt`).

## Compilar
**Opcion A, Android Studio:** abre esta carpeta, espera el sync y usa
*Build > Build Bundle(s) / APK(s) > Build APK(s)*. El APK queda en
`app\build\outputs\apk\debug\app-debug.apk`.

**Opcion B, sin instalar nada:** sube la carpeta a un repo de GitHub; la pestana *Actions*
ejecuta `build-apk` y deja `app-debug.apk` como artefacto descargable.

## Instalar y probar
1. `.\install.ps1` instala el APK, concede los permisos del nombre Wi-Fi, lo abre y guarda una captura.
2. Mira la captura y prueba con el control: flechas, OK, Inputs, Settings.
3. Solo si todo va bien: `.\set-home.ps1` lo fija como Home.

## Volver atras
- `.\restore-launcher.ps1` reactiva los launchers de fabrica y desinstala este.
- `.\restore.ps1` (generado durante la limpieza) reactiva las apps que deshabilitaste.
- Reactivar una app concreta: `adb shell pm enable <paquete>`.

## Personalizar
- **Orden y apps del dock:** `app/src/main/res/values/config.xml`, lista `dock_apps`
  (paquetes, de izquierda a derecha). `columns` (4 a 7) fija los espacios del dock y las
  columnas de la cuadricula.
- **Ocultar apps de la cuadricula:** lista `hidden_apps` en el mismo archivo.
- **Fondo:** negro puro por diseno (ahorra memoria y es el estilo Apple TV). No hay fondo
  de pantalla configurable.
- Tras cambiar `config.xml`, recompila e instala de nuevo.

## Antes de terminar
Apaga la depuracion USB / de red en el Stick y reinicia, para no dejar el puerto 5555 abierto.
