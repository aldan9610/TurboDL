# TurboDL 2.1.0

Gestor de descargas multipropósito para Android, escrito **100 % en código
original Kotlin** (con pequeños analizadores Java puros para MP4/M3U/MPD).
Inspirado en la funcionalidad de FileCentipede, sin ninguna relación con sus
autores ni su código. Gratis, sin anuncios, sin suscripciones, sin límites.

## Novedades de la 2.1.0

| Área | Qué hay de nuevo |
|---|---|
| Pestañas | Hasta **8 pestañas** en el navegador (conmutador 🗂, nueva/cerrar/todas, persistencia entre sesiones). Solo vive **un WebView** activo: el resto guarda su back/forward y URL, así la RAM no crece con cada pestaña |
| Historial de páginas | Registro de páginas visitadas (SQLite, máx. 500) con visor: abrir, eliminar una entrada (pulsación larga) o borrar todo |
| Vídeos DASH/HLS que no se podían desplazar (seek) | Tras unir los segmentos el MP4 se **normaliza con remux** (moov clásico indexado, sin recodificar): el reproductor ya puede saltar a cualquier punto, la duración es correcta y no vuelve al inicio |
| Porcentaje de progreso falso | Corregido: el total va siempre en **bytes**; si el stream no los declara, el progreso se calcula por **segmentos completados** (seg 12/240 · 34 %), nunca mezclando unidades |
| Audio separado automático | DASH: si el manifiesto tiene pista de audio, se descarga y **combina por defecto**. HLS: el grupo `EXT-X-MEDIA TYPE=AUDIO` del master se detecta solo |

## Novedades de la 2.0.0

| Área | Qué hay de nuevo |
|---|---|
| Detección de medios | Duración y tamaño (real o ≈ estimado) de cada video detectado, resolución/calidades del stream (HLS master y DASH) y **pistas de audio separadas detectadas** |
| Descarga A/V | "Descargar solo audio", "Elegir calidad" y **fusión audio+video** automática en un único MP4 (DASH fMP4 y HLS fMP4, vía org.mp4parser vendido en `app/libs`); si la fusión falla, las pistas quedan por separado |
| Ordenación | Lista de detectados y lista de descargas ordenables: tamaño ↑↓, duración ↑↓, tipo, nombre, fecha, estado |
| Cola | Todo entra a una **cola** con N descargas simultáneas (1-4, configurable); "Descargar ahora" o "Añadir a la cola"; pausar, reanudar, reencolar y salir de la cola a demanda |
| Permisos | La app solicita en tiempo de ejecución las notificaciones (Android 13+) y el almacenamiento (Android 6-9, cuando hace falta); en 10+ se usa SAF, sin permisos |
| Carpeta de descarga | Elección de carpeta con el **explorador del sistema (SAF)**, incluidas carpetas de la **tarjeta SD**; o la carpeta privada de la app |
| Servicio/Notificación | Notificación descriptiva (nombre, %, MB, velocidad, barra de progreso) con botones **«Pausar todo»** y **«Detener servicio»**; el servicio se apaga solo al terminar y existe **«Salir (cerrar del todo)»** que mata la app |
| Navegador | **Buscador elegible** (DuckDuckGo, Google, Bing, Brave, Startpage, Ecosia, Yandex, Wikipedia, Mojeek o URL personalizada), **perfiles de navegación** (Android Chrome, Escritorio Windows/Linux/macOS, iPhone/iPad, Googlebot, Wget, UA personalizado) y **modo escritorio ↔ móvil** con un botón |
| Accesos e historial | Pantalla de **accesos directos** editable (YouTube, Facebook, Instagram, X, TikTok… añadir/quitar con pulsación larga), **historial de búsqueda** con autocompletado y borrado, y limpieza de cookies/caché |
| Interfaz | Tema **claro/oscuro**, **tamaño de fuente** (80-160 %), **tipografía** (6 familias), cajón lateral (☰), menú de puntos (⋮), barra inferior de acciones |
| Bajos recursos | **minSdk 21 (Android 5.0)**, sin AndroidX, pools acotados (2-4 hilos de red, 1 de sondeo), buffers pequeños, WebView único, servicio que se apaga solo |

## Requisitos

- Android 5.0 (API 21) o superior
- 1 GB de RAM es suficiente (diseñado para ello)

