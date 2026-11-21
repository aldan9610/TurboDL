# TurboDL 4.2.3 — Notas de la versión

Fecha: 2026-11-21

Esta versión reorganiza la página de inicio del navegador, hace que el swipe
entre paneles sea muy sensible e inmediato, y añade un botón para volver
rápido a la vista principal.

## 1. Página de inicio sin logo ni buscador duplicado (U14)

Pediste: *"elimina el icono enorme y la barra de búsquedas que está debajo.
Solo deja la barra de búsqueda superior para que haya más espacio para el
panel de accesos directos, además los dos elementos son redundantes"*.

- Eliminado el `ImageView` de 64 dp con el icono de la app y el `EditText`
  `homeSearch` que había debajo en el `homeOverlay`. Ambos eran
  redundantes con la barra superior (`editUrl`), que siempre visible
  cumple la misma función.
- El `LinearLayout` vertical que los contenía también se quita, de modo
  que el `HorizontalScrollView` de paneles de accesos (`sitesPager`) ahora
  ocupa todo el espacio vertical disponible bajo el título del panel. En
  pantallas pequeñas (BLU C5L Max) los accesos directos ganan ~120 dp de
  altura extra: caben más filas y dejan de verse recortados.
- Se elimina la variable `homeSearch` y todo su cableado (listener de
  edición, listener de foco que sincronizaba con `editUrl`). La barra
  superior ya hace esa sincronización.

## 2. Swipe muy sensible e inmediato (U9, redefinido)

Pediste: *"que sea muy sensible el movimiento de swipe para cambiar de
paneles/pestañas y que con un pequeño gesto rápidamente se cambie de
ventanas. Además que la transición entre paneles sea bien rápida/dura y
pase de manera inmediata. Quiero este comportamiento para la parte de
accesos directos y para el menú del navegador"*.

- El esquema anterior (4.2.2) usaba un runnable que vigilaba `scrollX`
  cada 50 ms hasta que se estabilizaba 100 ms y entonces encajaba con
  `smoothScrollTo` (animación de ~300 ms). Eso era lento y se notaba.
- **Nuevo enfoque con `GestureDetector`**: cada pager (accesos y menú)
  tiene ahora un `GestureDetector` que intercepta el `onFling`. Si la
  velocidad horizontal supera `FLING_VEL_MIN = 300 px/s` (muy baja, un
  flick mínimo cuenta), salta a la página siguiente/anterior
  **INMEDIATAMENTE** con `scrollTo()` (sin animación). El evento se
  consume para que el `HorizontalScrollView` no haga su propia inercia.
- **Umbral bajado al 10%**: si no hubo fling (arrastre lento), al soltar
  el dedo se encaja a la página siguiente si el desplazamiento superó el
  10% del ancho (`SNAP_THRESHOLD_FRACTION = 0.10f`), frente al 25% de
  4.2.2 y al 50% de 4.2.1. Basta deslizar una décima parte de la página.
- **`scrollTo` en vez de `smoothScrollTo`**: la transición es al instante,
  "rápida y dura", sin deslizamiento animado.
- Aplicado a los dos pagers: `sitesPager` (paneles de accesos directos)
  y `menuPager` (menú inferior del navegador).

## 3. Botón para volver a la vista principal (U15)

Pediste: *"dentro del navegador añade un botón/icono para volver rápido
al menú principal de la aplicación (vista principal cuando abres la
aplicación)"*.

- Nuevo botón `btnMainHome` en la barra inferior del navegador, entre
  `btnHome` (🏠) y el contador de pestañas. Icono: ▶ (cuadrado/triángulo).
- A diferencia de `btnHome` (que en 4.2.2 hace dos toques: 1º reinicia
  la búsqueda y carga about:blank, 2º muestra la vista principal), este
  botón va **directo e inmediato** a la vista principal en un solo toque:
  muestra el `homeOverlay` con los paneles de accesos sin tocar la pestaña
  actual. Si ya está visible, la oculta.
- Así tienes dos caminos: `btnHome` para "reiniciar y volver a la home",
  `btnMainHome` para "volver a la home sin tocar lo que tengo cargado".

## 4. Internas

- `versionCode` 19, `versionName` 4.2.3.
- Nuevas constantes en `companion`: `FLING_VEL_MIN = 300f`,
  `SNAP_THRESHOLD_FRACTION = 0.10f`. Las anteriores `IDLE_DELAY` /
  `IDLE_THRESHOLD` quedan sin uso (la vigilancia del scroll se elimina).
- Nuevos campos: `sitesPagerGesture`, `menuPagerGesture`
  (`GestureDetector` por `lazy`), `snapPagerInstant(pager, isMenu,
  forward)`.
- `homeSearch` eliminado (variable + listeners).
- 122 tests JVM pasan (0 fallos).

## 5. Instalación

```
adb install TurboDL-4.2.3.apk
```

APK de debug firmado con la clave de depuración. Android 8.0+ (API 26).

---

# TurboDL 4.2.2 — Notas de la versión

Fecha: 2026-11-21

Esta versión corrige el crash que reportaste al abrir el menú en la 4.2.1
(el `IllegalArgumentException: row indices (start + span) mustn't exceed
the row count`) y aborda los cinco problemas que quedaron sin resolver o
que surgieron tras la 4.2.1: el swipe que no respondía a movimientos
ligeros, la edición de nombres de panel no descubrible, los iconos
recortados en la BLU C5L Max, el "TurboDL 3" residual en dos pantallas y
el Home que dejaba la app atascada en el navegador integrado.

## 1. Crash al abrir el menú (H9)

