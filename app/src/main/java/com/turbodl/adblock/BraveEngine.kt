package com.turbodl.adblock

import android.content.Context
import android.webkit.WebView
import io.github.edsuns.adblockclient.ResourceType
import io.github.edsuns.adfilter.AdFilter
import io.github.edsuns.adfilter.DownloadState

/**
 * Wrapper (4.11) del motor de bloqueo nativo Brave — la librería
 * AdblockAndroid (fork reaver961025, v2.0.0, LGPL-2.1), cuyo núcleo
 * C++ se basa en `brave/ad-block` (el motor del navegador Brave).
 *
 * Los AARs (ad-filter + adblock-client) van vendidos en `app/libs`;
 * el motor descarga sus listas por su cuenta vía WorkManager y las
 * instala en el cliente nativo (InstallationWorker). A diferencia del
 * extinto AdblockPlusEngine (libadblockplus V8, nunca empaquetado),
 * esta librería SÍ está compilada y vendida: shouldBlock funciona.
 *
 * Sincronización de suscripciones: el catálogo de TurboDL
 * (`AdblockCatalog`, Ajustes → Listas de filtros) es la única fuente
 * de verdad. `ensure()` añade como suscripciones las listas
 * seleccionadas en formato EasyList/AdGuard (el motor Brave NO
 * entiende listas formato hosts — esas se dejan para FilterEngine) y
 * elimina las suscripciones que el usuario desmarcó. addFilter es
 * idempotente por URL (id = sha1) y download() deduplica trabajos.
 *
 * Hilos: shouldBlock/ruleFor corren en el hilo de red del WebView
 * (el cliente nativo es thread-safe; el demo de la librería hace lo
 * mismo). ensure() y reloadAll() corren en el hilo "adblock-init" de
 * la fachada. Nunca se usa el variant shouldIntercept(view, request)
 * que hace runBlocking hacia Dispatchers.Main.
 */
object BraveEngine {

    @Volatile private var initialized = false

    /** Motivo si el motor nativo no se pudo inicializar. */
    @Volatile private var unavailableReason: String = ""

    /** 4.11: fusible anti-sobre-bloqueo por sesión — lo activa la fachada
     *  (AdBlockEngine.deshabilitarSesion) cuando el navegador detecta una
     *  proporción anómala de bloqueos; ready() pasa a false hasta la
     *  siguiente llamada a ensure() (activar el bloqueador o cambiar motor). */
    @Volatile private var sesionDesactivada = false

    /** URLs de las listas por defecto si el usuario no tiene ninguna EasyList. */
    private val DEFAULT_SUBS = listOf(
        "https://easylist.to/easylist/easylist.txt",
        "https://easylist.to/easylist/easyprivacy.txt")

    private fun filter(): AdFilter = AdFilter.get()

    /**
     * Crea el singleton `AdFilter`, activa el interruptor maestro y
     * sincroniza las suscripciones con el catálogo de TurboDL. Llamar
     * desde hilo de fondo (la fachada ya lo hace).
     */
    @JvmStatic
    fun ensure(ctx: Context) {
        // reactivar tras fusible/activación (ver sesionDesactivada)
        sesionDesactivada = false
        if (initialized) return
        try {
            val f = AdFilter.create(ctx.applicationContext)
            // interruptor maestro de la librería: defaultea true, pero si
            // una versión anterior lo apagó, lo reactivamos al abrir.
            if (f.viewModel.isEnabled.value != true) f.viewModel.isEnabled.value = true
            syncSubscriptions(ctx)
            initialized = true
        } catch (t: Throwable) {
            unavailableReason = t.message ?: t.javaClass.simpleName
        }
    }

    /** Fusible: pausa el motor nativo hasta la próxima ensure(). */
    @JvmStatic
    fun deshabilitarSesion() { sesionDesactivada = true }

    /**
     * Catálogo → suscripciones. Añade las EasyList seleccionadas (id
     * sha1 de la URL: re-add devuelve la existente), habilita y
     * descarga las que falten; elimina suscripciones que el usuario ya
     * no tiene marcadas. Si no hay ninguna EasyList marcada, subscribe
     * el mínimo (EasyList + EasyPrivacy) para no dejar el motor vacío.
     */
    private fun syncSubscriptions(ctx: Context) {
        val vm = filter().viewModel
        val sel = com.turbodl.adblock.AdblockCatalog.selected(ctx)
        val wanted = sel.filter { it.format == com.turbodl.adblock.AdblockCatalog.Format.EASYLIST }
        val subs = if (wanted.isEmpty()) {
            DEFAULT_SUBS.mapNotNull { u -> sel.find { it.url == u } }
        } else wanted
        val wantedIds = HashSet<String>()
        for (e in subs) {
            val f = vm.addFilter(e.name, e.url)
            wantedIds.add(f.id)
            if (!f.isEnabled) vm.setFilterEnabled(f.id, true, post = false)
            if (!f.hasDownloaded()) vm.download(f.id)
        }
        // quitar suscripciones desmarcadas (borra datos + prefs)
        val current = vm.filters.value?.values?.toList().orEmpty()
        for (f in current) if (f.id !in wantedIds) vm.removeFilter(f.id)
    }

