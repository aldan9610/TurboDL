# TurboDL 4.6

Gestor de descargas multipropósito para Android, escrito **100 % en código
original Kotlin** (con pequeños analizadores Java puros para MP4/M3U/MPD y un
motor de bloqueo de anuncios también en Kotlin, sin código nativo).
Inspirado en la funcionalidad de FileCentipede, sin ninguna relación con sus
autores ni su código. Gratis, sin anuncios, sin suscripciones, sin límites.

## Novedades de la 4.6 — bóveda de credenciales estilo KeePassXC + códigos OTP para 2FA

La 4.6 añade un **gestor de contraseñas** integrado con **generación de
códigos OTP** (TOTP/HOTP/Steam) para autenticación de dos factores,
accesible desde el navegador y desde Herramientas.

### 🔐 Bóveda de credenciales
- Guarda título, usuario, **contraseña cifrada** (AES-256-GCM,
  AndroidKeyStore), URL y notas por entrada.
- Copia usuario/contraseña/URL/OTP al portapapeles con un toque.
- Cada usuario del sandbox tiene su propia bóveda.

### 🔑 Códigos OTP (2FA)
- **TOTP** (RFC 6238, 30s), **HOTP** (RFC 4226, contador), **Steam Guard**.
- Algoritmos HMAC: SHA1 (estándar), SHA256, SHA512.
- Acepta el secret en **Base32**, **hex** (`hex:`), texto plano
  (`plain:`), o pega un **`otpauth://` URI** completo (el de los QR) y
  la app lo descompone solo.
- Implementación 100% Kotlin, sin dependencias. Validada contra los
  vectores oficiales RFC 6238/4226.

### ⚡ Optimización de recursos (lo que pediste)
> *"no generar estos códigos siempre sino cuando se entre a la
> interfaz que los muestra"*

- La **lista no calcula OTP** — solo al pulsar **"Ver código"**.
- **Solo una entrada** muestra OTP a la vez (evita N timers).
- Recálculo **solo en el límite del step** (cada 30s), no cada tick.
- `onPause` y reciclado de vista cancelan el timer (sin timers zombis).

### 📦 Internas
- versionCode 25, versionName 4.6. Misma firma que 4.3/4.4/4.5. **145
  tests JVM pasan** (0 fallos): 129 + 16 nuevos de OTP (vectores RFC).

---

## Novedades de la 4.5 — proxy de red para todo el tráfico (HTTP, HTTPS-proxy, SOCKS4, SOCKS5)

La 4.5 añade un proxy configurable que enruta **TODO el tráfico** de
TurboDL —navegador y motor de descargas— a través de un proxy de red.

### 🛰 Tipos soportados
- **HTTP** — proxy HTTP clásico (CONNECT para HTTPS).
- **HTTPS-proxy** — proxy que habla TLS (RFC 2817).
- **SOCKS4** — sin credenciales (SOCKS4 no las admite).
- **SOCKS5** — con auth opcional (usuario/contraseña).
- **Ninguno** — conexión directa (por defecto).

### 🌐 Qué se enruta por el proxy
TODO: navegador (WebView vía `ProxyController.setProxyOverride`, Android
9+), descargas HTTP/HTTPS/HLS/DASH, sonda de medios, subtítulos,
metadatos .torrent, favicons, actualizaciones y FTP (vía SOCKS; FTP no
admite proxy HTTP).

### 🔐 Credenciales
HTTP, HTTPS-proxy y SOCKS5 admiten usuario/contraseña; la contraseña se
guarda cifrada (AES-256-GCM, AndroidKeyStore). SOCKS4 no las admite.

### ⚙️ UI
Ajustes → **Red** → 🛰 **Proxy**: selector de tipo + host + puerto +
usuario + contraseña. Al guardar se reaplica en caliente (sin reiniciar).

### 📦 Internas
- versionCode 24, versionName 4.5. Misma firma que 4.3/4.4. 129 tests JVM
  pasan (0 fallos). Nueva dependencia `androidx.webkit:webkit:1.11.0`.

---

## Novedades de la 4.4 — cambio de usuario funcional (sandbox real, estilo lfbrowser)

La 4.4 hace que el cambio de usuario sea **real**: cada usuario tiene su
propio sandbox de cookies/LocalStorage/caché del WebView, y cambiar de
cuenta reinicia la app en la sandbox del usuario elegido (como lfbrowser).

### 🔄 El bug de "solo me dice un mensaje"
El cambio existía pero era engañoso: `restartApp` hacía `killProcess` y
Android reusaba el proceso → `applyDataDirSuffix` no cambiaba el sufijo →
las cookies seguían siendo del usuario anterior. Ahora `restartApp` usa
`AlarmManager` + `System.exit(0)` (reciclado real del proceso) y vuelca las
cookies del usuario saliente antes de salir.

### 👥 UI de usuarios estilo lfbrowser
Diálogo a pantalla completa: título "USUARIOS" + acción "AGREGAR" (azul),
lista de filas (avatar circular + identificador subrayado + radio button
exclusivo) y botones CANCELAR (gris) / CAMBIAR (naranja). Mantén pulsada una
fila para editar (renombrar/cambiar email) o eliminar. Cada usuario puede
identificarse por su correo.

### 🔐 Aislamiento real
Cada usuario no-principal monta `app_webview/usr_<id>/` propio → cookies,
LocalStorage, IndexedDB y caché separados. Dos cuentas de Google pueden
estar logueadas a la vez (cada una en su sandbox) y cambiar entre ellas
con un toque.

### 📦 Internas
- versionCode 23, versionName 4.4. Misma firma que 4.3 (actualización sin
  desinstalar). 129 tests JVM pasan (0 fallos). `allowBackup=false`.

---

## Novedades de la 4.3 — notas del navegador funcionales (CRUD) + clics del menú arreglados