- **Síntoma** (del log que subiste): al tocar el botón de menú dentro del
  navegador, la app se cierra con `java.lang.IllegalArgumentException:
  row indices (start + span) mustn't exceed the row count` en
  `BrowserActivity.renderMenuPages`. Se reproduce 3 veces en el reporte,
  todas en BLU C5L Max, Android 11, 4.2.1.
- **Causa raíz**: la tercera página del menú tenía **11 celdas** (🛡🚫🍪🔑👥👁📖🧹👤📌🚪)
  pero el `GridLayout` solo admite 10 (`MENU_ROWS=2 × MENU_COLUMNS=5`).
  La celda 11 caía en `row=2`, que excede el `rowCount=2`, y Android 11
  lanza la excepción al añadirla.
- **Solución**: el menú ahora es **dinámico**. Las celdas se definen en
  una lista plana (`allMenuCells()`) y `renderMenuPages` las reparte
  automáticamente en sub-páginas de 10. Si hay 11 acciones, se crean 2
  páginas (10 + 1); si hay 25, 3 páginas. Nunca se excede el rowCount, y
  los puntos del indicador se ajustan al número real de páginas. El
  campo `MENU_PAGE_COUNT` (constante fija en 3) se sustituye por
  `menuPageCount`, una propiedad calculada. Un `if (index >=
  MENU_PER_PAGE) break` actúa de salvaguarda adicional anti-crash.

## 2. Swipe por página completa con umbral bajo (U9, revisado)

Pediste: *"sigo sin tener el comportamiento que quiero de hacer un leve
movimiento y que se muevan los paneles"*.

- El snap anterior solo disparaba al levantar el dedo (`ACTION_UP`), lo
  que fallaba con los flings: el `HorizontalScrollView` sigue rodando por
  inercia cientos de ms después del `ACTION_UP`, y el snap se calculaba
  con un scrollX intermedio, no el final.
- **Nuevo enfoque**: al soltar el dedo, un runnable vigilante lee
  `scrollX` cada 50 ms; cuando deja de cambiar durante 100 ms
  (scroll detenido), calcula la página destino y la anima con
  `smoothScrollTo`. Esto cubre tanto el toque simple como el fling.
- **Umbral bajado al 25%** del ancho (antes 50% vía `round()`): basta
  deslizar una cuarta parte de la página para saltar a la siguiente. Un
  "leve movimiento" ya cambia de panel.
- Aplicado tanto a los paneles de accesos como al menú.

## 3. Renombrar paneles de accesos — ahora descubrible (U11)

Pediste: *"también quiero poder cambiar el título de cada panel … algo
que no hiciste"*.

- En 4.2.1 el renombrado ya existía (pulsación larga sobre el título),
  pero no era **descubrible**: no había ningún botón visible que lo
  indicara.
- Ahora hay un **botón ✏ explícito** al lado del título del panel
  (`btnRenamePanel`). Un toque abre el diálogo "Renombrar panel" con el
  nombre actual precargado; "Guardar" lo persiste por usuario, y
  "Restaurar" lo devuelve al nombre por defecto.
- La pulsación larga sobre el título sigue funcionando como atajo.

## 4. Iconos de accesos recortados (U12)

Pediste: *"los iconos no se muestran bien en mi dispositivo, están
recortados"*.

