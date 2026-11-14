# TurboDL 3.4.0

Gestor de descargas multipropósito para Android, escrito **100 % en código
original Kotlin** (con pequeños analizadores Java puros para MP4/M3U/MPD y un
motor de bloqueo de anuncios en Rust vendido como AAR).
Inspirado en la funcionalidad de FileCentipede, sin ninguna relación con sus
autores ni su código. Gratis, sin anuncios, sin suscripciones, sin límites.

## Novedades de la 3.4.0

| Área | Qué hay de nuevo |
|---|---|
| Bloqueador de anuncios | Motor **adblock-rust de Brave** (JNI nativo, vendido en `libs/adblock-0.1.3.aar`): EasyList + EasyPrivacy completas (~3,5 MB de reglas), **filtrado cosmético** (oculta los huecos de anuncios), scriptlets y `$redirect`. Cada comprobación es nativa (microsegundos). El motor se serializa a `engine.bin`: los arranques siguientes deserializan en milisegundos. Si el motor nativo fallara, cae a la lista compacta de hosts anterior. Ajustes → «Actualizar listas de filtros» descarga listas frescas de easylist.to |
| Refresco de enlace caducado | Los CDNs firmados (YouTube/Facebook…) dan **403/410 al vencer la URL**. Ahora la tarea guarda la **página de origen**: al caducar se reabre la página en un **WebView oculto**, se recaptura el enlace (emparejando por itag/mime/extensión/host) con cookies renovadas y **reanuda desde el byte ya descargado** sobre el `.part`. Auto una vez + botón manual «🔄 Refrescar enlace» en el menú de la tarea (estilo 1DM) |
| Pre-creación del archivo | Antes de descargar: **comprueba el espacio libre** (`usableSpace` vs tamaño+margen) y **pre-asigna el fichero** con su tamaño final (`setLength`), mostrando «creando archivo…». Sin más fallos de «sin espacio» a mitad de una descarga de 900 MB |
| Botón de recarga | Icono **⟳ junto a la barra de direcciones** (donde estaba el menú): recarga con un toque; mientras la página carga pasa a **✕ = detener** |
| Nueva pestaña en la cuadrícula | El conmutador de pestañas añade una **tarjeta «＋»** al final (pulsación larga: pestaña privada), además de la fila inferior |
| Nombres descriptivos | Estilo yt-dlp: **«Sitio - Título.ext»** (p. ej. `Youtube - Mi Vacaciones en Cancun.mp4`) usando el título de la página que olfateó el medio (`document.title` capturado en el momento del sniffeo), sitio sacado de la página de **origen** (no del CDN), saneado y recortado. Antes: `videoplayback?itag=137…` |

**Nota de requisitos:** minSdk pasa de 21 a **26 (Android 8.0)**, requisito del
motor adblock-rust; Android 5.x–7.x ya no tiene WebView actualizable ni cuota
relevante.

### Integración técnica del bloqueador
- AAR de Maven Central vendido offline; metadato `minCompileSdk` ajustado de 37
  a 34 (el wrapper solo usa `android.net.Uri`, presente desde API 1).
- El AAR trae metadatos de Kotlin 2.4 que el compilador 1.9 del proyecto no
  lee: la integración (`AdBlockEngine.java`) está escrita en Java puro y el
  AAR se reempaqueta sin `.kotlin_module`.
- Reglas ProGuard añadidas para las entradas JNI (`org.mlm.adblock.**`).

## Novedades de la 3.3.0

| Área | Qué hay de nuevo |
|---|---|
| Sondeo concurrente | La detección de **duración/tamaño arranca al snifear** (en background, 4 sondas en paralelo): al abrir la lista los datos ya están — y si no, la fila se **actualiza en vivo**. Antes solo sondeaba al abrir el diálogo, de uno en uno |
| Sondeo rápido | Un MP4 se resuelve con **1 petición** (rango 256 KB que da tamaño, mime y `moov` a la vez; antes eran 2-4 conexiones). Las variantes HLS se resuelven **en paralelo** (8 peticiones simultáneas; testeado: 5 variantes en ~120 ms frente a ~4,5 s). Timeouts 12 s → 8 s |
| YouTube | Las URLs `googlevideo.com/videoplayback` **traen duración y tamaño en sus propios parámetros** (`dur=`/`clen=`): duración, tamaño, calidad (`itag`) y «solo audio»/«sin audio» con **cero peticiones**. Detecta los manifiestos DASH de YouTube (`/api/manifest/dash`) |
| Grandes sitios | Detección **por host además de por extensión** (`DETECT_RE`): Facebook (`fbcdn`), X/Twitter (`video.twimg`), TikTok (`tiktokcdn`), Instagram (`cdninstagram`), Reddit (`v.redd.it`), Vimeo — funcionan aunque la URL no termine en `.mp4` |
| Deduplicación | Los segmentos de YouTube por rango (`range=0-…`, `rn`, `rbuf`) se normalizan: un vídeo = **1 ítem**, no decenas |

