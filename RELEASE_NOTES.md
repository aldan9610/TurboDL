# TurboDL 4.17 — Notas de la versión

Fecha: 2026-11-27

## Mejora 1: modo claro y oscuro en TODA la aplicación

Reportaste que la vista principal y las barras del navegador no
implementaban el tema oscuro. La causa: el sistema de temas existía
(claro / oscuro / AMOLED por preferencia) pero gran parte de la interfaz
pintaba colores fijos de modo claro — `browser.xml` tenía 28 literales
(`#FFFFFF` en las barras, `#212121` en los textos), las hojas
inferiores, sugerencias, accesos y diálogos igual, y el código de
BrowserActivity/SettingsActivity repetía lo mismo a mano.

Ahora **todo el color sale de una paleta única por tema**: nuevos
atributos `td*` (fondo, tarjeta, barra, texto, texto secundario, hints,
divisores, suave) definidos en cada tema y resueltos por layouts XML y
por código a través de helpers `Ui.bg/card/bar/text/text2/text3/
divider/soft/accent`. Auditoría completa con script: las barras del
navegador, hojas de pestañas y menú, sugerencias, accesos directos,
tira de pestañas, diálogo de usuarios, consola, iconos de pestañas,
Ajustes y el fondo del WebView (adiós al "flash blanco" al cargar
páginas en oscuro) siguen el tema. Los textos blancos sobre naranja se
conservan a propósito; los colores semánticos (error rojo, aviso) no
cambian. Las notas antiguas de 4.15 y anteriores también heredan la
paleta sin perder datos.

## Mejora 2: Herramientas rediseñadas con la elaboración de Ajustes

La vista eran cuatro botones planos con un texto de "acerca de" pegado.
Ahora replica el patrón de Ajustes que te gustó: **encabezados de
sección** (Archivos · Navegador y seguridad · Acerca de) y **filas con
título + subtítulo descriptivo**, con el mismo comportamiento táctil y
el mismo respetado de tamaño de fuente y tipografía personalizables.
Cada herramienta explica qué hace antes de abrirla (combinar archivos,
suma de verificación MD5/SHA-1/SHA-256, codificadores URI/Base64,
bóveda de credenciales y la nueva sección de Notas).

## Mejora 3: Notas como herramienta — módulo completo estilo ColorNote

Las notas salen del diálogo escondido del navegador y se convierten en
una **pantalla completa** propia (Herramientas › Notas, y también el
menú del navegador), con la estructura y funcionalidades al estilo de
ColorNote (`com.socialnmobile.dictapps.notepad.color.note`) manteniendo
el estilo de TurboDL:

- **Cuadrícula de 2 columnas** de tarjetas con **8 colores** (paleta
  propia: pastel en claro, oscuros armonizados en oscuro/AMOLED).
- **Dos tipos de nota**: texto y **lista de comprobación** (checkboxes
  con lo hecho tachado, añadir/quitar elementos, progreso "2/3" en la
  tarjeta).
- **Fijar arriba** (📌) — sección de fijadas siempre primera.
- **Búsqueda en vivo** por título, texto y elementos del checklist.
- **Orden**: fecha de modificación, fecha de creación o título (A-Z).
- **Papelera**: borrar suave con restauración, borrado definitivo y
  "vaciar papelera".
- **Compartir y copiar** (las listas se exportan como ☑/☐ texto).
- Editor a pantalla completa que **guarda al salir** (como ColorNote)
  y descarta las notas totalmente vacías; el selector de color se abre
  desde la barra del editor y tiñe la pantalla.