- En la BLU C5L Max (pantalla ~480×854, ~3.5"), el `item_site.xml` con
  icono de 40 dp + texto 12 sp + padding 10 dp excedía la altura de
  celda y recortaba el icono y el nombre.
- Reducido: padding 10→4 dp, icono 40→32 dp, texto 12→11 sp, margen
  4→3 dp. El `ImageView` ahora usa `scaleType=centerInside` +
  `adjustViewBounds=true`, así el favicon siempre cabe sin deformarse.
  El `FrameLayout` y el `LinearLayout` interno pasan a `match_parent` en
  alto para que el peso 1f del GridLayout reparta el espacio y la celda
  no desborde hacia abajo.
- El monograma (cuando no hay favicon) también se pide a 32 dp para
  coincidir con el tamaño del `ImageView`.

## 5. "TurboDL 3" residual (U13)

Pediste: *"hay sitios dentro de la interfaz que muestran TurboDL 3 cuando
debería ser simplemente TurboDL"*.

- `main.xml` (cabecera de MainActivity) decía "TurboDL 3" → ahora
  "TurboDL".
- `ToolsActivity.kt` (diálogo "Acerca de") decía "TurboDL 3.0.0" → ahora
  "TurboDL 4.2.2".
- No hay más referencias a "TurboDL 3" en el código ni en los recursos.

## 6. Botón Home con dos toques (U10)

Pediste: *"al tocar una vez el Home se reinicie toda la búsqueda y todo
quede limpio, y cuando le dé otra vez al home vaya a la vista principal
de la aplicación (ahora me quedo atascado dentro del navegador
integrado)"*.

- **1er toque** (vista principal oculta): si hay una página cargada, la
  descarta (`about:blank`), limpia la barra de dirección y los medios
  detectados de esa pestaña, y muestra la vista principal de accesos.
  "Todo queda limpio". Si la pestaña ya estaba en blanco, muestra
  directamente la vista principal.
- **2º toque** (vista principal visible): la oculta y deja el campo URL
  vacío y enfocado para una nueva búsqueda.
- Antes el botón era un toggle que mostraba/ocultaba el overlay sin
  limpiar la pestaña, por lo que quedabas "atascado" en el navegador con
  la URL anterior.

## 7. Internas

- `versionCode` 18, `versionName` 4.2.2.
- `MENU_PAGE_COUNT` (constante) → `menuPageCount` (propiedad calculada
  a partir de `allMenuCells().size`).
- `MenuPaginationTest` reescrito (11 casos) blindando el cálculo
  dinámico de páginas y la regresión del crash (11 celdas = 2 páginas).
- `SitesPanelsTest` actualizado a las nuevas constantes.
- 122 tests JVM pasan (0 fallos).

## 8. Instalación

```
adb install TurboDL-4.2.2.apk
```

APK de debug firmado con la clave de depuración. Android 8.0+ (API 26).

---

# TurboDL 4.2.1 — Notas de la versión

Fecha: 2026-11-21

Esta versión corrige el crash que reportaste en la 4.0.0/4.1.0 (el
`CalledFromWrongThreadException` del logcat de la BLU C5L Max) y añade
los cambios que pediste para la navegación de los paneles de accesos y
del menú: swipe por página completa (no scroll fluido), sin
desbordamiento, y nombre de panel editable.

## 1. Crash al detectar medios (H8)

- **Síntoma** (del `reporte.log` que subiste): al abrir una página con
  medios, la app se cierra con `android.view.ViewRootImpl$CalledFromWrongThreadException:
  Only the original thread that created a view hierarchy can touch its views.`
  en `BrowserActivity.updateDetectBtn → TextView.setText`, llamada desde
  `shouldInterceptRequest`. Aparece 4 veces en el reporte (2 en 4.0.0 y
  2 en 4.1.0).
- **Causa raíz** (`BrowserActivity.kt`):
  `WebViewClient.shouldInterceptRequest` corre en un hilo de red de
  Chromium. Cada petición matching `MEDIA_RE`/`DETECT_RE` llamaba a
  `addDetected(...)`, que a su vez llamaba a `updateDetectBtn()`, que
  toca vistas (`btnVideos.setText`, `fab.setText`, `fab.visibility`).
  Android prohíbe tocar vistas desde un hilo que no sea el UI.
- **Solución**: `addDetected` ahora envuelve `updateDetectBtn()` en
  `runOnUiThread { ... }`; además `updateDetectBtn` tiene un guardameta
  que, si se la invoca desde otro hilo en el futuro, reprograma en el hilo
  UI. La persistencia de `detected` y el `postDelayed(uiH, ...)` ya eran
  seguros (`uiH` es un handler del hilo UI). El contador del FAB y el
  badge de "Detectados" siguen actualizándose en tiempo real.

## 2. Swipe por página completa (no scroll fluido)

Pediste: *"en la próxima versión para la navegación de los paneles de
accesos directos y del menú no lo quiero como scroll fluido sino que con
movimiento de swipe pase de pestañas completamente"*.

- Implementado en **paneles de accesos** y en **menú** con un
  `OnTouchListener` que, al soltar el dedo (`ACTION_UP`/`ACTION_CANCEL`),
  dispara `scheduleSitesSnap()` / `scheduleMenuSnap()`. Estas funciones
  calculan la página más cercana como `round(scrollX / pageWidth)` y la
  animan con `smoothScrollTo`, encajando la página entera (igual que un
  ViewPager, pero sin añadir ninguna dependencia externa — importante,
  porque el build es offline y la integración de libs externas ha sido
  problemática).
- Un flag `sitesSnapBusy`/`menuSnapBusy` evita la reentrancia durante la
  animación de snap (que dispara más eventos de scroll).
- El indicador de puntos y el título del panel se actualizan en tiempo
  real mientras deslizas, y se afianzan al soltar.

## 3. Sin desbordamiento — todo cabe en pantalla

Pediste: *"que no haya desbordamiento y que todo aparezca en pantalla; no
medios iconos, sino cabe añadir a otra pestaña/panel"*.

- Cada sub-panel de accesos es ahora un `GridLayout` de 4 columnas × 3
  filas = 12 huecos, con peso `1f` por celda — ocupan exactamente el
  área visible, sin scroll vertical interno.
- Si una categoría tiene **más de 12 accesos**, se reparte
  automáticamente en 2 o más sub-paneles paginados (no aparece scroll
  vertical; se añade otro panel). Por ejemplo, 13 accesos = 2
  sub-paneles, 25 accesos = 3 sub-paneles.
- Las celdas vacías (cuando una categoría no llena los 12 huecos) se
  mantienen invisibles pero conservan su tamaño, así la rejilla nunca se
  deforma ni desborda.
- El menú inferior ya cabía (10 celdas por página, 3 páginas); solo se le
  añadió el snap por página completa.

## 4. Renombrar paneles de accesos

Pediste: *"además quiero poder editar el nombre de los paneles de acceso
directo que ahora no puedo"*.

- Pulsación larga sobre el **título del panel** (`txtSitesTitle`) abre el
  diálogo "Renombrar panel", con el nombre actual precargado. Aceptar lo
  guarda; "Restaurar" lo devuelve al nombre por defecto.
- También accesible desde el menú contextual de cada celda (pulsación
  larga sobre un acceso → "📝 Renombrar este panel").
- Los títulos se persisten por usuario activo
  (`siteTitleN_<user>` en SharedPreferences). El botón "Restaurar
  accesos" también limpia los títulos renombrados.
- El título del sub-panel muestra el nombre de la categoría + sufijo
  "· 2/3" cuando una categoría ocupa varios sub-paneles.

## 5. Internas

- `versionCode` 17, `versionName` 4.2.1.
- Constantes reorganizadas: `SITES_CATEGORY_COUNT=3`, `SITES_PER_PAGE=12`
  (antes `SITES_PANEL_COUNT=3`, `SITES_ROWS_VISIBLE=3`).
- `sitesAdapter` (GridView) reemplazado por `makeSitesPage` (GridLayout
  por sub-panel) y `makeSiteCell` (celda individual). El drag para
  reordenar se resuelve por hit-test del GridLayout.
- Tests: `SitesPanelsTest` reescrito (15 casos) blindando las nuevas
  constantes, el reparto automático en sub-paneles y la fórmula de
  snap. Los 119 tests JVM siguen pasando.

## 6. Instalación

APK de debug firmado con la clave de depuración. Permite instalación en
Android 8.0+ (API 26).

```
adb install TurboDL-4.2.1.apk
```

---

# TurboDL 4.2.0 — Notas de la versión

Fecha: 2026-11-20

Esta versión resuelve los problemas que reportó el usuario tras la 4.1.0:
el cierre inesperado al navegar, las funcionalidades de notas y de cambio
de usuario rotas, la imposibilidad de elegir bloqueador de anuncios o de
ver qué hay almacenado en cada sandbox de usuario.

## 1. Crash al navegar (diagnóstico y arreglo)

Se analizaron cinco causas potenciales de cierre inesperado y se
arreglaron todas:

### 1.1 H1 — `WebView.setDataDirectorySuffix` mal defendido
- **Síntoma**: crash al cambiar de usuario o al abrir un enlace tras
  haber cambiado de usuario.
- **Causa raíz** (`app/src/main/java/com/turbodl/util/Users.kt:71-76`):
  el `try/catch` atrapaba `Exception` (no `Throwable`); en Android 9+ la
  segunda llamada a `setDataDirectorySuffix` en el mismo proceso lanza
  `IllegalStateException`. Como `restartApp` a veces no mata el proceso a
  tiempo y Android re-entra por `App.onCreate`, la segunda llamada dejaba
  el sandbox inconsistente y podía tirar la app.
- **Solución**: el sufijo se aplica de forma idempotente — si ya se aplicó
  uno igual, no se llama de nuevo; si se aplicó uno distinto, se deja el
  anterior con un `Log.w` (sin crash); el `try/catch` ahora es `Throwable`
  para cubrir también `Error`. Se registra el sufijo aplicado en una var
  estática `appliedSuffix`.

### 1.2 H2 — `ConcurrentModificationException` en `tabs`
- **Síntoma**: crash al cerrar una pestaña mientras una página está todavía
  cargando subrecursos.
- **Causa raíz** (`BrowserActivity.kt:2338-2400`): `shouldInterceptRequest`
  corre en hilo de red e iteraba la lista `tabs` con `firstOrNull` (que
  usa un iterator interno) mientras el hilo UI la mutaba con `newTab`/
  `closeTab`.
- **Solución**: toda mutación e iteración de `tabs` pasa por un
  `tabsLock` (un `Any`). Se añaden helpers `snapshotTabs()` y
  `currentTab()` para los accesos desde hilos de red. Las mutaciones en
  `newTab`/`closeTab`/`switchTab`/`trimTabs`/`closeOthers`/`restoreTabs`/
  `persistTabs` se envuelven en `synchronized(tabsLock)`; las operaciones
  UI (attach, renderStrip, setText) se hacen fuera del lock.

### 1.3 H3 — `onCreateWindow` sin cancelar el `postDelayed`
- **Síntoma**: crash al tocar un enlace `target=_blank` y volver atrás.
- **Causa raíz** (`BrowserActivity.kt:2433-2472`): el `newTab` diferido
  500 ms no se cancelaba en `onDestroy` y no chequeaba `isFinishing`.
- **Solución**: se introduce un `uiHandler` compartido; en `onDestroy`
  se llama `uiHandler.removeCallbacksAndMessages(null)` y se destruyen
  los WebViews temporales pendientes (`pendingTempWebViews`). El
  `postDelayed` chequea `isFinishing`/`isDestroyed` antes de `newTab`.

### 1.4 H4 — `handleExternalIntent` ejecutado dos veces
- **Síntoma**: crash al abrir un enlace desde fuera de la app (Gmail,
  WhatsApp…).
- **Causa raíz** (`BrowserActivity.kt:695, 763-772`): tras exportar
  `BrowserActivity` en 4.1.0 con `launchMode=singleTask`, el sistema
  puede entregar `onNewIntent` justo después de `onCreate`, procesando el
  mismo intent dos veces. Además `load()` no estaba en try/catch y una
  URL inválida llegada por `ACTION_PROCESS_TEXT`/`WEB_SEARCH` lanzaba
  `IllegalArgumentException`.
- **Solución**: deduplicación por instancia de intent (`lastHandledIntent`);
  `load()` valida el scheme con `isSafeWebUrl()` (admite http/https/file/
  about/magnet) y se envuelve en try/catch.

### 1.5 H7 — `offerCredSave` abría `AlertDialog` sin chequear `isFinishing`
- **Síntoma**: crash si una página hacía submit de un formulario justo
  al salir del navegador.
- **Causa raíz** (`BrowserActivity.kt:451-470`): `AlertDialog.show()` sobre
  una Activity no resumida lanza `BadTokenException`; `Secrets.encrypt`
  tampoco estaba protegido y puede lanzar si el AndroidKeyStore no está
  disponible.
- **Solución**: se chequea `isFinishing || isDestroyed` al inicio;
  `Secrets.encrypt` y `setNeverCred` van en try/catch con toast de error.

## 2. Selector de bloqueadores de anuncios (nuevo)

Antes el motor cargaba EasyList + EasyPrivacy de forma fija (arrays
hardcoded en `AdBlockEngine.java`). Ahora:

- **Catálogo** (`AdblockCatalog.kt`, nuevo): 8 listas con URL real,
  formato (EasyList o hosts), descripción y tamaño aproximado.
- **Selector UI** en Ajustes → "📋 Listas de filtros" — diálogo
  `setMultiChoiceItems` con la descripción de cada lista y un aviso de
  RAM. Botón "Restaurar por defecto".
- **Persistencia** en `SharedPreferences("cfg")` clave `adblockLists`
  como JSON array de ids. Si está vacío o no se ha elegido nada, se usa
  el set por defecto (`{easylist, easyprivacy}`) para no romper
  instalaciones existentes.
- **Carga** en `AdBlockEngine.init` y `updateLists`: itera la selección
  del catálogo, copia desde assets si la lista está embebida (easylist/
  easyprivacy) o descarga la URL, y concatena. Las listas tipo hosts
  pasan por `HostsAdapter.toEasyList()` que las convierte a `||host^`.
- **Listas disponibles**:
  - EasyList (`easylist.to/easylist/easylist.txt`)
  - EasyPrivacy (`easylist.to/easylist/easyprivacy.txt`)
  - AdGuard Base (`filters.adtidy.org/extension/ublock/filters/2.txt`)
  - AdGuard Tracking (`filters.adtidy.org/.../3.txt`)
  - AdGuard Annoyances (`filters.adtidy.org/.../14.txt`)
  - Fanboy's Annoyances (`secure.fanboy.co.nz/fanboy-annoyance.txt`)
  - EasyList Spanish (`easylist.to/easylistspanish/easylistspanish.txt`)
  - StevenBlack/hosts (`raw.githubusercontent.com/StevenBlack/hosts/master/hosts`)
- **Diagnóstico** del motor muestra las listas cargadas en lugar de
  "EasyList + EasyPrivacy" fijo.

## 3. Notas — CRUD completo (arreglado)

El CRUD básico existía en la BD pero fallaba en la UI:

- **La lista no se refrescaba tras crear/editar** (sí tras eliminar): el
  usuario creaba una nota y no la veía al volver a la lista. Ahora el
  editor recibe un callback `onSaved` que recarga la vista de notas.
- **Búsqueda en vivo** por título o cuerpo: caja de búsqueda en la
  cabecera; el `NotesDb.notes(query)` ya existía pero no se exponía.
- `editNote` usa `NotesDb.getNote(id)` (nuevo) en vez de releer toda la
  tabla con `notes().firstOrNull`.
- Eliminar pide confirmación con `AlertDialog`.

## 4. Usuarios sandbox — CRUD completo + borrado real (arreglado)

- **Rename** (nuevo, `Users.rename`): completa el CRUD — antes solo se
  podía crear y eliminar. El id no cambia, así que todo el sandbox
  (cookies, history, creds, marcadores, accesos) se conserva.
- **Eliminar ahora borra de verdad** (`Users.remove` + `wipeSandbox`):
  antes solo se quitaba el nombre de la lista JSON y los datos quedaban
  huérfanos. Ahora se borra:
  - Directorio WebView del sandbox (`app_webview/usr_$id/`).
  - Filas de `history` y `creds` con `user=?` en `turbodl.db` (lo hace el
    llamador con `Db.clearHistory(id)` y `Db.clearCreds(id)`, nuevos).
  - SharedPrefs `bookmarks_$id`, `history_$id`, `sitesP{0,1,2}_$id`.
  El diálogo informa de que el borrado es completo.
- **`restartApp` con fallback**: si `getLaunchIntentForPackage` devuelve
  null, no mata el proceso sin relanzar; cierra las tareas con
  `ActivityManager.appTasks.forEach { finishAndRemoveTask() }`.

## 5. Visor de sandbox del usuario activo (nuevo)

`showSandboxViewer()` en `BrowserActivity.kt`: diálogod e solo lectura
accesible desde el menú del navegador (Privacidad → 👁 Sandbox) o desde
el diálogo de Usuarios. Muestra:

- 🍪 Cookies del WebView (CookieManager) — número de entradas y nombres.
- 🗄 localStorage y 🗃 IndexedDB de la página activa (vía JS, solo si hay
  una página http(s) cargada).
- 🕘 Historial de navegación (tabla `history` filtrada por usuario).
- 🔑 Credenciales guardadas + 🚫 hosts con "nunca preguntar" (tabla
  `creds`; sin mostrar el secreto).
- ⭐ Marcadores y 📌 accesos (SharedPreferences namespaced por usuario).
- 📂 Ruta del sandbox en disco y tamaño total (`dirSize` recursivo).

Todo es de solo lectura. Para borrar hay que usar "Limpiar datos" o
eliminar/renombrar al usuario.

## 6. Tests

Nuevos tests unitarios JVM en `app/src/test/java/com/turbodl/`:

- `adblock/HostsAdapterTest.kt`: 14 casos — conversión `0.0.0.0 host` →
  `||host^`, descarte de localhost/comentarios, paso de selectores
  cosméticos EasyList, CRLF/LF.
- `adblock/AdblockCatalogTest.kt`: 13 invariantes — ids únicos, URLs
  válidas, default en catálogo, presencia de AdGuard/Fanboy/StevenBlack.
- `util/UsersLogicTest.kt`: 3 invariantes estáticas (sin Context) — id
  "main", formato del sufijo `usr_$id`, generación de ids `u<…>`.

El build de debug (`./gradlew assembleDebug`) y todos los tests JVM
(`./gradlew testDebugUnitTest`) pasan.

## 7. Compilación

**Requisitos**: Android SDK Platform 34, Build Tools 34.0.0, JDK 17+.

```bash
cd /home/z/my-project/TurboDL
export ANDROID_HOME=/home/z/android-sdk
./gradlew --no-daemon assembleDebug
# APK en app/build/outputs/apk/debug/TurboDL-4.2.0.apk
```

## 8. Verificación manual sugerida

| Cambio | Cómo verificar |
|---|---|
| Crash al cambiar usuario | Crear usuario → cambiar a él → tocar un enlace → no debe crashear. |
| Crash al cerrar pestaña | Abrir varias pestañas, abrir una página pesada → cerrar una pestaña mientras carga → no debe crashear. |
| Crash al abrir enlace externo | Compartir un enlace desde Gmail → elegir TurboDL → no debe crashear. |
| Selector de listas | Ajustes → Listas de filtros → activar AdGuard Base + StevenBlack → Guardar → Diagnóstico muestra las listas cargadas. |
| Notas | Menú → Notas → crear una → volver a la lista: aparece. Buscar "algo": filtra. |
| Usuarios rename | Menú → Usuarios → Renombrar → el nombre cambia y el id se conserva. |
| Usuarios eliminar | Eliminar un usuario con datos → el directorio `app_webview/usr_<id>/` desaparece. |
| Visor de sandbox | Menú → Sandbox → aparecen cookies, historial, creds, marcadores del usuario activo. |

---

# TurboDL 4.1.0 — Notas de la versión (histórico)

Fecha: 2026-11-20

Esta versión corrige los cuatro bugs del navegador que reportó el usuario,
integra el bloqueador de anuncios maduro de Adblock Plus, registra la app
como navegador del sistema y como gestor de descargas externo, refactoriza
`BrowserActivity` con nombres descriptivos y comentarios, y añade tests
unitarios e integración documentada.

## Correcciones de bugs del navegador

### Bug 1 — El menú del navegador no se muestra correctamente

**Síntomas** (informe del usuario): no salían todos los botones del menú
inferior y no se podía cambiar entre submenús (las tres páginas).

**Causa raíz** (en `app/src/main/java/com/turbodl/ui/BrowserActivity.kt`):
- `menuCell()` definía el `GridLayout.LayoutParams` con `width = 0` y
  `columnSpec = spec(UNDEFINED, 1f)` **después** de añadir la celda al
  GridLayout, por lo que nunca se aplicaba: las celdas se amontonaban a
  la izquierda.
- `renderMenuPages()` añadía cada GridLayout al LinearLayout horizontal
  `menuPages` con `LayoutParams(MATCH_PARENT, MATCH_PARENT)`, pero el
  LinearLayout tiene `wrap_content` en su propio ancho. La primera
  página medía todo el ancho del pager y desplazaba a las dos siguientes
  fuera de vista (ancho 0).
- El indicador de página (puntos) no se repintaba al saltar.

**Solución**:
- `menuCell(c, col, row)` ahora recibe la posición `(col, row)` y
  asigna los `LayoutParams` con `columnSpec = spec(col, 1f)` y
  `rowSpec = spec(row, 1f)` **antes** de `addView`. Ancho/alto a `0`
  para que el GridLayout reparta el espacio por peso.
- `renderMenuPages()` fija cada GridLayout a un `LinearLayout.LayoutParams`
  de **ancho fijo = ancho del pager** (con fallback al ancho de pantalla
  si el pager aún no se ha medido) y alto `MATCH_PARENT`.
- `showMenuSheet()` repinta tras el primer `post {}` cuando el pager ya
  tiene ancho real.
- `renderMenuDots()` y `jumpMenuPage(i)` repintan el indicador al
  cambiar de página.

### Bug 2 — No se puede hacer swipe de los accesos rápidos ni se ven páginas

**Síntomas**: el `HorizontalScrollView` de la página de inicio solo
mostraba el primer panel de accesos (Sitios comunes) y no se deslizaba
a los paneles 2 y 3 (IA y Mis sitios).

**Causa raíz**: en `buildSitesPanels()`, cada panel se añadía a
`sitesPages` (LinearLayout horizontal con `wrap_content` en width) con
`LayoutParams(MATCH_PARENT, MATCH_PARENT)`. La primera página medía todo
el ancho del pager; las dos siguientes quedaban a ancho 0.

**Solución**:
- Cada panel se crea con `LinearLayout.LayoutParams(panelWidth,
  MATCH_PARENT)`, donde `panelWidth = sitesPager.width` (con fallback a
  `resources.displayMetrics.widthPixels` en el primer render).
- Tras el primer render, si el pager aún no tenía ancho, se repinta en
  un `post {}` para fijar el ancho real.

### Bug 3 — Las sugerencias de búsqueda siguen saliendo tras la búsqueda

**Síntomas**: una vez realizada una búsqueda, el panel de sugerencias
seguía visible tapando el WebView.

**Causa raíz**:
- `go()` llamaba a `load()`, pero este nunca ocultaba el panel
  `suggestionsHost`. El `setText(url)` en `load()` disparaba el
  `TextWatcher`, que reabría `showSuggestions(url)` → el panel reaparecía.
- El `WebViewClient.onPageStarted/onPageFinished` reescribía `editUrl`
  con la URL final (posiblemente distinta por redirección del
  servidor), disparando el `TextWatcher` otra vez.

**Solución**:
- `load()` ahora llama a `hideSuggestions()` **antes** y **después**
  de `setText(url)` (defensivo ante el orden de llamadas del TextWatcher).
- `go()` llama también a `hideSuggestions()` y a `hideKb()`.
- `onPageStarted` y `onPageFinished` envuelven el `setText(url)` con
  `suppressSuggest = true/false` y llaman a `hideSuggestions()`.
- `showSuggestions(q)` añade un **guard adicional**: si `q` coincide
  exactamente con la URL de la pestaña activa, oculta sugerencias (el
  usuario ya está ahí, no hay nada que sugerir).

### Bug 4 — La URL no se limpia al hacer click en la barra de dirección

**Síntomas**: tras una búsqueda, al tocar la barra de dirección no se
seleccionaba/borraba la URL actual, lo que estropeaba la navegación
rápida. El usuario quería que el toque simple la borre (para escribir
una nueva) pero manteniendo "compartir enlace" y "copiar URL" en la
pulsación larga.

**Solución** (en `onCreate` de `BrowserActivity`):
- `editUrl.setOnClickListener`: si el campo no está vacío, ejecuta
  `editUrl.selectAll()` + pide foco + teclado. Así, el siguiente
  carácter escrito reemplaza la URL (igual que Chrome/Firefox).
- `editUrl.setOnLongClickListener`: muestra un nuevo diálogo
  `showUrlContextMenu()` con cuatro opciones:
  - **🔗 Compartir enlace** → `ACTION_SEND` chooser.
  - **📋 Copiar URL** → `ClipData` al portapapeles del sistema.
  - **📂 Pegar y abrir** → lee el portapapeles, si es URL la carga.
  - **✏ Editar URL (sin borrar)** → cursor al final sin `selectAll()`.

## Resto de cambios

### 5. Bloqueador de anuncios: libadblockplus-android

Se integra la librería oficial de Adblock Plus
(`https://github.com/adblockplus/libadblockplus-android`) como motor
primario. El motor 100% Kotlin (`FilterEngine`, integrado en 3.6.0) se
conserva como **fallback automático**. La elección es transparente para
el navegador: `AdBlockEngine.shouldBlock/cssFor/ruleFor` consultan
primero al motor oficial y, si no está disponible o no está listo,
caen al motor Kotlin; si tampoco, a la lista compacta de hosts.

**Archivos nuevos/modificados**:
- `app/src/main/java/com/turbodl/adblock/AdblockPlusEngine.kt` (nuevo):
  wrapper Kotlin que carga `AdblockHelper` por reflexión, de modo que
  la app compila tanto si la AAR se resuelve como si no. Si la AAR no
  está en el classpath, todos los métodos devuelven `false`/`""` y el
  fallback `FilterEngine` responde.
- `app/src/main/java/com/turbodl/engine/AdBlockEngine.java`: actualiza
  `ensure/ready/caido/deshabilitarSesion/diagnostico/shouldBlock/ruleFor/
  cssFor` para delegar primero en el motor oficial.
- `app/build.gradle`: añade
  `implementation 'org.adblockplus:adblock-android:4.5.0'` y
  `implementation 'org.adblockplus:adblock-android-webview:4.5.0'`.
- `settings.gradle`: añade el mirror de jcenter de Alibaba,
  `https://maven.eyeo.com` y `https://jcenter.bintray.com` (solo lectura)
  para resolver la AAR.
- `app/proguard-rules.pro`: reglas `-keep` para
  `org.adblockplus.libadblockplus.**` (JNI de V8 + libadblockplus).

**Limitación conocida**: la AAR se publicaba en jcenter (retirado en
2021). Si ningún mirror la resuelve, el proyecto sigue compilando y
funcionando con el motor Kotlin. La integración nativa con V8 consume
~30 MB adicionales de APK y RAM; en dispositivos con poca RAM, el
wrapper prioriza el motor Kotlin.

### 6. Navegador registrado en el sistema Android

`BrowserActivity` ahora es **exportada** y declara los siguientes
intent-filters (en `app/src/main/AndroidManifest.xml`):
- `ACTION_VIEW` con `BROWSABLE` + `http/https` + categoría
  `APP_BROWSER`: el sistema Android la reconoce como navegador
  disponible en *Ajustes > Apps > Aplicaciones predeterminadas >
  Navegador*. Al tocar un enlace http/https en cualquier app (Gmail,
  WhatsApp, Keep Notes…), el chooser ofrece TurboDL junto a
  Chrome/Firefox.
- `ACTION_SEND` con `text/plain`: permite "Compartir enlace → Abrir en
  navegador TurboDL".
- `ACTION_WEB_SEARCH`: el asistente de Google ("Buscar X") abre el
  navegador TurboDL con la búsqueda.
- `ACTION_PROCESS_TEXT`: el texto seleccionado en otra app puede
  abrirse en TurboDL ("Abrir en TurboDL", igual que "Buscar en
  Google").

`BrowserActivity.onCreate` y el nuevo `onNewIntent` procesan estos
intents (`handleExternalIntent`) y cargan la URL en una pestaña nueva.

### 7. Gestor de descargas para otras apps

`MainActivity` ya tenía los intent-filters de magnet/torrent. Esta
versión refina el chooser:
- `ACTION_SEND` con `text/plain`: otras apps comparten un enlace y
  TurboDL aparece como destino; el enlace se añade a la cola de
  descargas.
- `ACTION_VIEW` con `http/https` y `priority=1`: TurboDL aparece en
  el chooser junto a BrowserActivity; al elegirla, el enlace se
  añade a la cola de descargas en lugar de abrirse en el navegador.

### 8. Refactor: nombres descriptivos y comentarios

`BrowserActivity.kt` (3896 → ~4100 líneas):
- Constantes centralizadas en `companion object`:
  `COLOR_ACCENT`, `COLOR_TEXT_PRIMARY`, `COLOR_TEXT_SECONDARY`,
  `COLOR_TEXT_HINT`, `COLOR_ORANGE`, `COLOR_DOT_OFF`,
  `MENU_PAGE_COUNT`, `MENU_COLUMNS`, `MENU_ROWS`,
  `SITES_PANEL_COUNT`, `SITES_COLUMNS`, `SITES_ROWS_VISIBLE`.
- Comentarios descriptivos en cada función relevante, explicando
  qué hace, por qué, y (cuando aplica) a qué bug del usuario responde.
- Renombrado de variables de una letra (`q` → `query`, `u` → `url`)
  en los métodos de navegación (`go`, `load`, `goUrlContextMenu`).

### 9. Tests

Nuevos archivos en `app/src/test/java/com/turbodl/`:
- `util/NormalizeSniffUrlTest.kt`: deduplicación de segmentos
  googlevideo (range, rn, rbuf).
- `util/MediaClassTest.kt`: clasificación `mediaClass` y `fmtOf`
  (stream / video / audio / otro) y formato (HLS, DASH, MP4, etc.).
- `util/UrlBarResolutionTest.kt`: lógica de resolución de URL de la
  barra de dirección (Bug 4) y utilidades `looksLikeUrl` y `hostOf`
  para el menú contextual de pulsación larga.
- `engine/AdBlockTypeOfTest.kt`: tipo de recurso
  (`script/image/stylesheet/media/font/document/xmlhttprequest`) que
  el motor de bloqueo necesita.
- `ui/MenuPaginationTest.kt`: paginación del menú inferior (Bug 1).
- `ui/SitesPanelsTest.kt`: paneles de accesos (Bug 2).
- `ui/SuggestionsVisibilityTest.kt`: ocultar sugerencias tras
  búsqueda (Bug 3).
- `integration/IntegrationTestsDocumentation.kt`: lista de casos de
  integración (instrumented tests) que deben ejecutarse en CI con un
  emulador o dispositivo; documenta los comandos `./gradlew
  :app:connectedDebugAndroidTest`, etc.

## Compilación

**Requisitos**:
- Android SDK Platform 34 (`android-34`), Build Tools 34.0.0.
- JDK 17 o superior (Gradle 8.7 lo exige).
- Conexión a internet la primera vez (para resolver Gradle/AGP y
  opcionalmente la AAR de libadblockplus-android).

**Compilar el APK de release** (firmado con `turbo.keystore` incluido):

```bash
cd /home/z/my-project/TurboDL
export ANDROID_HOME=/ruta/al/Android/Sdk
./gradlew --no-daemon assembleRelease
# APK en app/build/outputs/apk/release/TurboDL-4.1.0.apk
```

**Compilar el APK de debug** (sin firmar):

```bash
./gradlew --no-daemon assembleDebug
```

**Ejecutar tests unitarios JVM** (no requieren SDK, solo JUnit):

```bash
./gradlew --no-daemon :app:testDebugUnitTest
# Resultados en app/build/reports/tests/testDebugUnitTest/
```

**Ejecutar tests instrumentados** (requieren emulador o dispositivo):

```bash
./gradlew --no-daemon :app:connectedDebugAndroidTest
```

## Limitación del sandbox

En el entorno de build de este informe no está disponible el Android
SDK ni Gradle cache, por lo que el APK no se compiló aquí. La verificación
sintáctica y la coherencia de los tests se hizo por inspección. Antes
de generar el release en GitHub:

1. Compila el APK de release con el comando anterior y verifica que
   no haya errores.
2. Ejecuta `./gradlew :app:testDebugUnitTest` y comprueba que todos
   los tests pasan.
3. Instala el APK en un emulador API 26+ y prueba manualmente los
   cuatro bugs (menú, accesos, sugerencias, barra de dirección).
4. Verifica en *Ajustes > Apps > Aplicaciones predeterminadas >
   Navegador* que TurboDL aparece.

## Verificación de los bugs

| Bug | Cómo verificar |
|---|---|
| 1 — Menú | Abrir el navegador → tocar ⋮ (menú) → los 10 botones deben verse en cada página; deslizar horizontalmente cambia entre las 3 páginas; los puntos indican la página activa; tocar un punto salta a esa página. |
| 2 — Accesos | En la página de inicio, deslizar horizontalmente sobre la cuadrícula de accesos → cambia entre "Sitios comunes", "IA" y "Mis sitios"; los puntos indican el panel activo; tocar un sitio abre su URL. |
| 3 — Sugerencias | Teclear "youtube" en la barra → aparecen sugerencias; pulsar Enter (carga youtube.com) → el panel de sugerencias desaparece y se ve el WebView; tocar la barra de nuevo → no se muestran sugerencias (la URL ya está cargada). |
| 4 — Barra URL | Tras cargar una página, tocar la barra → se selecciona toda la URL; escribir "g" reemplaza la URL; pulsación larga sobre la barra → menú con "Compartir enlace" / "Copiar URL" / "Pegar y abrir" / "Editar URL (sin borrar)". |

## Commits

- `feat(navegador): menú inferior paginado con todas las celdas visibles (Bug 1)`
- `fix(accesos): paneles de accesos con swipe horizontal y 3 páginas (Bug 2)`
- `fix(sugerencias): ocultar tras cargar URL en onPageStarted/onPageFinished (Bug 3)`
- `feat(barra): toque selecciona URL, pulsación larga menú share/copy (Bug 4)`
- `refactor: constantes y nombres descriptivos en BrowserActivity`
- `feat(adblock): integrar libadblockplus-android con fallback a FilterEngine`
- `feat(intents): BrowserActivity registrada como navegador del sistema`
- `feat(intents): gestor de descargas para ACTION_SEND y ACTION_VIEW http/https`
- `test: tests unitarios de paginación, accesos, sugerencias, URL parsing, tipos adblock`
- `docs: RELEASE_NOTES.md 4.1.0`