### Verificación (harness JVM con los parsers reales del APK)
- MP4: duración exacta 10 s y resolución 640×360 desde la cabecera, tamaño por `Content-Range` — 1 petición.
- HLS: master + 5 variantes con duración en paralelo (119 ms).
- DASH: duración 634 s y 10 representaciones.
- googlevideo: `dur`/`clen` parseados; dos peticiones con distinto `range` → 1 ítem.
- `DETECT_RE`: 8 casos reales (Facebook/X/Reddit/Vimeo/YouTube) sin falsos positivos.

## Novedades de la 3.2.0

| Área | Qué hay de nuevo |
|---|---|
| Tira de pestañas | **Siempre visible arriba**, como un navegador de escritorio: fila fija `[🗂 n] [tarjetas…] [＋]`. El contador abre el conmutador en cuadrícula y **＋** crea una pestaña. Ya no hay nada de pestañas abajo: retirado el botón 🗂 de la barra inferior y las entradas de pestañas del menú (⋮) |
| Gestos de swipe | **Arrastra una tarjeta en horizontal para cerrarla**: se desliza con desvanecido y se cierra al pasar el umbral (si no, vuelve a su sitio con animación). **Desliza hacia abajo sobre la tira** o sobre una tarjeta para **crear una pestaña nueva**, con aviso háptico |
| Robustez | Los índices de las tarjetas se recalculan al actuar: cerrar varias seguidas no cierra la pestaña equivocada |

## Novedades de la 3.1.0

| Área | Qué hay de nuevo |
|---|---|
| Pestañas | **Un WebView vivo por pestaña**: cambiar de pestaña es instantáneo y sin recargas; la página sigue su carga incluso en segundo plano. Límite de 8 |
| Barra de pestañas | **Tira superior deslizable** (aparece con 2+ pestañas): mini-tarjetas con el título de la página, botón **×** para cerrar cada una y **＋** para crear; toque = cambiar, pulsación larga = cerrar / cerrar las demás |
| Conmutador | Botón **🗂** abre el conmutador en **cuadrícula con miniaturas** de cada pestaña (captura real de la página), título, host y **✕** por tarjeta; botones «+ Nueva», «+ Privada» y «Cerrar todas» |
| Enlaces | **Pulsación larga sobre un enlace** → abrir en **nueva pestaña (primera fila)**, en **segundo plano** 🌙 (con aviso), descargar o copiar. Enlaces `target=_blank` / `window.open` se abren en pestaña nueva automáticamente |
| Ajustes | El contador del bloqueador de anuncios es **por pestaña**; «Recargar» y «Modo escritorio» se aplican a todas las pestañas abiertas |

### Notas de la 3.1.0
- Las pestañas se restauran entre sesiones (las privadas no).
- El WebView de una pestaña se crea bajo demanda: abrir TurboDL sigue siendo
  ligero aunque hubiera pestañas guardadas.

## Novedades de la 3.0.0

| Área | Qué hay de nuevo |
|---|---|
| Motor de descargas | **Reintentos automáticos** con espera progresiva (5 s, 15 s, 60 s); **solo Wi-Fi** (las descargas esperan a tener Wi-Fi); **reanudación tras reiniciar** (tareas a medias quedan pausadas y reanudables, con auto-resumen opcional) |
| Multimedia | **Reproductor interno** (hardware, sin salir de la app); **extraer audio** de un MP4 descargado a M4A; **subtítulos automáticos**: si el HLS/DASH declara pista de texto se guarda un `.vtt` junto al video |
| Verificación | Al terminar un MP4 se comprueba el índice (`ftyp`+`moov`) y se avisa en Detalles si el archivo no se podrá desplazar bien en el reproductor |
| Navegador | **Bloqueador de anuncios/rastreadores** por host (contador por página); **marcadores ⭐**; **pestañas privadas** 🕵 (sin historial ni persistencia); **modo lector** 📖; **escaneo profundo** de medios de la página; **congelado** del estado de pestañas a partir de 5 |
| Interfaz | Tema **AMOLED** (negro puro); **vista compacta** de la lista; **notificación con «Reanudar todo»** cuando hay pausadas; **portapapeles inteligente** (enlace copiado → ofrecer descargar) |
| Gestión | Panel de **uso de almacenamiento** (mayores archivos, total, espacio libre); **mover descarga a otra carpeta** (SAF, copia+borra); **limpieza automática** de temporales de hace +48 h |
| Seguridad | **Bloqueo con PIN** (guardado solo como SHA-256); **ocultar descargas de la galería** (.nomedia) |
| Tamaño/RAM | **R8/minify + shrinkResources** activados (APK y memoria más pequeños); sin dependencias pesadas nuevas |

### Notas de la 3.0.0
- El PIN se pide al abrir la app; se desactiva desde Ajustes › Seguridad.
- El bloqueador usa una lista compacta de hosts publicitarios y de rastreo;
  **no** toca CDNs de video para no romper reproducciones.
- Los subtítulos se concatenan cuando los segmentos son WebVTT planos; los
  DASH con texto empaquetado en MP4 (wvtt) se avisan como no convertibles.

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