- Entrada con contador en la fila de Herramientas ("3 notas · 1 en
  papelera").

Los datos van en `NotesDb` (esquema v2 con migración ALTER TABLE sin
pérdida: las notas viejas quedan color naranja, tipo texto, no
fijadas). Como toda la base del navegador, **cada usuario tiene sus
propias notas** (sandbox por perfil).

## Tests

- `NotesDbAdvancedTest` (13 casos, nuevo): papelera suave/restaurar/
  vaciar, fijadas primero, color/tipo, migración desde notas v1,
  roundtrip JSON del checklist (incluida tolerancia a JSON corrupto),
  progreso y búsqueda dentro de elementos.
- `NotesDbTest` (CRUD 4.3) sigue en verde contra el esquema v2.
- Suite completa: **177 tests, 0 fallos**; versión 36 (4.17).

---

# TurboDL 4.16 — Notas de la versión

Fecha: 2026-11-26

## Bug 1: la app ya no se reabre sola tras cambiar de usuario

Reportaste que tras el mensaje «TurboDL se reinicia…» la aplicación
**no volvía a abrirse** y había que abrirla a mano (aunque el usuario
ya sí cambiaba). Causa: el arreglo de la 4.15 pasaba la app a segundo
plano y confiaba en una alarma (AlarmManager) para relanzarla, pero
**desde Android 10 arrancar una actividad desde segundo plano está
prohibido** (Background Activity Launch): la alarma disparaba, el
sistema bloqueaba el arranque y la app quedaba cerrada.

Arreglo estilo **ProcessPhoenix** (la librería canónica de reinicio en
caliente): el arranque se lanza **en primer plano y ANTES de matar el
proceso** (ahí arrancar sí está permitido); al morir el proceso con el
lanzamiento ya pedido, el sistema lo completa en un proceso nuevo,
donde `App.onCreate` vuelve a fijar la sandbox del usuario elegido.
Resultado: un solo reinicio, automático, sin alarmas ni fondo. La
alarma queda solo como red de seguridad si el arranque inicial fallara.
También se **persisten las pestañas** al pulsar CAMBIAR (antes quedaban
a merced de `onPause`).

## Bug 2: seleccionar el motor Brave por fin se refleja (y se usa)

Preguntaste si el motor Brave estaba implementado o era cosmético. Lo
está y funciona — motor nativo C++ `brave/ad-block` (AARs ad-filter +
adblock-client con `libadblock-client.so`, integrado en 4.11 y
predeterminado). El problema era otro: **en equipos con menos de 1,5 GB
de RAM, `usaBrave()` degradaba en silencio a FilterEngine (Kotlin)**.
Marcabas «Brave (nativo C++) — recomendado», quedaba marcado, pero el
toast decía «Motor: FilterEngine (Kotlin)» y la fila de Ajustes
también — tu elección no se reflejaba porque *no se usaba de verdad*:
no era un bug cosmético, el motor nativo no llegaba a arrancar en tu
equipo.

Ahora **se respeta siempre la elección**: elegir Brave sirve Brave,
también con RAM baja. Si el motor nativo no pudiera inicializar, el
respaldo existente a FilterEngine sigue cubriendo ese caso (con su
línea de estado en el diagnóstico). La RAM baja solo produce un **aviso
informativo** en el selector («carga algo más pesada; si no arranca, se
usa FilterEngine como respaldo»), nunca más un cambio de motor sin
decírselo al usuario.

## Extra: colisión de ids al crear dos usuarios muy seguidos

Un test destapó que dos usuarios creados en el mismo milisegundo
recibían el **mismo id** y el segundo machacaba al primero. `Users.add`
ya garantiza ids únicos manteniendo el formato `u<8 dígitos>`.

## Tests

- `EngineChoiceTest` (4 casos, nuevo): la elección del motor se persiste
  y `engineName` la refleja siempre — incluida la comprobación de
  regresión en entorno de RAM baja, exactamente el perfil de equipo donde
  la 4.15 servía FilterEngine en silencio.
- `UsersSandboxTest` (9 casos, +1): dos altas seguidas generan ids
  distintos.
- Suite completa en verde; sin cambios de esquema de BD ni permisos;
  versión 35 (4.16).

---

# TurboDL 4.15 — Notas de la versión

Fecha: 2026-11-26

## Arreglado: cambiar de usuario en el navegador no funcionaba

Bug reportado: al usar «Usuarios» dentro del navegador, la aplicación
se **reiniciaba dos veces** y al comprobar qué usuario estaba activo,
**seguía siendo el mismo**. Eran dos defectos de raíz distintos:

### 1. El cambio se escribía en disco "a medias" (usuario sin cambiar)

`Users.setActive` guardaba el usuario activo con `apply()`, una
escritura **asíncrona** que Android encola en un hilo de fondo. Pero
el cambio de usuario mata el proceso apenas ~50ms después
(`restartApp` → `System.exit`) para montar la sandbox del nuevo
usuario: si el hilo no había escrito todavía, **la escritura moría con
el proceso** y al arrancar `activeUser` seguía siendo el anterior —
por eso el usuario "no cambiaba" aunque la app se reiniciara. Ahora
`setActive` y `save` usan `commit()` **síncrono**: el valor está en
disco antes de que la función devuelva, así que ningún reinicio puede
perderlo.

### 2. Reinicio doble: el sistema relanzaba la app Y la alarma la relanzaba otra vez

`restartApp` mataba el proceso con la actividad en **primer plano**.
Android no puede dejar la pantalla sin tarea: al morir el proceso,
**relanzaba la app por sí solo** (primer reinicio). Un segundo después
disparaba la alarma programada por `restartApp` con
`FLAG_ACTIVITY_CLEAR_TASK`, que volvía a arrancar todo (segundo
reinicio). Ahora, **antes** de matar el proceso, la tarea se manda a
segundo plano (`moveTaskToBack`): el sistema ya no la relanza
automáticamente y la única que arranca la app es la alarma — **un solo
reinicio**, y más rápido (la alarma pasa de 1s a ~0,4s).

### Cómo verificarlo

En el navegador: ☰ → «👥 Usuarios» → selecciona otro usuario →
CAMBIAR. Debe verse UN solo reinicio y, al volver a abrir «Usuarios»
(o «👁 Sandbox»), el usuario activo debe ser el elegido: radio marcado
en su fila y su sesión (cookies, credenciales, historial) cargada en
el WebView.

### Tests nuevos: UsersSandboxTest (8 casos)

Suite Robolectric que fija el contrato del cambio de usuario:
`setActive` persiste de forma síncrona (releyendo desde una instancia
nueva de SharedPreferences, lo que hará el siguiente proceso), el
`remove()` del usuario activo reactiva «main» de inmediato (ese camino
también reinicia la app), redondos de `add`/`list`/`update` con nombre
y email, y precedencia de `displayOf` (email → nombre → id).

### Detalles técnicos

- El mecanismo de proceso nuevo (AlarmManager + `System.exit(0)`) se
  conserva: es necesario porque `WebView.setDataDirectorySuffix` solo
  puede fijarse una vez por proceso, antes de crear cualquier WebView.
- Sin cambios de esquema de BD ni permisos; versión 34 (4.15).

---

# TurboDL 4.14 — Notas de la versión

Fecha: 2026-11-23

## El atrás del teléfono ahora también lleva al menú principal

En la 4.13 el botón "App" de la barra ya volvía al menú principal, pero
el **atrás del sistema** (el botón físico/gesto de tu teléfono) seguía
teniendo el flujo antiguo: al agotar las capas del navegador te avisaba
con *"Pulsa atrás otra vez para salir"* y el segundo toque cerraba
TurboDL entera. Lo reportaste como mal flujo UX y tienes razón: si el
usuario quiere salir del navegador, lo natural es volver al menú de la
app, no cerrar el programa.

### Nuevo flujo del atrás del sistema (máquina de estados 4.0 U4)

El atrás se retira capa a capa, igual que en cualquier app bien
comportada, y **ya nunca cierra la aplicación ni pide confirmación**:

1. **Sugerencias de búsqueda visibles** → las cierra.
2. **Hoja de pestañas o menú ☰ abierta** → la cierra.
3. **Historial web disponible** → retrocede en la página actual.
4. **Vista de accesos del navegador** (Home interno) → si hay página
   cargada vuelve a ella.
5. **Fin de la pila** → **vuelve al MENÚ PRINCIPAL de TurboDL**:
   persiste las pestañas, abre MainActivity y cierra el navegador. Las
   pestañas abiertas se restauran la próxima vez que abras el
   navegador — no se pierde nada.

### Un único helper compartido: exitToMainMenu()

Las tres formas de salir del navegador al menú — botón "App" de la
barra inferior, celda "🏠 Menú principal" del menú ☰ y ahora el atrás
del sistema — ejecutan el mismo código (`exitToMainMenu()`): una sola
implementación, un solo comportamiento. «🚪 Salir» del menú ☰ sigue
existiendo y sigue cerrando la aplicación entera, para quien quiera
terminar la sesión completa.

### Detalles técnicos

- Eliminado el mecanismo de doble toque (`lastBack` + toast) del
  `onBackPressed` de BrowserActivity.
- `finish()` del navegador, nunca `finishAffinity()`: si
  MainActivity comparte tarea no se ve arrastrada.
- Sin cambios de esquema de BD ni permisos; versión 33 (4.14).

---

# TurboDL 4.13 — Notas de la versión

Fecha: 2026-11-23

## Volver al menú principal desde el navegador

Reportaste dos cosas en la barra inferior del navegador: que los dos
botones centrales hacían lo mismo y que el que está al lado del de
icono Home no se entendía. Tenías razón — los dos (⌂ y ▖) acababan
mostrando la misma vista de accesos *dentro* del navegador, y no
existía ninguna forma de volver al menú principal de la app (la única
salida era ☰ → Salir, que cierra TurboDL entera). Corregido:

### Barra inferior: cada botón hace una cosa distinta

- **‹ / ›** — atrás / adelante en el historial de la pestaña (sin
  cambios).
- **⌂ Home** — igual que siempre: 1er toque limpia la pestaña y muestra
  la vista de accesos del navegador; 2º toque la oculta. Es el Home
  *del navegador*, no de la app.
- **App** (antes ▖, el que no se entendía) — ahora es el botón que
  pediste: **vuelve al menú principal de TurboDL en un solo toque**.
  Cierra el navegador y abre el menú; antes de salir **persiste las
  pestañas** (se restauran al reabrir el navegador, no se pierde la
  sesión ni las URLs abiertas). La etiqueta de texto «App» sustituye al
  glifo ▖ que nadie podía interpretar.
- **[1] Pestañas** y **☰ Menú** — sin cambios.

### Nueva entrada «🏠 Menú principal» en el menú ☰

La misma acción, accesible desde el menú del navegador, colocada justo
encima de «🚪 Salir» (que sigue cerrando la aplicación entera). Así la
función se descubre aunque no sepas qué hace cada botón de la barra.

### Detalles técnicos

- El botón «App» y la celda «Menú principal» lanzan MainActivity
  (launchMode singleTask + NEW_TASK | CLEAR_TOP) y hacen finish() del
  navegador — sin finishAffinity(), para no arrastrar a MainActivity si
  comparten tarea.
- persistTabs() antes de salir: las pestañas viven en SharedPreferences
  (tabsV21) y restoreTabs() las recupera en el próximo arranque del
  navegador.
- Sin cambios de esquema de BD ni permisos; versión 32 (4.13).

---

# TurboDL 4.12 — Notas de la versión

Fecha: 2026-11-23

## Rediseño completo de la bóveda de credenciales

Pediste pulir la interfaz del gestor de credenciales inspirándote en
gestores de escritorio tipo KeePassXC y en apps de 2FA tipo Google
Authenticator (sin mencionarlos en ningún mensaje de la interfaz), con
carpetas anidadas, vista vertical, copiado rápido y anillo de tiempo
para el TOTP. Hecho — la bóveda se reescribió por completo manteniendo
el estilo general de TurboDL (tarjetas redondeadas, acento naranja del
tema claro/oscuro/AMOLED, tipografía configurable).

### Carpetas anidadas (grupos dentro de grupos)

- **Árbol de profundidad libre**: cada carpeta puede contener
  subcarpetas, igual que los grupos de un gestor de contraseñas
  clásico. Se guardan en una tabla nueva (`vault_grp`, Db v5) donde
  cada carpeta apunta a su padre; la migración es automática y las
  entradas existentes quedan en la raíz sin perder nada.
- **Navegación por niveles**: entras tocando la carpeta y subes con el
  botón ⬆ o tocando la ruta de **migas de pan** («Raíz / Trabajo /
  Bancos»), que también abre un salto directo a cualquier carpeta
  superior.
- **Gestión completa** desde el menú ⋮ de cada carpeta: abrir,
  renombrar, crear subcarpeta, **mover** (selector en árbol, con
  protección anti-ciclos: no puedes mover una carpeta dentro de sí
  misma) y eliminar. Al eliminar, las subcarpetas y entradas pasan a la
  carpeta superior — nada se borra por accidente.
- Las credenciales nuevas se crean en la carpeta que estés viendo y se
  pueden mover con ⋮ → Mover a…; el selector muestra el árbol completo
  con sangría.

### Vista vertical con todo a la vista

Cada credencial es ahora una **tarjeta vertical** que muestra TODOS sus
campos de un vistazo (antes había que pulsar botones para ver cosas):

- **Título** con avatar cuadrado de la inicial, y la ruta de su carpeta
  cuando buscas.
- **Usuario**, **contraseña**, **web** y **notas** en líneas
  independientes con su icono temático.
- La búsqueda (arriba) filtra en **TODAS** las carpetas y muestra la
  ruta de cada resultado.

### Iconos de acción en cada campo

- **Copiado rápido**: un icono junto a usuario, contraseña, código 2FA
  y web copia ese valor con un toque (antes: botones de texto «Usu.»/
  «Pass» agrupados arriba).
- **Seguridad**: las copias de valores sensibles se **autolimpian del
  portapapeles a los 60 s**, como hacen los gestores serios.
- **Mostrar/ocultar contraseña**: va enmascarada (••••••••) con icono de
  ojo para revelarla (descifrado solo bajo demanda, se vuelve a ocultar
  al reciclar la tarjeta).
- **Mostrar/ocultar código 2FA**: el apartado OTP tiene su propio ojo;
  el código no se calcula hasta mostrarlo (misma optimización de
  recursos de siempre).

### Anillo de tiempo del TOTP

Cada código 2FA visible lleva un **anillo circular** que nace lleno y
se va difuminando conforme pasa el periodo (30 s por defecto), con los
**segundos restantes dentro**; en los últimos 5 s el anillo se pone
rojo. Al llegar a cero el código se recalcula y el anillo se reinicia,
sincronizado con el ritmo exacto del TOTP — como en las apps de
autenticación modernas. El HOTP (contador) no lleva anillo y consume un
código por revelación, como antes.

### Detalles técnicos

- Db v5: `ALTER TABLE vault ADD COLUMN grp` + tabla `vault_grp`
  (anidadas por `parent`); migración con try por paso, sin pérdida de
  datos (test incluido que simula una BD v4 real).
- `TimerRingView` propio en Canvas (sin librerías): pista + arco con
  desvanecimiento de opacidad + segundos dentro.
- Un único handler de 250 ms reparte los ticks a los anillos visibles;
  los códigos se recalculan solo al cruzar el límite del periodo. Se
  detiene en onPause (sin timers zombis).
- 13 iconos vectoriales Material nuevos (ojo, copiar, carpeta, llave,
  globo…) tintados según el tema.
- Eliminar usuario también borra su bóveda completa (entradas +
  carpetas), como el resto de su sandbox.
- Tests nuevos `VaultGroupsTest` (6 casos): CRUD de carpetas anidadas,
  filtros por nivel, búsqueda global, re-parenteo al eliminar, limpieza
  por usuario y migración real v4→v5.

---

# TurboDL 4.11 — Notas de la versión

Fecha: 2026-11-23

## Motor de bloqueo nativo Brave (AdblockAndroid)

Pediste integrar la versión mejorada de AdblockAndroid
([reaver961025/AdblockAndroid v2.0.0](https://github.com/reaver961025/AdblockAndroid/releases/tag/v2.0.0))
como motor anti-ads del proyecto. Hecho: es ahora el **motor por
defecto** de TurboDL, con fallback automático a FilterEngine.

### Qué es este motor

Es un fork modernizado del AdblockAndroid de Edsuns, cuyo núcleo C++
se basa en **`brave/ad-block`** — el mismo motor de bloqueo que usa el
navegador Brave. El fork arregla fallos fatales del original y mejora
el análisis de reglas ~40x. Soporta EasyList y filtros AdGuard, con
ocultamiento de elementos, CSS extendido, reglas de scriptlets y
descarga de suscripciones en segundo plano (WorkManager).

A diferencia de la librería oficial de Adblock Plus (que en su día
intentamos integrar y nunca se pudo resolver de Maven), **esta sí está
compilada**: el release v2.0.0 incluye las AARs preconstruidas con el
binario nativo para arm64-v8a, armeabi-v7a, x86 y x86_64, y van vendidas
en `app/libs/` (compilación offline, como el resto de libs vendidas).

### Los tres motores, ahora así (Ajustes → Navegador → Motor de bloqueo)

| Motor | Qué es | Estado |
|---|---|---|
| **Brave (nativo C++)** — **default** | Motor de Brave vía JNI (AdblockAndroid) | Recomendado |
| FilterEngine (Kotlin) | El motor propio, ligero, sin nativos | Ligero / fallback |
| Solo hosts | Solo listas de dominios (StevenBlack) | Mínimo |

- **Nueva instalación o usuario sin preferencia → motor Brave**. Quien
  ya eligió un motor explícitamente en 4.9/4.10 conserva su elección.
- En equipos con **< 1,5 GB de RAM** la preferencia Brave se sirve con
  FilterEngine (el motor C++ mantiene las listas en memoria nativa).
- Si el motor nativo no inicializara en algún dispositivo, la fachada
  cae automáticamente a FilterEngine (patrón self-test/fusible que ya
  existía, extendido a ambos motores).

### Integración técnica

- **Suscripciones unificadas**: el catálogo de TurboDL (Ajustes →
  Listas de filtros) sigue siendo la única fuente de verdad. Las listas
  EasyList/AdGuard marcadas se convierten en suscripciones del motor
  Brave (addFilter es idempotente por URL); las formato hosts
  (StevenBlack) solo las aplica FilterEngine — el motor Brave no lee
  ese formato, y Ajustes avisa si solo hay hosts marcadas.
- **`BraveEngine.kt`**: wrapper propio con la misma disciplina de la
  fachada — todo con `catch(Throwable)`, shouldBlock falla abierto
  (deja pasar) si el motor no está listo, fusible anti-sobre-bloqueo
  por sesión compartido, y nunca se usa el variante de
  `shouldIntercept` que hace `runBlocking` hacia el hilo principal.
- **Cosméticos**: en modo Brave, el ocultamiento de elementos y los
  scriptlets los inyecta el motor nativo en `onPageStarted`
  (performScript), con mejor cobertura que el CSS propio (extended CSS
  + scriptlets); el CSS de FilterEngine no se duplica.
- **Escudo**: el panel muestra la regla real que devolvió el C++
  (FilterResult.rule) cuando el bloqueo viene de Brave.
- **R8/ProGuard**: keeps explícitos para el paquete del cliente
  (el constructor JNI de MatchResult), los workers de WorkManager y
  las clases generadas por Mezzanine (scriptlets embebidos).
- **Dependencias nuevas**: kotlinx-coroutines 1.8.0,
  kotlinx-serialization-json 1.6.3, WorkManager 2.9.1, Timber 5.0.1 y
  HttpRequest (com.github.Edsuns, vía JitPack). Las clases generadas
  por Mezzanine ya vienen dentro de la AAR (no hace falta el plugin).

### Licencia

El motor es **LGPL-2.1** (se conserva del upstream y del
`brave/ad-block` del que deriva). Va dinámicamente enlazado como AAR
separada, con su código fuente público en GitHub; los créditos y la
licencia del fork están en el repositorio upstream. El resto de
TurboDL no se ve afectado.

### Notas

- APK: los binarios nativos del motor (~1 MB por ABI, 4 ABIs) añaden
  ~2 MB comprimidos al APK.
- Tests del motor (FilterEngine, HostsAdapter, AdblockCatalog,
  AdBlockTypeOf, ExemptDomain) — todos pasan.
- El primer arranque tras actualizar: el motor Brave descarga sus
  listas (EasyList + EasyPrivacy) vía WorkManager; hasta que terminen
  (~segundos con buena red), las peticiones pasan sin bloquear, igual
  que la ventana de "cargando motor…" histórica.

---

# TurboDL 4.10 — Notas de la versión

Fecha: 2026-11-22

## Bloqueo de popups de imágenes molestos

Reportaste: *"Muchos sitios me generan popup de imágenes molestos que
incluso dicen que son ads, ¿esto se puede solucionar?"*. Sí, y en gran
parte se ataca con reglas cosméticas + de red que el motor ya soporta
pero no cargaba por defecto. La 4.10 activa las listas anti-popup,
filtra los `window.open` reales contra el adblock y neutraliza los
popups automáticos con JS.

### Tres tipos de popup y cómo se ataca cada uno

1. **Popups cosméticos** (los más comunes): un `<div>` overlay con una
   imagen y un botón "X" minúsculo. No abren ventana nueva, se
   superponen. Se arreglan con reglas `##selector` de ocultamiento —
   ya soportadas por `FilterEngine`.
2. **Popups/Pop-unders reales (`window.open`)**: abren pestaña o
   ventana nueva con una landing de anuncio. Se arreglan con reglas de
   red `$popup` bloqueando el host destino **antes** de abrir.
3. **"Notificaciones push" falsas**: en Android WebView no existe la
   API `Notification` web, así que lo que veías eran en realidad
   popups cosméticos (#1). No hace falta un toggle de notificaciones.

### Qué se cambió

**1. Listas anti-popup activadas por defecto** (`AdblockCatalog.kt`)

El `DEFAULT_IDS` seguía siendo `{easylist, easyprivacy}` desde la 4.2.
AdGuard Annoyances existía en el catálogo pero no se cargaba si el
usuario no la marcaba a mano. Ahora el default es:
`{easylist, easyprivacy, adguard-annoyances, antiadblock}`.

- **AdGuard Annoyances** — popups, banners de cookies, widgets
  sociales intrusivos, notificaciones push falsas. La clave para tu
  problema.
- **Adblock Warning Removal List** (nueva entrada en el catálogo) —
  quita los carteles "Desactiva tu adblock para continuar" que algunos
  sitios muestran cuando detectan bloqueo.

Los usuarios existentes no pierden su selección guardada; solo una
instalación nueva (o quien no haya tocado "Listas de filtros") coge
el nuevo default. Para sacarle provecho ya: Ajustes → Navegador →
Listas de filtros → marca AdGuard Annoyances + Adblock Warning Removal.

**2. Filtro de `window.open` en `onCreateWindow`** (`BrowserActivity.kt`)

Hasta 4.9, los popups con gesto de usuario (clic) se abrían sin
preguntar al adblock. Ahora, antes de abrir la nueva pestaña, se
consulta `AdBlockEngine.shouldBlock(pageUrl, popupUrl, "popup")`. Si
el motor lo marca (reglas `$popup` de EasyList/AdGuard Annoyances),
se descarta el popup y se cuenta como bloqueado, igual que un
popunder sin gesto.

**3. Popup blocker en JS** (extensión del `HOOK` inyectado)

`window.open` se intercepta desde JavaScript. La heurística es
doble:
- Si el host destino está en el motor de bloqueo (vía bridge
  `shouldBlockPopup` → `AdBlockEngine.shouldBlock(..., "popup")`),
  bloquear.
- Si no hubo gesto real del usuario en los últimos 1,5 s
  (popunders automáticos), bloquear.

Los popups abiertos con un clic verdadero a un host NO bloqueado
pasan. Los automáticos o hacia hosts de anuncios se devuelven `null`
y no abren. El bridge se añadió como `@JavascriptInterface` en la
clase `Bridge` ya existente.

### Notas técnicas

- `FilterEngine.kt` no se tocó: ya soportaba `$popup` (tipo bit 256)
  desde la 3.6.0. El cambio es solo en qué listas se cargan y cómo se
  usa el motor.
- `onCreateWindow` ahora llama a `tabOf(view)?.url` para conocer la
  página origen del popup y respetar las exenciones por sitio.
- El `HOOK` se inyecta en `onPageStarted` ya existente; el bloque
  `window.open` corre dentro del mismo IIFE que el sniffer de medios.
- Tests del motor (`FilterEngine`, `HostsAdapter`, `AdblockCatalog`,
  `AdBlockTypeOf`) — todos pasan.
- APK 16,6 MB, mismo certificado que 4.9 (instalable como
  actualización).

---

# TurboDL 4.9 — Notas de la versión

Fecha: 2026-11-22

## Selector de motor de bloqueo + fix del "Adblock no instalado"

Pediste: *"investiga los motores de bloqueo disponibles… la app dice que
Adblock no está instalado, y quiero elegir el motor en Ajustes"*. La 4.9
elimina el código muerto del motor oficial de Adblock Plus (que nunca
llegó a empaquetarse) y añade un selector real con dos motores.

### Por qué decía "Adblock no instalado" (y ya no lo dice)

El proyecto tenía un wrapper (`AdblockPlusEngine.kt`) que cargaba por
reflexión la librería nativa `libadblockplus-android` (el motor oficial
de Adblock Plus, que usa V8). Esa librería se publicaba en `jcenter`,
que cerró en 2021; ningún mirror la resuelve, así que las dependencias
llevaban años comentadas en el `build.gradle`. El wrapper siempre
detectaba "no instalado" y el navegador mostraba
**"Adblock Plus no instalado (modo FilterEngine)"** aunque el bloqueo
sí funcionara — lo hacía `FilterEngine`, el motor Kotlin.

Lo que cambié:
- **Eliminé `AdblockPlusEngine.kt`** (280 líneas de código muerto) y las
  9 referencias que tenía en `AdBlockEngine.java`.
- El diagnóstico ya **no menciona Adblock Plus**: ahora dice
  `Motor: FilterEngine (Kotlin)` o `Motor: Solo hosts`, con el estado
  real (LISTO / NO DISPONIBLE / cargando).

### Selector de motor en Ajustes → Navegador → 🧱 Motor de bloqueo

Dos motores reales, elegibles por el usuario:

| Motor | Qué carga | Pros | Contras |
|---|---|---|---|
| **FilterEngine (Kotlin)** (recomendado, default) | Todas las listas seleccionadas: EasyList, Easy Privacy, AdGuard, Fanboy, EasyList Spanish + hosts (StevenBlack) | Bloqueo de red + cosméticos (ocultar elementos), reglas finas | Más reglas en RAM (~10–20 MB) |
| **Solo hosts** | Solo listas `Format.HOSTS` (StevenBlack) | Mínimo, sin falsos positivos, ideal para RAM baja | Solo bloquea dominios enteros; sin cosméticos ni reglas finas |

Al cambiar el motor, se persiste en `SharedPreferences("cfg")` clave
`adblockEngine` y se **recarga en segundo plano** (no hace falta
reiniciar). En modo "Solo hosts", el aviso de Ajustes te recuerda
marcar una lista tipo hosts (StevenBlack) si no hay ninguna.

### Investigación previa (por qué no se empaquetó la lib nativa)

Revisé 6 alternativas externas del chat de DeepSeek:
- **AdblockAndroid (Edsuns)** — basada en Brave, vía JitPack. Descartada:
  abandonada desde 2021, licencia LGPL-2.1 (copyleft fuerte), tag `1.0`
  único sin mantenimiento.
- **Adblock Plus AdblockWebView** — la oficial. Descartada: jcenter
  retirado, sin mirror.
- **DevSurf / EinkBro** — navegadores con motor embebido, no publican
  AAR. Extraerlas a mano son días de trabajo sin mantenimiento futuro.
- **Brave ad-block (C++ puro)** — requeriría JNI + NDK, semanas de
  integración, +5–8 MB.
- **marcelbohland/Android-Webview-Adblock** — ejemplo didáctico, sin
  cosméticos.

Conclusión: FilterEngine ya cubre el 90% del bloqueo real (red +
cosméticos simples) sin pesar nada. Las librerías externas están
muertas, no publicadas o requieren empaquetar 30 MB nativos. La opción
sana es quedarse con lo que funciona y dejar al usuario elegir entre
completo y mínimo.

### Notas técnicas

- `AdBlockEngine.java` ahora tiene `selectedEngine(ctx)` /
  `setEngine(ctx, id)` / `engineName(ctx)` para el selector.
- `init()` y `updateLists()` filtran las listas según el modo: en
  "hosts" se saltan las EasyList (no se descargan, no se cargan en
  RAM). En "kotlin" se cargan todas las seleccionadas.
- El self-test de arranque sigue igual: valida que el motor bloquea
  un script de anuncio y deja pasar un CSS normal antes de marcar
  READY. Las listas de hosts (StevenBlack) incluyen los dominios de
  publicidad grandes, así que el test pasa en ambos modos.
- `FilterEngine` y `HostsAdapter` no se tocaron: son los que ya
  funcionaban. El cambio es solo en el facade y la UI.

---

# TurboDL 4.8 — Notas de la versión

Fecha: 2026-11-22

## Caché HTTP con ETag: menos datos al recargar

Pediste: *"navegador con caché con etag u otra alternativa para
disminuir el consumo de datos y que al recargar la página no se
descargue todo de nuevo"*. La 4.8 añade un interceptor de caché HTTP
propio en el `shouldInterceptRequest` del WebView que guarda los
recursos y los revalida con el servidor antes de volver a bajarlos.

### Cómo ahorra datos

Cada recurso GET del navegador pasa por el caché:

- **Recurso fresco** (dentro de su `max-age`): se sirve desde disco
  **sin ir a la red**. Ahorro total: 0 bytes transferidos.
- **Recurso vencido**: se revalida enviando `If-None-Match: <ETag>` y
  `If-Modified-Since: <fecha>`. Si el servidor responde
  **304 Not Modified**, se entrega el caché sin descargar el cuerpo
  (solo ~300 bytes de headers vs. los MB que pesa una página entera).
- **Sin red** (excepción de conexión): sirve el caché stale como
  paliativo en modo offline, en vez de mostrar error.

El frame principal también se cachea — es lo que más pesa y lo que
más cambia entre recargas, así que es donde más se nota. El bloqueador
de anuncios sigue corriendo, pero solo sobre recursos no cacheados.

### Respeto a las cabeceras del servidor

- `Cache-Control: no-store` / `no-cache` y `Pragma: no-cache` → no se
  cachea (páginas sensibles, banca, login).
- Se guardan ETag, Last-Modified, max-age y Expires de la respuesta.
- Se reenvían las cookies del `CookieManager` del WebView (sesiones,
  logins) y los headers del navegador (User-Agent, Accept, etc.).

### Control y visibilidad

En **Ajustes → Navegador** hay ahora tres filas:

- **📦 Caché HTTP (ETag)** — activar/desactivar. Muestra el tamaño
  actual del caché y los **bytes ahorrados** desde que se instaló.
- **🧹 Vaciar caché HTTP** — borra el caché del usuario activo.

El caché está **aislado por perfil de usuario** (cada usuario tiene
su propio directorio bajo `dataDir/httpcache/usr_<id>/`) y tiene un
límite de 80 MB con expulsión LRU, así que no crece sin control.

### Notas técnicas

- Implementado en `util/HttpCache.kt` con `HttpURLConnection` (sin
  añadir OkHttp ni dependencias externas — coherente con el resto
  del app, que es Kotlin puro). Clave de entrada = SHA-1 de la URL.
- Range (vídeo/streaming) y métodos no GET se ignoran: los maneja
  el WebView nativamente, que ya tiene su caché de medios.
- Se fuerza `Accept-Encoding: identity` para guardar el cuerpo final
  (sin gzip): el ahorro real viene del 304, no de la compresión.

---

# TurboDL 4.7 — Notas de la versión

Fecha: 2026-11-22

## Barra del navegador con título de página, selección total al editar y URL a pantalla completa

Pediste: *"mejores la barra de búsqueda del navegador... una vez que uno
entre a un sitio la barra te muestre el titulo de la página no la URL,
que cuando se toque está barra se seleccione todo el texto para o bien
ser reemplazado rápidamente o ser copiado o cortado y otra acción
contextual, además para urls muy largas hay un boton que lo muestra todo
a pantalla completa"* — con capturas del navegador de referencia. La 4.7
replica exactamente ese comportamiento.

### La barra ahora tiene dos modos

- **Modo reposo (navegando)**: muestra el **TÍTULO de la página** —
  "Release TurboDL 4.6 · aldan9610/TurboDL" en vez de
  `https://github.com/aldan9610/TurboDL/releases/tag/v4.6`. Más legible,
  y la URL completa sigue disponible al tocar la barra o en el menú
  long-press. Si la página no da título, muestra la URL como fallback.
- **Modo edición (al tocar)**: la barra cambia a la **URL completa** con
  **TODO SELECCIONADO** (highlight verde, como en tu captura):
  - Escribir un carácter **reemplaza la URL entera** — navegación rápida
    como Chrome/Firefox.
  - La selección activa el **menú contextual nativo** del EditText:
    cortar, copiar, compartir, pegar — la "otra acción contextual" que
    pediste, sin pulsaciones largas.
  - Debajo aparece la **tarjeta de la página actual** (📄 título + URL):
    un toque para volver a donde estabas, como en tu captura.
- **Al perder el foco**: vuelve al modo reposo (título), descartando lo
  tecleado si no se navegó — igual que Chrome. Si había una carga en
  curso, muestra la URL hasta que llegue el título nuevo.

### ⇱ URL a pantalla completa (para URLs larguísimas)

En modo edición, cuando la URL no cabe en la barra (más de ~45
caracteres), aparece el botón **⇱** junto a la X de limpieza. Abre un
diálogo a ancho completo con la URL **envuelta en varias líneas y
seleccionada** (como tu tercera captura):
- **→ Ir** — navega a lo que quede escrito en el diálogo (también sirve
  para editar la URL ahí mismo, cómodo con texto largo).
- **📋 Copiar** — al portapapeles con un toque.
- **⇲ Colapsar** — volver a la página.

### Título en vivo y por pestaña

- El título se actualiza en vivo vía `WebChromeClient.onReceivedTitle`
  (páginas que lo fijan por JS / SPAs, donde llega **después** de
  `onPageFinished`) — sin pisar al usuario si está editando la barra.
- Al **cambiar de pestaña**, la barra muestra el título de la pestaña
  destino (antes mostraba su URL).
- El long-press (Compartir enlace / Copiar URL / Pegar y abrir) sigue
  funcionando igual, y la opción **"Editar URL (sin borrar)"** ahora
  cambia primero a modo URL antes de poner el cursor al final (antes
  editarías el título).

### Internas

- versionCode 26, versionName 4.7.
- `browser.xml`: nuevo botón `btnUrlExpand` (⇱) en la barra superior.
- `BrowserActivity`: helpers `setBarDisplay()` / `setBarEdit(selectAll)` /
  `restoreBarAfterEdit()` / `updateExpandBtn()` / `showCurrentPageCard()` /
  `showFullUrl()`; `onPageFinished` y `switchTab` muestran el título;
  `onReceivedTitle` en el `WebChromeClient` refresca en vivo; las
  sugerencias ponen la página activa primera (con dedupe contra
  historial/marcadores).
- **145 tests JVM pasan** (0 fallos). Misma firma que 4.3–4.6 → se
  actualiza sin desinstalar. Android 8.0+.

### Instalación

```
adb install -r TurboDL-4.7.apk
```

---

# TurboDL 4.6 — Notas de la versión

Fecha: 2026-11-22

## Bóveda de credenciales estilo KeePassXC + códigos OTP para 2FA

Pediste: *"Incorporar una utilidad de poder guardar credenciales estilo
lo que hace keepassxc fácilmente accesible dentro de la aplicación
incluyendo generación de códigos OTP para logearme fácilmente a sitios
con 2auth dentro de la aplicación (tip de optimización: no generar estos
códigos siempre sino cuando se entre a la interfaz que los muestra no
vaya a ser que consuman muchos recursos computacionales en caso de
haber muchas credenciales)"*. La 4.6 añade una **bóveda de credenciales**
con **generación de códigos OTP** (TOTP/HOTP) para 2FA, accesible desde el
navegador y desde Herramientas, con la optimización que pediste: **los
códigos OTP no se generan siempre, solo al pulsar "Ver código"**, y se
recalculan **solo en el límite del step** (cada 30s), no cada tick.

### Qué guarda la bóveda

Por cada entrada:
- **Título** (obligatorio) — p. ej. "Gmail", "GitHub".
- **Usuario / email** — para copiar con un toque.
- **Contraseña** — cifrada con `Secrets` (AES-256-GCM, clave en
  AndroidKeyStore). Se copia con un toque.
- **Sitio web (URL)** — opcional; se copia con un toque.
- **Doble factor (OTP)** — opcional:
  - Tipo: **Ninguno / TOTP (tiempo, 30s) / HOTP (contador) / Steam Guard**.
  - Algoritmo HMAC: **SHA1** (estándar de Google Authenticator),
    **SHA256** o **SHA512**.
  - Dígitos: **6** (por defecto), **7** u **8** (algunos servicios).
  - Secret OTP en **Base32** (lo estándar), **hex** (`hex:`), texto
    plano (`plain:`), o pega directamente un **`otpauth://` URI** completo
    (el que dan los QR de 2FA) y la app lo descompone solo.
- **Notas** — texto plano (no guardes secretos aquí).

### Códigos OTP (2FA)

- **TOTP** (RFC 6238) — el estándar de Google Authenticator, KeePassXC,
  Aegis, 1Password, Authy, etc. Código basado en el tiempo, cada 30s.
- **HOTP** (RFC 4226) — código basado en contador; al pulsar "Ver código"
  se incrementa el contador y se persiste en la BD (como esperan los
  servidores HOTP).
- **Steam Guard** — variante de TOTP con 5 dígitos alfanuméricos
  (alfabeto propio de Steam), compatible con Aegis/KeePassXC.
- Implementación **100% Kotlin** (`util/Otp.kt`), sin dependencias
  externas: HMAC-SHA1/256/512, Base32 (RFC 4648), truncado dinámico
  RFC 4226 §5.3, y parsing de `otpauth://` URIs.
- **Validado contra los vectores oficiales** del RFC 6238 (Apéndice B)
  y RFC 4226 (Apéndice D) con 16 tests JVM.

### 🔧 Optimización de recursos (lo que pediste)

> *"no generar estos códigos siempre sino cuando se entre a la
> interfaz que los muestra, no vaya a ser que consuman muchos recursos
> computacionales en caso de haber muchas credenciales"*

Implementado así:
1. **La lista de entradas no descifra nada ni calcula OTP.** Solo
   muestra título/usuario (texto plano en BD). El descifrado del secret
   y el HMAC **solo ocurren al pulsar "Ver código"** en una entrada.
2. **Solo una entrada muestra su OTP a la vez** (la última abierta);
   al abrir otra, la anterior se oculta y cancela su timer. Esto evita
   N timers simultáneos cuando hay muchas credenciales.
3. Cuando se muestra un código TOTP, se calcula **una vez** y se
   reprograma **un único** `Handler.postDelayed` en el **límite del
   step siguiente** (cada 30s), no cada tick. Entre recalculadas **no
   hay CPU gastada**.
4. Al `onPause` (salir de la bóveda) o reciclar la vista, el callback
   se cancela. No quedan timers zombis recorriendo la bóveda.
5. HOTP no tiene timer: el siguiente código se obtiene al pulsar de
   nuevo (e incrementa el contador).

### Acceso rápido

Dos puntos de entrada:
- **Navegador** → menú inferior → celda **🔑 Bóveda** (junto a Ajustes).
- **Herramientas** → **🔐 Bóveda de credenciales (KeePassXC + OTP)**.

### Cómo usarlo

1. Abre la bóveda (desde el navegador o Herramientas).
2. Pulsa **"+ Añadir"** → rellena título, usuario, contraseña.
3. Para 2FA: elige el tipo (TOTP normalmente), pega el secret Base32
   (o el `otpauth://` URI completo de tu QR) → Guardar.
4. En la lista, pulsa **"Ver código"** en la entrada para ver el OTP
   actual (recalcula solo en el límite del step) y **"Copiar"** para
   llevarlo al portapapeles. **Usu.** y **Pass.** copian usuario y
   contraseña; **URL** copia el sitio.
5. Toca la fila para editar; mantén pulsado para... (en la 4.7: borrado
   con long-press; por ahora se edita y se deja en blanco).

### Seguridad

- Contraseñas y secrets OTP se guardan **cifrados** con `Secrets`
  (AES-256-GCM, clave en AndroidKeyStore) — mismo mecanismo que las
  credenciales HTTP de la tabla `creds`.
- La contraseña y el secret **no se descifran nunca al mostrar la
  lista**; solo bajo demanda (al pulsar el botón).
- Cada usuario del sandbox tiene su propia bóveda (campo `user` en la
  tabla, igual que `creds`/`history`).

### Internas

- `versionCode` 25, `versionName` 4.6.
- Nueva clase `util/Otp.kt` — TOTP/HOTP, Base32, parseo de otpauth.
- Nueva clase `util/Clipboard.kt` — helper copy/paste (antes duplicado
  inline en 3 sitios).
- Nueva `ui/VaultActivity.kt` — bóveda con lista, diálogo add/edit y
  OTP bajo demanda (Handler recalcula solo en step boundary).
- `db/Db.kt` — versión 3 → 4: nueva tabla `vault` + DAO
  `saveVault`/`vaultAll`/`vaultGet`/`deleteVault`/`vaultCount`/
  `vaultNextCounter` + `VaultRow`.
- `ToolsActivity` + `tools.xml` — botón "🔐 Bóveda de credenciales".
- `BrowserActivity` — celda "🔑 Bóveda" en el menú inferior.
- `AndroidManifest.xml` — declarada `VaultActivity`.
- **145 tests JVM pasan** (0 fallos): 129 existentes + 16 nuevos de OTP
  (vectores oficiales RFC 6238 y RFC 4226).

### Instalación

```
adb install -r TurboDL-4.6.apk
```
Misma firma que 4.3/4.4/4.5 → se actualiza sin desinstalar. Android 8.0+.

---

# TurboDL 4.5 — Notas de la versión

Fecha: 2026-11-22

## Proxy de red (HTTP, HTTPS-proxy, SOCKS4, SOCKS5) para todo el tráfico

Pediste: *"añadir la posibilidad de navegar usando un proxy http, socks
y alguna otra variante de proxy que pueda existir"*. La 4.5 añade un
proxy configurable que enruta **TODO el tráfico** de TurboDL —navegador y
motor de descargas— a través de un proxy de red.

### Tipos soportados

- **HTTP** — proxy HTTP clásico (CONNECT para HTTPS). El más común.
- **HTTPS-proxy** — proxy que a su vez habla TLS (RFC 2817). Útil cuando el
  proxy está en una red que inspecciona el tráfico.
- **SOCKS4** — proxy SOCKS4 (sin credenciales; SOCKS4 no las admite).
- **SOCKS5** — proxy SOCKS5 con auth opcional (usuario/contraseña).
- **Ninguno** — conexión directa (por defecto).

### Qué tráfico se enruta por el proxy

TODO el tráfico de red de la app:

- **Navegador (WebView):** TODA la carga —página + subrecursos (HTML, CSS,
  JS, imágenes, XHR, fetch)— por `androidx.webkit ProxyController
  .setProxyOverride` (API oficial de Android 9+). Requiere Android 9+ con
  WebView actualizable; en Android 8 el navegador usa el proxy del sistema.
- **Descargas HTTP/HTTPS:** `HttpDownloader`, `HlsDownloader`, `DashDownloader`.
- **Sonda de medios (MediaProbe):** el chequeo de cabeceras/rangos antes
  de descargar MP4/HLS/DASH.
- **Subtítulos (Subs):** las descargas de .vtt/.srt.
- **Metadatos .torrent (TorrentDownloader):** la descarga del .torrent por HTTP.
- **Favicons:** los iconos de los sitios.
- **Actualizaciones:** la comprobación de nuevas versiones y la descarga
  del APK de actualización.
- **FTP/FTPS:** vía SOCKS (`Socket(Proxy)` en los sockets de control y
  datos). FTP **no admite proxy HTTP** (HTTP CONNECT no encaja con el
  protocolo FTP de control+datos); para FTP usa SOCKS4 o SOCKS5.

### Credenciales del proxy

- HTTP, HTTPS-proxy y SOCKS5 admiten usuario/contraseña.
- SOCKS4 **no admite credenciales** (la UI lo avisa si intentas meterlas).
- La contraseña se guarda **cifrada** con `Secrets.encrypt` (AES-256-GCM,
  clave en AndroidKeyStore) — el mismo mecanismo que las credenciales
  HTTP de la tabla `creds`.

### Cómo funciona

- **Motor de descargas:** cada `URL.openConnection()` se hace con
  `Proxy.openConnection(javaProxy)` cuando hay proxy. Un
  `Authenticator` global (instalado en `App.onCreate`) responde a las
  solicitudes de auth del proxy (Basic/Digest para HTTP CONNECT,
  user/pass para SOCKS5) sin tocar la auth del servidor final.
- **WebView:** `ProxyController.setProxyOverride` aplica la regla proxy a
  toda la carga del WebView (página + subrecursos). El proxyUrl lleva el
  esquema (`http://`, `https://`, `socks5://`) y, opcionalmente, el
  `user:pass` embebido (URL-codificado). Es global a la app: no se
  configura por WebView, se aplica una vez al arrancar y al cambiarlo.

### UI

Ajustes → nueva sección **"Red"** → **🛰 Proxy**. Diálogo con:
- **Selector de tipo** (Ninguno / HTTP / HTTPS-proxy / SOCKS4 / SOCKS5).
- **Host** (IP o dominio).
- **Puerto** (1-65535).
- **Usuario** (opcional; deshabilitado para SOCKS4).
- **Contraseña** (opcional; cifrada al guardar).
- Texto explicativo de las limitaciones (FTP solo SOCKS; navegador
  requiere Android 9+).

Al guardar, se reinstala el `Authenticator` y se reaplica
`ProxyController` en caliente (sin reiniciar la app).

### Configuración

- `SharedPreferences "cfg"`:
  - `proxyType`: "none" | "http" | "https" | "socks4" | "socks5".
  - `proxyHost`, `proxyPort` (Int 1-65535).
  - `proxyUser`.
  - `proxyPassEnc` (cifrada con `Secrets`).
- Nueva clase `util/Proxy.kt` centraliza toda la lógica.
- Nueva dependencia `androidx.webkit:webkit:1.11.0` (para
  `ProxyController`/`ProxyConfig`).

### Internas

- `versionCode` 24, `versionName` 4.5.
- `Proxy.javaProxy(ctx)` → `java.net.Proxy` (HTTP→Type.HTTP,
  SOCKS→Type.SOCKS) para el motor de descargas.
- `Proxy.javaSocket(ctx)` → `Socket(Proxy)` para FTP (solo SOCKS).
- `Proxy.installAuthenticator(ctx)` → `Authenticator.setDefault` global
  (solo responde a `RequestorType.PROXY`, no a auth de servidor).
- `Proxy.applyToWebView(ctx)` → `ProxyController.setProxyOverride` /
  `clearProxyOverride` con `ProxyConfig.Builder.addProxyRule`.
- Puntos tocados: `Downloader.open`, `MediaProbe.open`, `Subs.getText`,
  `TorrentDownloader`, `Favicons.fetchAsync`, `Updater.latest` y
  `Updater.downloadAndInstall`, `FtpDownloader` (sockets ctl+data),
  `App.onCreate` (instalación al arrancar), `SettingsActivity`
  (sección "Red" + diálogo).
- 129 tests JVM pasan (0 fallos).

### Instalación

```
adb install -r TurboDL-4.5.apk
```
Misma firma que 4.3/4.4 → se actualiza sin desinstalar. Android 8.0+.

---

# TurboDL 4.4 — Notas de la versión

Fecha: 2026-11-22

## Cambio de usuario funcional (sandbox real, estilo lfbrowser)

Reportaste: *"para la próxima versión hagas funcional la utilidad de
cambiar de usuario; actualmente solo me dice un mensaje pero quiero que
sea funcional: lo que debe hacer es aislar el entorno del navegador
(cookies, etc.) para poder usar múltiples credenciales, por ejemplo de
Google y otros servicios, y poder cambiar de cuentas fácilmente. Una
funcionalidad similar a la que tiene lfbrowser"*.

### Causa raíz: por qué "solo mostraba un mensaje"

El cambio de usuario **ya existía** (no era solo un toast), pero era
**frágil y engañoso**: `restartApp` hacía `Process.killProcess(myPid())`
tras `sleep(120)`. Android suele **reusar el proceso reciclado**, así que
`App.onCreate` se volvía a correr en el mismo proceso y
`Users.applyDataDirSuffix` veía `appliedSuffix != null` (el sufijo del
usuario anterior ya fijado) y **dejaba el sufijo anterior** sin cambiarlo.
Resultado: `SharedPreferences.activeUser` decía "usuario B activo", pero el
`WebView` seguía usando el directorio del usuario A → las cookies y la
sesión de Google seguían siendo las del usuario A. El usuario lo percibía
como "solo me dice un mensaje, no cambia nada".

### Solución 1: reinicio robusto con AlarmManager + System.exit(0)

`Users.restartApp` ahora:
1. Vuelca las cookies del usuario saliente a disco con
   `CookieManager.getInstance().flush()` (la sesión del usuario que sale
   queda persistida en su sandbox).
2. Programa con `AlarmManager` un relanzamiento del launcher intent dentro
   de ~1 segundo (`RTC_WAKEUP`, `PendingIntent.getActivity`).
3. Llama a `System.exit(0)`. A diferencia de `killProcess`, `exit` termina
   la VM de forma que Android **no puede reusar** la instancia de
   `Application`: el siguiente `App.onCreate` crea un proceso **nuevo** y
   `applyDataDirSuffix` fija el sufijo del nuevo usuario correctamente.

### Solución 2: UI de usuarios reescrita estilo lfbrowser

La UI anterior era un `AlertDialog` simple con una lista de texto y
acciones ocultas al final (renombrar, ver sandbox, eliminar). Se reescribe
como un **diálogo a pantalla completa** inspirado en lfbrowser:

- **Título "USUARIOS"** en negrita, con acción **"AGREGAR"** (azul) arriba
  a la derecha.
- **Texto descriptivo**: "Puedes cambiar entre múltiples cuentas de
  usuario en este navegador. Cada una mantiene sus propias credenciales."
- **Lista de filas**, cada una con: **avatar circular** (silueta de
  persona sobre círculo azul claro) + **identificador del usuario
  subrayado** (el correo, como en lfbrowser) + **radio button exclusivo**
  (solo uno marcado).
- **Botones CANCELAR (gris) / CAMBIAR (naranja)** abajo. Pulsar CAMBIAR
  aplica el cambio al usuario seleccionado y reinicia la app.
- **Mantén pulsada** una fila para editarla (renombrar / cambiar email /
  eliminar) — no aplica al usuario "Principal".

### Solución 3: identificador de usuario (email)

La clase `Users.U` ahora lleva un **`email`** además del `name` (etiqueta
libre). Se persiste en el JSON con key `"e"` (backward-compatible: los
usuarios viejos sin email se muestran con el nombre como fallback). Así
cada cuenta puede identificarse por su correo (como en lfbrowser). El
diálogo de **"AGREGAR"** pide nombre + correo; el de **editar** permite
cambiar ambos.

### Flujo completo de cambio de cuenta (ej: dos cuentas de Google)

1. Menú → **👥 Usuarios** → se abre el diálogo a pantalla completa.
2. Pulsas **AGREGAR** → escribes "Trabajo" y "trabajo@gmail.com" → se crea.
3. En la lista, tocas la fila de "Trabajo" → su radio se marca.
4. Pulsas **CAMBIAR** (naranja) → la app vuelca las cookies de la cuenta
   actual, programa el relanzamiento y sale. Al volver a abrir, arranca
   en la sandbox de "Trabajo" (directorio `app_webview/usr_<id>/` propio):
   Google y cualquier otro sitio ven una sesión **limpia**. Inicias sesión
   con la cuenta de Trabajo.
5. Para volver: mismo diálogo → tocas "Personal" → **CAMBIAR** → la app
   reinicia en la sandbox de Personal, donde tu sesión de Google personal
   sigue **logueada** (se guardó al salir).

### Internas

- `versionCode` 23, `versionName` 4.4.
- `Users.restartApp`: `AlarmManager` + `System.exit(0)` (antes
  `killProcess` + `sleep`).
- `Users.flushCookies()`: vuelca `CookieManager` antes de cambiar.
- `Users.U`: nuevo campo `email` + `Users.update()` para editarlo.
- `Users.displayOf()`: identificador visible (email → name → id).
- `showUsersDialog` reescrito (diálogo completo estilo lfbrowser):
  avatar + identificador subrayado + radio + CANCELAR/CAMBIAR.
- `addAndSwitchUser` / `editUserDialog` / `confirmDeleteUser`: nuevos
  (sustituyen a `renameUserDialog` / `deleteUsersDialog`).
- `AndroidManifest.allowBackup=false` (evita que el backup/restore mezcle
  sandboxes de usuarios distintos).
- 129 tests JVM pasan (0 fallos).

### Instalación

```
adb install -r TurboDL-4.4.apk
```
Misma firma que 4.3 → se actualiza sin desinstalar. Android 8.0+.

---

# TurboDL 4.3 — Notas de la versión

Fecha: 2026-11-22

## Notas del navegador funcionales (CRUD completo)

Reportaste: *"haz otra versión, la 4.3, que incluya dentro de las notas del
navegador sean funcionales; hasta ahora no puedo hacer nada, quiero ser
capaz de hacer CRUD con las notas"*.

### Causa raíz

Las notas ya tenían el CRUD completo en la base de datos (`NotesDb`:
`addNote`, `updateNote`, `deleteNote`, `getNote`, `notes(query)`), pero el
usuario **no podía abrir la pantalla de Notas**. El culpable era el
`GestureDetector` que añadí en la 4.2.3 para el swipe del menú: ese detector
consumía el evento `ACTION_DOWN` en el `menuPager` y, al devolver `true`,
**bloqueaba todos los clics en las celdas del menú** (Notas, Marcadores,
Ajustes…). El usuario no podía pulsar ningún botón del menú.

### Solución 1: VelocityTracker en vez de GestureDetector (U9, redefinido)

Se sustituye el `GestureDetector` de los dos pagers (`sitesPager` y
`menuPager`) por un `VelocityTracker` manual. La diferencia clave: el touch
listener **devuelve siempre `false`** (nunca consume el evento), de modo que
el `HorizontalScrollView` y todos sus hijos (las celdas del menú, los accesos
directos) reciben el evento completo y los clics funcionan. Solo medimos la
velocidad del gesto en `ACTION_UP`; si supera `FLING_VEL_MIN` (300 px/s),
saltamos de página inmediatamente con `scrollTo()`. Si no, encajamos por
desplazamiento (umbral 10%). Se mantiene el swipe inmediato y muy sensible de
la 4.2.3 sin el bug de los clics bloqueados.

### Solución 2: UI de notas reescrita

La UI de notas anterior usaba un `ListView` anidado dentro de un
`LinearLayout` con manipulación manual de vistas hijas (frágil) y un menú
contextual oculto que el usuario no encontraba. Se reescribe:

- **Tarjetas verticales** (dentro de un `ScrollView`) en vez de `ListView`.
  Cada tarjeta muestra el **título** (negrita), la **fecha** y una
  **vista previa del cuerpo**.
- **Botones inline visibles** en cada tarjeta: ✏ Editar, 📋 Copiar,
  ↗ Compartir, 🗑 Borrar. Nada de menú contextual oculto.
- **Caja de búsqueda** en la cabecera que filtra por título o cuerpo en
  tiempo real (vía `NotesDb.notes(query)`).
- **Botón ＋ Nueva** en la cabecera, siempre visible.
- Tras crear/editar/borrar, la lista se **refresca íntegramente**.
- Borrar pide **confirmación**.
- El editor (`editNote`) tiene padding cómodo y campo de cuerpo de 8 líneas.

### CRUD completo y funcional

- **Create**: ＋ Nueva → editor (título + cuerpo) → Guardar. La nota
  aparece al instante en la lista.
- **Read**: la lista muestra todas las notas (ordenadas por fecha
  descendente) con vista previa. La búsqueda filtra en tiempo real.
- **Update**: ✏ Editar sobre una tarjeta → editor precargado → Guardar.
- **Delete**: 🗑 Borrar → confirmación → eliminada. La lista se refresca.
- **Copiar/Compartir**: botones inline en cada tarjeta.

### Test de regresión

Nuevo test JVM `NotesDbTest` (7 casos) con Robolectric que valida el CRUD
contra SQLite real sin dispositivo: create+get, listado ordenado, update
(refresca timestamp, no toca url), delete, búsqueda por título/cuerpo, y
preview. Añadido `NotesDb.clearAllNotes()` para aislar los tests. Esto fija
el contract del CRUD para que futuros cambios no rompan las notas
"sin que nadie se dé cuenta".

### Internas

- `versionCode` 22, `versionName` 4.3.
- `GestureDetector` sustituido por `VelocityTracker` en `sitesPager` y
  `menuPager`. Nuevos campos `sitesTracker`, `menuTracker`.
- `showNotes` y `editNote` reescritos (tarjetas + botones inline).
- `NotesDb.clearAllNotes()` añadido.
- Robolectric 4.13 + androidx.test:core 1.6.1 + androidx.test.ext:junit
  1.2.1 como `testImplementation`. `testOptions.unitTests
  .includeAndroidResources = true`.
- 129 tests JVM pasan (0 fallos): 122 anteriores + 7 nuevos de NotesDb.

### Instalación

```
adb install -r TurboDL-4.3.apk
```
Misma firma que 4.2.4/4.2.5 → se actualiza sin desinstalar. Android 8.0+.

---

# TurboDL 4.2.5 — Notas de la versión

Fecha: 2026-11-21

## Mejora del flujo de navegación: btnHome y btnMainHome

Reportaste: *"el botón de Home del navegador debería limpiar toda la búsqueda
de la pestaña donde esté navegando y cuando le dé de nuevo y no haya nada
en búsqueda debería ir a la vista principal de la aplicación. El otro botón
del menú debería ir directamente a la vista principal. Ninguna de las 2
cosas está pasando; además cuando le doy a alguno de esos dos botones luego,
estando todo limpio, me vuelve a abrir una URL que ya había limpiado. El
comportamiento de todo esto es extraño, mejora el flujo de navegación"*.

### 1. btnHome — dos toques, como pediste

- **1er toque**: si la pestaña actual tiene una página/URL cargada, la
  **limpia por completo**: carga `about:blank`, vacía `t.url`, vacía la
  barra de dirección y quita el botón X. El usuario sigue en el navegador
  con todo limpio, listo para una nueva búsqueda. NO muestra la vista
  principal. Un toast avisa: *"Pestaña limpiada. Toca Home de nuevo para
  ir a la vista principal"*.
- **2º toque** (cuando ya no hay nada que limpiar): va a la **vista
  principal** de la aplicación (homeOverlay con los paneles de accesos).

Antes el 1er toque limpiaba Y mostraba la vista principal en el mismo
toque, lo que no era lo que querías.

### 2. btnMainHome — siempre directo a la vista principal

- Ahora **siempre** va a la vista principal en un solo toque, sin
  importar el estado. Ya no es un toggle (mostrar/ocultar). No toca la
  pestaña actual: la URL cargada se conserva tal cual y al volver de la
  vista principal la sigues viendo.

### 3. La URL limpiada ya no "revive"

Este era el bug más raro: tras limpiar con Home, la URL que acababas de
descartar volvía a cargarse sola. La causa eran varios puntos del ciclo
de vida del WebView/pestaña que reescribían `t.url` con la URL vieja o la
recargaban:

1. **`onPageStarted`** volvía a poner `t.url = url` cuando el WebView
   disparaba cualquier carga (redirección, refresh interno, recarga
   tras recortar memoria).
2. **`onPageFinished`** hacía lo mismo al terminar.
3. **`ensureWv`** (al reconstruir un WebView recortado por memoria) lo
   recargaba desde `t.url`.
4. **`syncActive`** (al pausar/cambiar de pestaña) copiaba `t.wv?.url`
   (que podía seguir siendo la URL vieja o `about:blank`).

**Solución**: nuevo flag `Tab.cleared`. Cuando btnHome limpia la
pestaña, marca `cleared = true`. Ese flag se respeta en `onPageStarted`,
`onPageFinished`, `ensureWv` y `syncActive` (no reescriben/recargan
mientras esté activo), y se resetea en `load()` cuando el usuario carga
una URL nueva explícitamente. Así la URL eliminada no vuelve a aparecer
hasta que el usuario cargue algo a propósito.

### Internas

- `versionCode` 21, `versionName` 4.2.5.
- Nuevo campo `Tab.cleared: Boolean`.
- `onHomePressed` reescrito (flujo de dos toques: limpiar → vista
  principal). `btnMainHome` ya no es toggle.
- 122 tests JVM pasan (0 fallos).

### Instalación

```
adb install -r TurboDL-4.2.5.apk
```
Misma firma que 4.2.4 → se actualiza sin desinstalar. Android 8.0+.

---

# TurboDL 4.2.4 — Notas de la versión

Fecha: 2026-11-21

## Corrección: las actualizaciones fallaban con INSTALL_FAILED_UPDATE_INCOMPATIBLE

Reportaste: *"algo está pasando, antes podía actualizar la aplicación
directamente pero ahora me da error y tengo que desinstalar la antigua
para instalar esta"*.

### Causa raíz

Android no permite instalar un APK firmado con una clave distinta sobre
uno ya instalado (por seguridad: evita que alguien reemplace la app con
una versión falsificada). El error que se ve en el instalador es
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`.

Hasta la 4.2.3, el APK de **debug** se firmaba con la `debug.keystore` por
defecto de Android (`~/.android/debug.keystore`). Esa keystore se
**regenera** cada vez que el sandbox de compilación se recicla (o en cada
máquina distinta), con lo que dos APKs de debug sucesivos podían tener
firmas distintas y Android los rechazaba como actualización. El APK de
**release** sí usaba la keystore estable del repositorio (`turbo.keystore`),
pero el que se publicaba era el de debug.

### Solución

A partir de la 4.2.4, el buildType **debug** también se firma con
`turbo.keystore` (la misma keystore del release, que está en el repo y
es estable). Así **todos** los APKs —debug o release— comparten la misma
firma y se instalan uno sobre otro sin desinstalar la versión anterior.

Verificado con `apksigner`:
```
Signer #1 certificate DN: CN=TurboDL, OU=Dev, O=TurboDL, C=ES
Signer #1 certificate SHA-256 digest:
  10bb4b2b6fead0edbb7368905e0986c46ff3d1a728b0f082f901607d1631238b
```
Coincide con la huella de `turbo.keystore`.

### Qué significa para ti

- **A partir de la 4.2.4** podrás actualizar la app directamente (instalar
  el APK nuevo sobre el anterior) sin desinstalar nada. Conservarás los
  accesos directos, pestañas guardadas, ajustes y demás datos.
- **Una única excepción**: si tienes instalada una versión anterior a la
  4.2.4 (firmada con la debug.keystore antigua), esa última sí tendrás que
  **desinstalarla una sola vez** para pasar a la 4.2.4. A partir de ahí,
  todas las futuras se actualizarán sin desinstalar.

### Internas

- `versionCode` 20, `versionName` 4.2.4.
- `build.gradle`: `buildTypes.debug` ahora lleva
  `signingConfig signingConfigs.release`.
- 122 tests JVM pasan (0 fallos).

### Instalación

```
adb install -r TurboDL-4.2.4.apk
```
(`-r` = reemplazar manteniendo los datos). Android 8.0+ (API 26).

---

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