La 4.3 hace que las notas del navegador sean completamente funcionales (CRUD)
y arregla el bug que impedía abrir cualquier opción del menú.

### 🗒 Notas con CRUD completo
La BD ya tenía el CRUD, pero el usuario no podía abrir Notas porque el
`GestureDetector` del swipe del menú bloqueaba los clics. Arreglado eso, se
reescribe la UI: tarjetas con botones inline (✏ Editar, 📋 Copiar, ↗ Compartir,
🗑 Borrar), caja de búsqueda en vivo y botón ＋ Nueva visible. La lista se
refresca tras crear/editar/borrar, y borrar pide confirmación.

### 🖱 Clics del menú restaurados
El `GestureDetector` de la 4.2.3 consumía `ACTION_DOWN` y bloqueaba todos los
clics en las celdas del menú (Notas, Marcadores, Ajustes…). Se sustituye por un
`VelocityTracker` manual que **nunca consume el evento**: el ScrollView y sus
hijos reciben el toque completo y los clics funcionan. El swipe sigue siendo
inmediato (umbral 300 px/s + scrollTo).

### 🧪 Test de regresión del CRUD
Nuevo test JVM `NotesDbTest` (7 casos) con Robolectric, contra SQLite real
sin dispositivo. Valida create/get, listado, update, delete, búsqueda y
preview. Añade `NotesDb.clearAllNotes()` para aislar tests.

### 📦 Internas
- versionCode 22, versionName 4.3. Misma firma que 4.2.4/4.2.5 (actualización
  sin desinstalar). 129 tests JVM pasan (0 fallos).

---

## Novedades de la 4.2.5 — flujo de navegación corregido (btnHome y btnMainHome)

Reescribe el comportamiento de los dos botones de la barra inferior del navegador
y arregla el bug de que la URL limpiada "revivía" sola.

### 🏠 btnHome — dos toques, como debe ser
- **1er toque**: limpia la pestaña actual (about:blank + vacía URL y barra). Te
  quedas en el navegador con todo limpio. No va a la vista principal.
- **2º toque** (sin nada que limpiar): va a la **vista principal** de la app.

### ⬛ btnMainHome — siempre directo a la vista principal
- Ya no es un toggle (mostrar/ocultar). **Siempre** va a la vista principal en
  un solo toque, sin tocar la pestaña actual (la URL cargada se conserva).

### 🔒 La URL limpiada ya no "revive"
Antes, tras limpiar con Home, la URL descartada volvía a cargarse sola por culpa
de `onPageStarted`/`onPageFinished`/`ensureWv`/`syncActive`, que reescribían
`t.url` con la URL vieja. Nuevo flag `Tab.cleared`: cuando btnHome limpia, se
marca `true` y esos cuatro puntos dejan de tocar `t.url`. Se resetea en `load()`
cuando el usuario carga una URL nueva a propósito.

### 📦 Internas
- versionCode 21, versionName 4.2.5. Misma firma que 4.2.4 (actualización sin
  desinstalar). 122 tests JVM pasan (0 fallos).

---

## Novedades de la 4.2.4 — firma estable para el APK de debug (actualizaciones sin desinstalar)