## Funcionalidades (mantienen la v1)

| Protocolo | Detalle |
|---|---|
| HTTP/HTTPS | Multihilo por rangos (1-16 conexiones), pausa/reanudación real, reintentos, cabeceras/cookies personalizadas, límite global de velocidad |
| FTP/FTPS | Descarga con reanudación (REST), FTPS explícito (AUTH TLS) |
| HLS (m3u8) | Master playlists con elección de calidad, AES-128, BYTERANGE, fMP4 (EXT-X-MAP), streams en vivo, salida .ts/.mp4 |
| DASH (mpd) | Representación elegible por calidad, audio separado combinable, init+segmentos → .mp4; DRM rechazado con mensaje claro |
| BitTorrent | Magnet y .torrent (jlibtorrent/libtorrent 1.2, nativos incluidos por ABI), sigue sembrando al completar |
| thunder:// flashget:// qqdl:// | Decodificación nativa |
| Navegador integrado | WebView ligero con detección (XHR, fetch, `<video>`, `<audio>`, MutationObserver, descargas del WebView), cookies y User-Agent de la sesión pasados a la descarga, captura de magnets |
| Herramientas | Combinador de archivos, MD5/SHA-1/SHA-256, codificador URI/Base64 |
| General | Recibir URLs compartidas, abrir magnets/torrents desde otras apps, notificación de progreso, categorías con carpetas (o subcarpetas en la carpeta SAF elegida) |

## Compilar

### Con internet
```bash
gradlew assembleRelease        # Linux/macOS
gradlew.bat assembleRelease    # Windows
```
El APK firmado queda en `app/build/outputs/apk/release/TurboDL-2.0.0.apk`.

### Sin internet (Windows)
1. `scripts_compilacion\prepare_offline.bat` **una sola vez con internet**
   (deja cacheados Gradle, AGP y Kotlin; las dependencias de la app ya viajan
   vendidas en `app\libs\` — jlibtorrent, isoparser, muxer, slf4j —
   slf4j — y los nativos en `app\src\main\jniLibs\`).
2. Después, sin conexión: `scripts_compilacion\build_offline.bat`
   (equivale a `gradlew.bat --offline assembleRelease` y copia el APK a `release\`).

Requisitos del equipo: JDK 17+ (`JAVA_HOME`) y el SDK de Android (`ANDROID_HOME`)
con `platforms;android-34` y `build-tools;34.0.0`.

> Nota honesta: Gradle/AGP no se pueden "vender" dentro del proyecto de forma
> práctica (cientos de MB); por eso la primera compilación necesita internet y
> las siguientes ya funcionan offline con `--offline`.

### Firmado
El keystore `app/turbo.keystore` viaja en el proyecto (contraseña
`turbodl2026`, alias `turbo`) para poder regenerar el APK de prueba.

## Estructura

```
app/src/main/java/com/turbodl/
  App.kt                    Application (motor único)
  db/Db.kt                  SQLite de tareas
  model/Task.kt             Modelo de tarea + estados
  engine/
    Engine.kt               cola, planificador, notificación, destinos
    Sink.kt (en SafStore.kt) escritura local o SAF (RandomAccessFile/PFD)
    SafStore.kt             DocumentsContract sin dependencias (SD incluida)
    Downloader.kt           base HTTP con cabeceras de perfil
    HttpDownloader.kt       multihilo + reanudación
    HlsDownloader.kt        HLS + calidad + audio separado + fusión
    DashDownloader.kt       DASH + representación + fusión
    FtpDownloader.kt        FTP/FTPS
    TorrentDownloader.kt    BitTorrent
    Merge.kt                fusión MP4 audio+video (org.mp4parser)
    MediaProbe.kt           sondeo de duración/tamaño/calidades
  ui/                       MainActivity (cajón + cola), BrowserActivity,
                            SettingsActivity, ToolsActivity
  util/
    Kit.kt  Schemes.kt  Profiles.kt  Ui.kt
    Mp4.java  M3u.java  Mpd.java    (analizadores puros, probados en JVM)
  service/DownloadService.kt  primer plano con acciones y autoapagado
  provider/MiniFileProvider.kt  abrir archivos con otras apps
```

## Privacidad
Sin telemetría, sin anuncios, sin permisos raros. Las cookies del navegador
se usan solo para completar las descargas de la página en la que estás.