    /** Recarga todas las suscripciones (Ajustes → Actualizar listas). */
    @JvmStatic
    fun reloadAll() {
        if (!initialized) return
        try {
            filter().viewModel.filters.value?.values?.forEach {
                if (it.isEnabled) filter().viewModel.download(it.id)
            }
        } catch (t: Throwable) { }
    }

    /** true si el motor nativo está listo para responder (hay al menos
     *  una suscripción habilitada e instalada en el cliente C++, y el
     *  fusible de sesión no está activo). */
    @JvmStatic
    fun ready(): Boolean {
        if (!initialized || sesionDesactivada) return false
        return try {
            filter().viewModel.filters.value?.values?.any {
                it.isEnabled && it.downloadState == DownloadState.SUCCESS
            } == true
        } catch (t: Throwable) { false }
    }

    /** true si aún está descargando/instalando las listas. */
    @JvmStatic
    fun loading(): Boolean {
        if (!initialized || ready()) return false
        return try {
            filter().viewModel.filters.value?.values?.any {
                it.isEnabled && (it.downloadState.isRunning || !it.hasDownloaded())
            } == true
        } catch (t: Throwable) { false }
    }

    /** true si el motor nativo no se pudo inicializar. */
    @JvmStatic
    fun unavailable(): Boolean = !initialized || unavailableReason.isNotEmpty()

    /** Línea de estado para el diagnóstico de la fachada. */
    @JvmStatic
    fun statusLine(ctx: Context): String {
        if (sesionDesactivada) return "Brave (nativo) pausado por el fusible de sesión"
        if (!initialized) {
            return "Brave (nativo) no disponible: " +
                (unavailableReason.ifEmpty { "error de inicialización" })
        }
        val n = try {
            filter().viewModel.filters.value?.values?.count {
                it.isEnabled && it.downloadState == DownloadState.SUCCESS
            } ?: 0
        } catch (t: Throwable) { 0 }
        return when {
            ready() -> "Brave (nativo) LISTO ($n listas instaladas, motor C++ brave/ad-block)"
            loading() -> "Brave (nativo) descargando listas…"
            else -> "Brave (nativo) sin listas instaladas — descarga desde Ajustes"
        }
    }

    /**
     * true si la petición debe bloquearse. `type` es el tipo de
     * recurso en formato TurboDL/EasyList (script, stylesheet, image,
     * media, font, xmlhttprequest, subdocument, popup, document,
     * other). Los tipos que el cliente nativo no modela (popup,
     * document, other) pasan como UNKNOWN: la regla coincide igual si
     * no declara tipos específicos.
     */
    @JvmStatic
    fun shouldBlock(pageUrl: String, reqUrl: String, type: String): Boolean {
        if (!ready()) return false
        return try {
            filter().shouldIntercept(reqUrl, pageUrl, resourceType(type)).shouldBlock
        } catch (t: Throwable) {
            false // fallar es dejar pasar, nunca bloquear de más
        }
    }

    /** Regla que bloquearía reqUrl (para el escudo del navegador). */
    @JvmStatic
    fun ruleFor(pageUrl: String, reqUrl: String, type: String): String {
        if (!ready()) return ""
        return try {
            filter().shouldIntercept(reqUrl, pageUrl, resourceType(type)).rule ?: ""
        } catch (t: Throwable) { "" }
    }

    /** Mapa tipo TurboDL → ResourceType del cliente nativo. */
    private fun resourceType(type: String): ResourceType = when (type) {
        "script" -> ResourceType.SCRIPT
        "stylesheet" -> ResourceType.CSS
        "image" -> ResourceType.IMAGE
        "media" -> ResourceType.MEDIA
        "font" -> ResourceType.FONT
        "xmlhttprequest" -> ResourceType.XMLHTTPREQUEST
        "subdocument" -> ResourceType.SUBDOCUMENT
        else -> ResourceType.UNKNOWN
    }

    /**
     * Registra los puentes JS de ocultamiento de elementos y
     * scriptlets en el WebView. Llamar al CREAR el WebView (una vez).
     */
    @JvmStatic
    fun setupWebView(wv: WebView) {
        try { filter().setupWebView(wv) } catch (t: Throwable) { }
    }

    /**
     * Inyecta ocultamiento de elementos + scriptlets. Llamar en
     * onPageStarted (el propio AdFilterImpl chequea el interruptor
     * maestro; en no-listo no hace nada).
     */
    @JvmStatic
    fun performScript(wv: WebView?, url: String?) {
        try { filter().performScript(wv, url) } catch (t: Throwable) { }
    }
}