Corrige el error que te obligaba a desinstalar la versión antigua antes de
instalar la nueva (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`).

### 🔑 Causa y solución
El APK de debug se firmaba con la `debug.keystore` por defecto de Android, que
se **regenera** cuando el sandbox de compilación se recicla; dos APKs sucesivos
tenían firmas distintas y Android los rechazaba como actualización. A partir
de la 4.2.4, el buildType **debug** también se firma con `turbo.keystore`
(la misma keystore del release, estable y en el repo). Verificado con
`apksigner`: todos los APKs comparten la huella SHA-256
`10bb4b2b…d1631238b`.

### 📲 Qué cambia para ti
- A partir de la 4.2.4, podrás actualizar la app directamente instalando el APK
  nuevo sobre el anterior, conservando accesos, pestañas y ajustes.
- **Una sola excepción**: si tienes una versión anterior a la 4.2.4, tendrás
  que desinstalarla **una última vez** para pasar a la 4.2.4. A partir de ahí,
  todas las futuras se actualizarán sin desinstalar.

---

## Novedades de la 4.2.3 — página de inicio sin logo, swipe inmediato, botón de vista principal

La 4.2.3 reorganiza la página de inicio del navegador, hace que el swipe entre
paneles sea muy sensible e inmediato, y añade un botón para volver rápido a la
vista principal.

### 🧹 Página de inicio sin logo ni buscador duplicado
Eliminado el icono de 64dp y la barra `homeSearch` del `homeOverlay`. Ambos eran
redundantes con la barra superior `editUrl`. Sin ellos, el panel de accesos
directos gana todo el espacio vertical (~120dp extra en pantallas pequeñas).

### 📱 Swipe muy sensible e inmediato (GestureDetector + scrollTo)
El esquema anterior (vigilar scrollX + smoothScrollTo) era lento. Ahora cada
pager usa un `GestureDetector` que intercepta el `onFling`: si la velocidad
horizontal supera 300 px/s, salta de página INMEDIATAMENTE con `scrollTo()` (sin
animación). El umbral al soltar el dedo baja al 10% del ancho (antes 25%).
Aplicado a paneles de accesos y al menú del navegador.

### 🏠 Botón de volver a la vista principal
Nuevo botón `btnMainHome` (▶) en la barra inferior, entre Home y pestañas. Va
directo e inmediato a la vista principal en un solo toque, sin tocar la
pestaña actual (a diferencia de Home que reinicia la búsqueda).

### 📦 Internas
- versionCode 19, versionName 4.2.3.
- Nuevas constantes: `FLING_VEL_MIN = 300f`, `SNAP_THRESHOLD_FRACTION = 0.10f`.
- 122 tests JVM pasan (0 fallos).

---

## Novedades de la 4.2.2 — crash del menú arreglado, swipe sensible, iconos sin recorte, "TurboDL 3" corregido, Home con dos toques

La 4.2.2 corrige el crash al abrir el menú (`IllegalArgumentException: row
indices exceed row count`), el swipe que no respondía a movimientos ligeros,
los iconos recortados en la BLU C5L Max, el "TurboDL 3" residual y el Home que
dejaba la app atascada en el navegador integrado.

### 🐛 Crash al abrir el menú (H9, arreglado)
La página 3 del menú tenía 11 celdas pero el GridLayout solo admite 10 (2×5);
la celda 11 caía en `row=2`, que excede `rowCount`, y Android 11 cerraba la
app. Ahora el menú es dinámico: las celdas se reparten en sub-páginas de 10
automáticamente (11 celdas = 2 páginas, 25 = 3 páginas). Nunca se excede el
rowCount.

### 📱 Swipe sensible (umbral 25%)
El snap anterior solo disparaba en `ACTION_UP` y fallaba con flings. Ahora un
runnable vigila `scrollX` hasta que se detiene y entonces encaja la página,
con umbral bajado al 25% del ancho (antes 50%): basta un "leve movimiento"
para cambiar de panel. Aplicado a paneles de accesos y menú.

### ✏ Renombrar panel — botón ✏ visible
El renombrado existía (long-press) pero no era descubrible. Ahora hay un
botón ✏ al lado del título del panel; un toque abre el diálogo de renombrar.

### 🖼 Iconos sin recorte
`item_site` compactado (padding 10→4dp, icono 40→32dp, texto 12→11sp) con
`scaleType=centerInside` + `adjustViewBounds`. Los favicons ya no se recortan
en pantallas pequeñas (BLU C5L Max).

### 📛 "TurboDL 3" corregido
La cabecera de MainActivity y el diálogo "Acerca de" decían "TurboDL 3" /
"TurboDL 3.0.0". Ahora "TurboDL" / "TurboDL 4.2.2".

### 🏠 Home con dos toques
1er toque: descarta la página actual (about:blank), limpia la barra y muestra
la vista principal. 2º toque: oculta la vista principal y deja el campo listo
para una nueva búsqueda. Ya no se queda atascado en el navegador integrado.

### 📦 Internas
- versionCode 18, versionName 4.2.2.
- `MENU_PAGE_COUNT` (constante) → `menuPageCount` (propiedad calculada).
- 122 tests JVM pasan (0 fallos).

---

## Novedades de la 4.2.1 — crash del detector arreglado, swipe por página completa, paneles sin desbordamiento y renombrables

La 4.2.1 corrige el `CalledFromWrongThreadException` que cerraba la app al
detectar medios en una página (presente desde la 4.0.0) y trae los cambios
que pidió el usuario en la navegación de los paneles de accesos y del menú.

### 🐛 Crash al detectar medios (H8, arreglado)
`shouldInterceptRequest` corre en un hilo de red de Chromium y llamaba a
`updateDetectBtn()`, que toca vistas (`setText`, `setVisibility`). Android
lo prohíbe y lanza `CalledFromWrongThreadException`, cerrando la app.
Ahora `addDetected` envuelve la actualización del contador en
`runOnUiThread`, y `updateDetectBtn` tiene un guardameta para llamadas
desde hilos de red.

### 📱 Swipe por página completa (no scroll fluido)
- Paneles de accesos y menú: al soltar el dedo, la página "encaja" en la
  más cercana (snap), como un libro de páginas enteras.
- Implementado con `OnTouchListener` + `smoothScrollTo`, **sin añadir
  ninguna dependencia externa** (el build sigue siendo offline).

### 🗂 Paneles de accesos sin desbordamiento
- Cada sub-panel es un `GridLayout` de 4×3 = 12 huecos con peso 1f por
  celda: ocupan exactamente el área visible, sin scroll vertical interno.
- Si una categoría tiene más de 12 accesos, se reparte en sub-paneles
  paginados adicionales (13 → 2 paneles, 25 → 3 paneles).
- Las celdas vacías mantienen su tamaño para que la rejilla no se deforme.

### ✏ Renombrar paneles de accesos
- Pulsación larga sobre el título del panel abre el diálogo de renombrar.
- También accesible desde el menú contextual de cada acceso.
- Los títulos se persisten por usuario activo; "Restaurar accesos"
  también los limpia.

### 📦 Internas
- versionCode 17, versionName 4.2.1.
- `SitesPanelsTest` reescrito (15 casos). Los 119 tests JVM siguen pasando.

---

## Novedades de la 4.2.0 — crash al navegar arreglado, selector de bloqueadores, CRUD de notas y usuarios, visor de sandbox

La 4.2.0 resuelve la ronda de incidencias que reportó el usuario: el cierre
inesperado al navegar, las funcionalidades de notas y de cambio de usuario
rotas, y la imposibilidad de elegir bloqueador o ver qué hay almacenado en
cada sandbox.

### 🐛 Crash al navegar (arreglado)
Se diagnosticaron y arreglaron cinco causas de cierre inesperado:

- **H1 — `WebView.setDataDirectorySuffix` mal defendido**: el catch era
  `catch (e: Exception)` y no `Throwable`, y la segunda llamada (al cambiar
  de usuario si el proceso no moría a tiempo) dejaba el sandbox inconsistente
  y podía tirar la app. Ahora el sufijo se aplica de forma idempotente y
  robusta, con un log si no se puede cambiar en caliente (sin crash).
- **H2 — `ConcurrentModificationException` en `tabs`**: `shouldInterceptRequest`
  (hilo de red) iteraba la lista `tabs` con `firstOrNull` mientras el hilo
  UI la mutaba (`newTab`/`closeTab`). Ahora toda mutación e iteración pasa
  por un `tabsLock` dedicado.
- **H3 — `onCreateWindow` sin cancelar el `postDelayed`**: el `newTab` diferido
  500 ms se ejecutaba tras `onDestroy` y revienta al tocar un `target=_blank`.
  Ahora se usa un `Handler` compartido que se cancela en `onDestroy` y se
  chequea `isFinishing`/`isDestroyed`.
- **H4 — `handleExternalIntent` ejecutado dos veces**: tras exportar
  `BrowserActivity` en 4.1.0, `onCreate` y `onNewIntent` podían procesar el
  mismo intent. Ahora hay deduplicación por instancia y `load()` valida el
  scheme (rechaza `javascript:`, `data:`, etc.) en vez de propagar
  `IllegalArgumentException`.
- **H7 — `offerCredSave` abría `AlertDialog` sin chequear `isFinishing`**:
  si un formulario hacía submit justo al salir, `BadTokenException` mataba
  la app. Ahora se chequea el estado de la Activity y `Secrets.encrypt` va
  en try/catch.

### 🛡 Selector de bloqueadores de anuncios (nuevo)
Antes el motor cargaba solo EasyList + EasyPrivacy de forma fija. Ahora en
**Ajustes → Listas de filtros** el usuario elige qué listas activar mediante
un diálogo multi-choice, y se persiste en `SharedPreferences("cfg")`. La
selección se aplica al guardar (descarga + recarga del motor en segundo
plano). Catálogo disponible (todo 100 % Kotlin, sin código nativo):

- **EasyList** y **EasyPrivacy** (easylist.to) — por defecto.
- **AdGuard Base / Tracking / Annoyances** (`filters.adtidy.org`) — sintaxis
  EasyList, alternativa y complemento a EasyList.
- **Fanboy's Annoyances** (`fanboy.co.nz`) — banners de cookies, popups.
- **EasyList Spanish** (regional).
- **StevenBlack/hosts** (~80k hosts) — lista tipo `0.0.0.0 host`; se convierte
  a sintaxis EasyList (`||host^`) en tiempo de carga mediante un adapter
  puro Kotlin (`HostsAdapter`), sin tocar el motor.

Cuantas más listas, más RAM usa el motor (≈0,5–4 MB cada una); se avisa al
usuario en el diálogo. Hay botón "Restaurar por defecto" para volver a
EasyList + EasyPrivacy.

### 🗒 Notas — CRUD completo (arreglado)
El CRUD básico ya existía en la BD pero fallaba en la UI:

- **La lista no se refrescaba tras crear/editar** (sí tras eliminar) — el
  usuario creaba una nota y no la veía. Ahora el editor recarga la lista al
  cerrarse.
- **Búsqueda en vivo** por título o cuerpo (la BD ya la soportaba pero no
  se exponía). Caja de búsqueda en la cabecera de la vista de notas.
- `editNote` usa `getNote(id)` en vez de releer toda la tabla.
- Eliminar pide confirmación.

### 👥 Usuarios sandbox — CRUD completo + borrado real (arreglado)
- **Rename**: nuevo diálogo para renombrar un usuario existente (el id no
  cambia, así que todo el sandbox se conserva). Antes solo se podía crear
  y eliminar.
- **Eliminar ahora borra de verdad**: `Users.remove` antes solo quitaba el
  nombre de la lista JSON y los datos (cookies, localStorage, credenciales,
  historial, marcadores, accesos) quedaban huérfanos en disco. Ahora borra
  el directorio WebView del sandbox, las filas de `history`/`creds` en la
  BD y las claves `bookmarks_$id`, `history_$id`, `sitesP{0,1,2}_$id` en
  SharedPreferences. El diálogo informa de que el borrado es completo.
- **`restartApp` con fallback**: si `getLaunchIntentForPackage` devuelve
  null, no mata el proceso sin relanzar; cierra las tareas en su lugar.

### 👁 Visor de sandbox (nuevo)
En **menú → Privacidad → 👁 Sandbox** (o desde el diálogo de Usuarios),
se abre un visor de solo lectura con todo lo almacenado en el sandbox del
usuario activo:

- 🍪 **Cookies** del WebView (CookieManager) — número de entradas y nombres.
- 🗄 **localStorage** y 🗃 **IndexedDB** de la página activa (vía JS, solo si
  hay una página http(s) cargada; en otro caso se informa de que no se
  pueden inspeccionar sin una página abierta).
- 🕘 **Historial de navegación** (tabla `history` filtrada por usuario).
- 🔑 **Credenciales** guardadas + 🚫 hosts con "nunca preguntar" (tabla
  `creds`).
- ⭐ **Marcadores** y 📌 **accesos** (SharedPreferences namespaced por usuario).
- 📂 **Ruta del sandbox en disco** y tamaño total.

Todo es de solo lectura: para borrar hay que usar "Limpiar datos" o
eliminar al usuario.

### 📦 Internas
- versionCode 17, versionName 4.2.1.
- `AdblockCatalog.kt` (nuevo): catálogo de listas, persistencia de la
  selección, validación de ids y URLs.
- `HostsAdapter.kt` (nuevo): convierte listas tipo `0.0.0.0 host` a
  sintaxis EasyList (`||host^`) en una pasada O(n) sin regex.
- `Users.kt`: `applyDataDirSuffix` idempotente, `rename`, `remove` que
  borra sandbox, `wipeSandbox`, `restartApp` con fallback.
- `Db.kt`: `clearCreds(user)`, `historyCount`, `credsCount`, `neverCount`
  para el visor de sandbox.
- `NotesDb.kt`: `getNote(id)` para no releer toda la tabla.
- `AdBlockEngine.java`: `init` y `updateLists` ahora usan el catálogo y el
  adapter de hosts; el diagnóstico muestra las listas cargadas.
- Tests unitarios nuevos: `HostsAdapterTest` (14 casos), `AdblockCatalogTest`
  (13 invariantes), `UsersLogicTest` (3 invariantes). El build de debug y
  todos los tests JVM pasan.

## Novedades de la 4.1.0 — bugs del navegador, Adblock Plus, registro en el sistema y tests

La 4.1.0 corrige los cuatro bugs del navegador que reportó el usuario
(menú inferior, swipe de accesos, sugerencias persistentes y barra de
dirección), integra el motor de anuncios maduro de **Adblock Plus**
(`libadblockplus-android`) con *fallback* automático al motor Kotlin
existente, registra la app como **navegador del sistema** y como
**gestor de descargas externo**, refactoriza `BrowserActivity` con
constantes y nombres descriptivos, y añade tests unitarios e
integración documentada.

### 🐛 Bug 1 — El menú del navegador no se muestra correctamente
No salían todos los botones del menú inferior y no se podía cambiar entre
las tres páginas (Navegación · Herramientas · Privacidad). La causa era
que `menuCell()` aplicaba los `GridLayout.LayoutParams` *después* de
`addView` (nunca se aplicaban) y cada página medía todo el ancho del
pager, desplazando las otras dos fuera de vista. Ahora `menuCell(col,row)`
fija `columnSpec`/`rowSpec` *antes* de añadir la celda, cada GridLayout
tiene ancho fijo igual al del pager, y los puntos de paginación se
repintan al saltar.

### 🐛 Bug 2 — Swipe de accesos rápidos roto
El `HorizontalScrollView` de la página de inicio solo mostraba el primer
panel ("Sitios comunes") y no deslizaba a "IA" ni "Mis sitios". La causa
era que cada panel se añadía con `LayoutParams(MATCH_PARENT, …)` sobre un
LinearLayout de ancho `wrap_content`, por lo que la primera página medía
todo el ancho y las otras quedaban a 0. Ahora cada panel se crea con
`LinearLayout.LayoutParams(panelWidth, MATCH_PARENT)` y se repinta en
`post {}` si el pager aún no tenía ancho real.

### 🐛 Bug 3 — Las sugerencias siguen saliendo tras buscar
Tras pulsar Enter, el panel de sugerencias no se ocultaba y tapaba el
WebView. El `setText(url)` de `load()` disparaba el `TextWatcher`, que
reabría `showSuggestions()`. Ahora `load()` llama a `hideSuggestions()`
antes y después de `setText`, `onPageStarted/onPageFinished` envuelven
el `setText` con un flag `suppressSuggest`, y `showSuggestions(q)` añade
un guard que oculta sugerencias si `q` coincide con la URL activa.

### 🐛 Bug 4 — La URL no se limpia al tocar la barra
Tras una búsqueda, tocar la barra no seleccionaba ni borraba la URL. El
toque simple ahora ejecuta `selectAll()` + foco + teclado (siguiente
carácter reemplaza, como Chrome/Firefox). La pulsación larga abre un
menú contextual con **Compartir enlace**, **Copiar URL**, **Pegar y
abrir** y **Editar URL (sin borrar)**.

### 🛡 Bloqueador de anuncios: Adblock Plus + *fallback* Kotlin
Se integra la librería oficial de Adblock Plus
(`org.adblockplus:adblock-android:4.5.0` +
`adblock-android-webview:4.5.0`) como motor primario. El motor 100 %
Kotlin (`FilterEngine`, integrado en 3.6.0) se conserva como *fallback*
automático: `AdBlockEngine.shouldBlock/cssFor/ruleFor` consultan primero
al motor oficial y, si no está disponible o no está listo, caen al
motor Kotlin y, si tampoco, a la lista compacta de hosts. El wrapper
`AdblockPlusEngine.kt` carga `AdblockHelper` por reflexión, de modo que
la app compila tanto si la AAR se resuelve como si no.

### 🌐 Navegador registrado en el sistema Android
`BrowserActivity` ahora es **exportada** y declara
`ACTION_VIEW`+`BROWSABLE`+`http/https` (aparece en *Ajustes > Apps >
Aplicaciones predeterminadas > Navegador*), `ACTION_SEND` con
`text/plain` ("Compartir enlace → Abrir en TurboDL"),
`ACTION_WEB_SEARCH` (asistente de Google) y `ACTION_PROCESS_TEXT`
("Abrir en TurboDL" desde texto seleccionado). El nuevo `onNewIntent`
procesa estos intents y carga la URL en una pestaña nueva.

### 📥 Gestor de descargas para otras apps
`MainActivity` refina el chooser de magnet/torrent con `ACTION_SEND`
de `text/plain` (otras apps comparten un enlace y TurboDL lo añade a la
cola) y `ACTION_VIEW` con `http/https` y `priority=1` (TurboDL aparece
junto a BrowserActivity; al elegirla, el enlace se añade a la cola en
lugar de abrirse en el navegador).

### 🔧 Refactor y tests
`BrowserActivity.kt` centraliza constantes en `companion object`
(`COLOR_ACCENT`, `MENU_PAGE_COUNT`, `MENU_COLUMNS`, `SITES_PANEL_COUNT`,
etc.), renombra variables de una letra (`q` → `query`, `u` → `url`) y
documenta cada función. Se añaden tests unitarios JVM en
`app/src/test/java/com/turbodl/` para paginación del menú, paneles de
accesos, visibilidad de sugerencias, parsing de URL, tipos de recurso
adblock, clasificación de medios y normalización de URLs sniffadas, más
un documento de tests instrumentados para CI (`connectedDebugAndroidTest`).

### 📦 Internas
- versionCode 15, versionName 4.1.0.
- `settings.gradle` añade los mirrors `https://maven.eyeo.com` y
  `https://jcenter.bintray.com` (solo lectura) para resolver la AAR de
  libadblockplus-android.
- `proguard-rules.pro` añade `-keep` para `org.adblockplus.libadblockplus.**`
  (JNI de V8 + libadblockplus).
- Eliminado el árbol de fuentes duplicado `src/` suelto en la raíz
  (reliquia del "rescate" del commit `32b8df7`); todo vive ahora bajo
  `app/src/`.

## Novedades de la 4.0.0 — navegador estilo Via Browser: interfaz nueva, credenciales, usuarios y exportación

La interfaz del navegador integrado se reconstruye siguiendo el patrón de
Via Browser: barra superior persistente, menú de hoja inferior con páginas
deslizables, sugerencias desplazables, estados con color de acento e
iconografía real. Sobre esa base, la 4.0 corrige los diez defectos de la
línea 4.0 perdida y añade seis funcionalidades nuevas.

### 🔎 Búsqueda: nunca más el teclado encima
- **U1 · Texto siempre visible**: al tocar el buscador central de la página
  de inicio, el texto pasa a la **barra superior** (el campo nunca queda
  tapado por el teclado; `windowSoftInputMode=adjustResize`).
- **U2 · Botón X de limpieza**: aparece en cuanto hay algo escrito y borra
  sin cerrar el teclado ni perder el foco.
- **U10 · Sugerencias desplazables**: el panel de marcadores/historial es
  una lista con scroll hasta el último resultado (tope de altura 420dp).

### 🗂 Menú y accesos: todo con swipe
- **U3 · Menú de 3 páginas deslizables**: swipe izquierda/derecha entre
  Navegación · Herramientas · Privacidad; los puntos reflejan la página
  activa y permiten saltar. Los elementos activos se pintan en acento.
- **U9 · Tres paneles de accesos con swipe**: Sitios comunes (12),
  Inteligencia artificial (Z.ai, ChatGPT, DeepSeek, Gemini, Claude,
  Perplexity, Copilot, Qwen, Hugging Face, Grok) y Mis sitios.
- **U7 · Iconos reales**: favicon real por sitio (servicio público de
  favicons + caché en disco) y monograma por dominio como fallback;
  adiós al icono genérico repetido.
- **U8 · Dos vistas de accesos**: lectura (abrir) y **modo edición
  explícito** con ✕ por celda y **reordenado por arrastrar y soltar**
  (drag & drop nativo); menú contextual con pulsación larga (abrir en
  pestaña nueva, renombrar, mover al inicio, eliminar con confirmación).

### 🧭 Navegación consistente
- **U4 · Botón atrás con máquina de estados única**: sugerencias → hojas →
  inicio ↔ web → salida con confirmación de doble pulsación.
- **U5 · FAB de descargas detectadas**: botón flotante con contador en
  tiempo real (medios olfateados + descargas directas del DownloadListener);
  al pulsarlo se elige qué descargar con selección múltiple.
- **U6 · Interfaz reactiva**: bloqueador, escritorio, lector y nocturno
  cambian de color/estado al instante en el menú y en la barra.

### 🔐 Credenciales (nuevo)
Al enviar un formulario de inicio de sesión, TurboDL ofrece guardar el
usuario y la contraseña. Se cifran con **AES-256-GCM y la clave del
AndroidKeyStore** (nunca en texto claro), son **por usuario** y se
**rellenan automáticamente** en las siguientes visitas al mismo dominio.
Gestión y borrado desde el menú (Privacidad → Credenciales), con opción
«Nunca en este sitio».

### 👥 Usuarios sandbox (nuevo, estilo lfbrowser)
Multiperfil real: cada usuario tiene **cookies, sesiones, credenciales,
historial, marcadores y accesos propios** (sandbox por
`WebView.setDataDirectorySuffix` montada al arrancar el proceso). Al
cambiar de usuario la aplicación se reinicia y **el servidor solo detecta
a ese usuario**. Selector y creación desde el menú (Privacidad → Usuarios).

### 📤 Exportar y guardar offline (nuevo)
- **PDF** por dos vías: impresión del sistema (documento vectorial) o
  captura rápida del render a PDF paginado A4.
- **Markdown** (.md): extrae el contenido principal (nav/scripts fuera),
  convierte encabezados, listas, tablas, código y enlaces con URLs
  absolutas — listo para pegar como contexto en LLMs. Conversión con
  jsoup (vendido en `app/libs`).
- **HTML completo, texto plano y PNG** de página completa.
- **Guardar offline (.mht)**: página tal cual en línea con CSS+JS incluidos;
  se abre sin conexión desde «Páginas guardadas» (guardar, listar, abrir,
  eliminar).

### 🗒 Notas (nuevo)
Notas con CRUD completo dentro del navegador: crear, editar, buscar,
eliminar, compartir y **copiar al portapapeles con un toque** — el flujo
escribir prompt → copiar → pegar en el chatbot, sin salir de TurboDL.

### 🧪 Herramientas de desarrollador (nuevo)
En el menú (Herramientas): **HTML en bruto** del DOM vivo (visor + guardar
.html), **registro de red** de todas las peticiones (activable por sesión)
y **consola de JavaScript** estilo DevTools con niveles por color, captura
de `window.onerror` y evaluación de expresiones en la página activa.

### 🔧 Internas
- Base de datos migrada a **v3** (historial por usuario + tabla de
  credenciales); `NotesDb` nueva para notas y páginas guardadas.
- media3/ExoPlayer resuelto por Gradle; wrapper de Gradle regenerado
  (el del snapshot rescatado estaba corrupto).
- versionCode 14, versionName 4.0.0.

## Novedades de la 3.7.0 — escudo de bloqueos y anti-redirecciones

### 🛡 Escudo: qué bloquea el navegador y cuánto
- Nuevo botón **🛡 en la barra del navegador** con el contador de bloqueos de
  la página activa: subrecursos de anuncios/rastreadores, ventanas emergentes
  y redirecciones.
- Al pulsarlo se abre la **vista de detalles**: lista de todo lo bloqueado en
  esa página (tipo de recurso 📜🖼🎨🎬⚙, host y ruta del elemento y **la regla
  exacta de EasyList/EasyPrivacy que lo bloqueó**), más el desglose de
  ventanas y redirecciones.
- Contadores por pestaña (se reinician en cada carga; el del diagnóstico del
  menú sigue funcionando).

### ↪ Anti-redirecciones (eporner, tnaflix, spankbang y similares)
Los popunders de esos sitios secuestraban la pestaña actual porque el
WebView no tenía ventanas múltiples activadas. Ahora:
- `setSupportMultipleWindows(true)`: cualquier `window.open` pasa por el
  navegador, y **las ventanas sin gesto de usuario se bloquean y cuentan**;
  las legítimas (enlace tocado → target=_blank) se abren en pestaña nueva.
- **Guardia de redirección en `shouldOverrideUrlLoading`**: navegación del
  frame principal SIN toque de usuario hacia otro dominio base se consulta
  contra la lista de filtros (reglas `$popup` y `||red-de-anuncios^` de
  EasyList) y contra una lista compacta de redes de popunder conocidas
  (exoclick, popads, popcash, propellerads, adsterra, hilltopads…).
  Los enlaces tocados por el usuario nunca se tocan, y las redirecciones
  legítimas (inicios de sesión, OAuth) no están en esas listas → pasan.
- Toast informativo «Redirección de anuncio bloqueada» y todo queda en la
  vista de detalles del escudo.

### ⬇⏸🔀 Fases de descarga visibles (video / audio / uniendo)
- Cuando un stream DASH/HLS entrega video y audio por separado, la fila de
  tarea ahora muestra la fase: **«audio X MB»** durante la pista de audio
  (con progreso en vivo) y **«Uniendo video y audio…»** durante la fusión;
  en HLS/DASH sin pista separada dice «Preparando MP4…».
- Sobre la unión en sí: **no se usa ffmpeg**. La fusión audio+video la hace
  `org.mp4parser` (isoparser + muxer, JARs Java vendidos en `app/libs`,
  sin código nativo): lee las pistas (`handler` vide/soun), las reordena por
  tiempo y remuxea a MP4 indexado (`Merge.kt`). Si la fusión falla, los dos
  archivos se conservan por separado y la tarea lo anota.

## Novedades de la 3.6.0 — bloqueador 100 % Kotlin (adiós al Rust)

A petición: se ELIMINÓ por completo el bloqueador en Rust y se sustituyó por
un motor **100 % Kotlin** integrado en la app.

- **Sin Rust**: fuera el AAR `adblock-0.1.3`, sus 4 librerías nativas, las
  entradas JNI y las reglas ProGuard. El APK pasa de ~20 MB a ~15 MB y la app
  ya no carga ni un binario nativo para el bloqueo.
- **Motor nuevo `FilterEngine` (Kotlin puro)** en `com.turbodl.adblock`:
  semántica EasyList — `||host^`, comodines `*`, separador `^` (incluye el
  final de la URL), anclas `|…|`, `$script/$image/$stylesheet/$media/$font/
  $xmlhttprequest/…`, `$third-party/$first-party` (dominio base), `$domain=`,
  `$denyallow=`, `$to=`, `$important`, excepciones `@@`, y ocultación de
  elementos `##` por dominio y genérica con excepciones `#@#` y
  `$elemhide/$generichide`.
- **Por qué propio y no una librería**: se investigó el ecosistema y no
  existe una librería mantenida de motor EasyList en Kotlin/Java puro (eyeo
  es C++/JNI y está parada desde 2017; AdGuard es TypeScript). La parte
  MADURA y mantenida del bloqueo son las LISTAS (EasyList + EasyPrivacy,
  integradas en el APK y actualizables desde Ajustes); el motor compacto es
  nuestro y está probado con JUnit contra las listas reales.
- **Índice por dominio**: ~48.000 dominios en HashMap y las genéricas
  agrupadas por su literal más largo; sin regex en el camino caliente.
  RAM típica ~10 MB (solo EasyList en móviles < 1,5 GB) frente a los
  90-165 MB nativos del motor anterior.
- **Robustez conservada**: el frame principal nunca se bloquea; fail-open
  (si el motor falla, deja pasar y avisa); fusible anti-sobre-bloqueo;
  CSS cosmético limitado y calculado fuera del hilo UI; panel de
  diagnóstico actualizado («Motor: KOTLIN LISTO, N reglas»).
- **Tests JUnit (13) contra las listas REALES**: subrecursos legítimos de
  YouTube/Google/Facebook/X/Reddit/Wikipedia… **0 bloqueados**, anuncios y
  rastreadores (doubleclick, googlesyndication, taboola, analytics…) sí,
  y rendimiento por petición con margen. Durante el desarrollo los tests
  cazaron tres bugs graves del primer borrador (matcher que no completaba
  coincidencias, subdominios hermanos tratados como third-party y una regla
  `*$ping,third-party` que sin el bit de «tipo desconocido» habría bloqueado
  TODO lo third-party) — exactamente el tipo de error que envenenó la 3.4.x.

## Novedades de la 3.5.2 — compatibilidad con móviles de poca RAM

El informe: el navegador seguía sin mostrar nada. **Revisión completa** con el
resultado definitivo de las reglas (bindings Python del mismo crate Rust, con
la clasificación `typeOf()` EXACTA de la app y las listas integradas):

- **Las reglas del motor son limpias**: 0/192 subrecursos legítimos bloqueados
  en ambos modos (lite y full), incluso los sin extensión que la app clasifica
  como `xmlhttprequest` (avatares y miniaturas de Google/YT/X/FB); 48/96
  anuncios bloqueados en lite y 96/96 en full. El cosmético es pequeño
  (12-35 selectores por página) y no esconde `img`/`video`. **El bloqueo no
  es la causa del navegador en blanco.**
- **La causa real en móviles de 1 GB es la RAM del motor**: medido con el
  mismo crate, solo EasyList ocupa ≈90 MB de RAM nativa (≈165 MB con
  EasyPrivacy). En un teléfono de 1 GB eso estrangula al proceso que
  renderiza el WebView → **página en blanco**. Coincide con «todo empezó con
  el bloqueador» (la 3.4.0 lo introdujo).
  **Fix por umbrales de RAM**: < 2 GB **no carga el motor nativo** (filtra con
  la lista básica de hosts, ≈0 MB — así funcionaba la 3.3.0); 2-3 GB solo
  EasyList; ≥ 3 GB ambas listas.
- **Recuperación del renderizador** (`onRenderProcessGone`): si el proceso que
  dibuja el WebView muere (presión de RAM o crash del visor), antes la página
  quedaba en blanco para siempre; ahora **se reconstruye la pestaña, se
  recarga y se avisa** («Visor reiniciado #N»).
- **Panel de diagnóstico** en el menú del navegador («🧪 Diagnóstico del
  navegador»): versión de TurboDL y del WebView, estado real del motor
  (LISTO/lite/full/no cargado por RAM/con motivo), RAM libre y total,
  bloqueados en esta página y reinicios del visor. Es lo que hay que
  reportar si algo sigue fallando.

## Novedades de la 3.5.1

Informe: el navegador no renderizaba imágenes ni vídeos, incluso con el
bloqueador desactivado. Investigación completa (diff 3.3.0→3.5.0 del camino
del WebView, fuente Kotlin del wrapper JNI y **pruebas empíricas con las
bindings Python del mismo crate Rust** sobre las listas incluidas):

| Hallazgo | Corrección |
|---|---|
| **Orden de argumentos invertido en `shouldBlock`**: el wrapper espera `(url=recurso, sourceUrl=página)` y se enviaba `(página, recurso)`. Probado empíricamente: con ese orden el motor bloquea **0 %** (era inerte); con el orden correcto bloquea el 75 % de anuncios y el 0 % de recursos legítimos | Orden corregido + self-test de arranque alineado (el motor ahora sí llega a READY y el bloqueo funciona de verdad) |
| Nada del código bloquea con el bloqueador desactivado (verificado): el fallo de imágenes/vídeos con OFF es de red, no de la app — el informe previo venía de un teléfono **sin conexión** (el texto en caché sí carga; imágenes y vídeos no) | Nuevo `onReceivedError` del frame principal: aviso claro «La página no cargó (…). Si el teléfono está sin internet, no se ven imágenes ni vídeos aunque el bloqueador esté desactivado» |
| Una regla que acertase el frame principal dejaría la pestaña en blanco | El **frame principal nunca se bloquea** (solo subrecursos, como uBlock) |
| Posible sobre-bloqueo futuro no reproducible aquí | **Fusible**: si el motor bloquease > 60 % de ≥ 12 peticiones de una carga, se pausa en la sesión (queda la lista de hosts) y se avisa con un toast |
| CSS cosmético validado: YouTube 12 selectores, Facebook 4, X 7, Wikipedia/Instagram 0 — ninguno esconde img/video | Sin cambios (tope 256 KB y hilo de fondo ya en 3.5.0) |

## Novedades de la 3.5.0

| Área | Qué hay de nuevo |
|---|---|
| Crash del bloqueador solucionado | Blindaje completo de la integración del motor adblock-rust: todo acceso usa `catch(Throwable)` (un `UnsatisfiedLinkError`/`NoClassDefFoundError` degrada a la lista de hosts en vez de matar el proceso); nunca se llama al nativo con URLs que no sean http/https (páginas de error sin conexión, about:blank); el **CSS cosmético se calcula en hilo de fondo y con tope de 256 KB** (los selectores genéricos de EasyList pueden ocupar megas y su construcción en el hilo UI provocaba `OutOfMemoryError` — el crash principal); self-test del motor antes de marcarlo listo; inicialización solo con el bloqueador activado y en el arranque de la app; equipos de **< 1,5 GB de RAM cargan solo EasyList** (modo lite con cache propio); `NATIVE_LOCK` evita use-after-free al sustituir el motor. **Las listas siguen integradas en el APK: el bloqueo funciona sin conexión desde la primera instalación** |
| Introducción de primera instalación | Estilo 1DM: bienvenida → notificaciones de progreso (API 33+) → carpeta de descargas (interna sin permisos o pública/SD vía SAF) → exención de optimización de batería (con consejo para Xiaomi/Huawei) → resumen. Al abrir la app, si falta algo importante aparece un diálogo «Configuración recomendada» con el paso directo |
| Gestor de descargas externo | TurboDL ahora se registra para **enlaces http/https** (`VIEW + BROWSABLE`), como 1DM/Gopeed: aparece en el selector de otras apps (p. ej. navegadores como Via Browser o «Compartir →») para descargar con TurboDL desde una tercera aplicación. Además `onNewIntent` maneja los enlaces cuando la app ya está abierta |
| Cuadrícula de pestañas | Cerrar una pestaña desde la cuadrícula **se refleja al instante** (antes la tarjeta seguía visible hasta reabrir la vista); si es la última, la cuadrícula se cierra |

### Diagnóstico del crash (para el registro)
Informe del usuario: crashes al abrir el navegador/recargar sin conexión con
el bloqueador activado; sin bloqueador, todo normal. Análisis del wrapper JNI
(`adblock-rs-kt-wrapper`, Rust con `panic=abort`): las URLs inválidas no
paniquean (llegan como `RuntimeException` de Java), pero con el bloqueador
activado cada página construía en el **hilo principal** un CSS cosmético que
con EasyList+EasyPrivacy alcanza megas → `OutOfMemoryError` (un `Error`, no
`Exception`) sin capturar → crash; las páginas de error sin conexión también
reciben selectores genéricos, de ahí que se agravara offline. Lo corrige el
conjunto de blindajes listado arriba.

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
