# TurboDL 4.1.0 — Notas de la versión

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
