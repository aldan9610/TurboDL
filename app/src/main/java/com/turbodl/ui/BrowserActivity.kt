package com.turbodl.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.turbodl.App
import com.turbodl.R
import com.turbodl.db.Db
import com.turbodl.engine.AdBlockEngine
import com.turbodl.engine.MediaProbe
import com.turbodl.model.Kind
import com.turbodl.util.Kit
import com.turbodl.util.Profiles
import com.turbodl.util.Ui
import org.json.JSONArray
import org.json.JSONObject
import java.util.LinkedHashMap
import java.util.Locale

class BrowserActivity : Activity() {

    private lateinit var editUrl: AutoCompleteTextView
    private lateinit var btnVideos: Button
    private lateinit var btnDesk: Button
    private lateinit var btnTabCount: Button
    private lateinit var btnNewTab: Button
    private lateinit var tabsRow: LinearLayout
    private lateinit var prog: ProgressBar
    private lateinit var homeOverlay: LinearLayout
    private lateinit var grid: GridView
    private lateinit var webHost: FrameLayout
    private lateinit var stripScroll: HorizontalScrollView
    private lateinit var strip: LinearLayout
    private val eng get() = App.app.engine
    private var desktopMode = false

    // ---------------- pestañas (un WebView vivo por pestaña) ----------------

    /** Pestaña: WebView propio (se crea bajo demanda), url, titulo y miniatura. */
    inner class Tab(var url: String, var title: String) {
        val uid: Long = tabSeq++ // identidad estable para asociar sus capturas
        var wv: WebView? = null
        var showHome: Boolean = url.isEmpty()
        var priv: Boolean = false // pestaña privada: sin historial ni persistencia
        var thumb: Bitmap? = null
        var blocked: Int = 0
        var popups: Int = 0        // ventanas emergentes bloqueadas
        var redirects: Int = 0     // redirecciones de anuncio bloqueadas
        var lastUse: Long = 0      // última vez que se adjuntó (para recortar memoria)
        val blockedItems = ArrayList<String>() // detalles del escudo (cap 120)
    }
    private val tabs = ArrayList<Tab>()
    private var cur = 0
    private var readerMode = false

    /** WebView de la pestaña activa (todas las referencias antiguas siguen validas). */
    private val web: WebView
        get() {
            val t = tabs[if (cur in tabs.indices) cur else 0]
            return t.wv ?: ensureWv(t)
        }
    private fun activeWv(): WebView? = if (cur in tabs.indices) tabs[cur].wv else null

    companion object {
        /** Identificador único por pestaña: asocia las capturas de medios
         *  a su pestaña aunque el WebView se recree. */
        private var tabSeq = 0L

        private val MEDIA_RE = Regex(
            "\\.(m3u8|mpd|mp4|webm|mov|m4s|mp3|aac|m4a|ogg|opus|flac|wav|avi|flv|mkv)(\\?|$)",
            RegexOption.IGNORE_CASE
        )

        /**
         * Grandes sitios cuyos medios NO llevan extension: los segmentos de
         * YouTube (googlevideo/videoplayback) y las URLs de CDN de Facebook,
         * TikTok, X/Twitter, Instagram, Reddit y Vimeo se detectan por host
         * aunque la URL acabe en query. Tambien los manifiestos DASH de YouTube.
         */
        private val DETECT_RE = Regex(
            "videoplayback|googlevideo\\.com|/api/manifest/dash|fbcdn\\.net|" +
            "video\\.twimg\\.com|tiktokcdn|cdninstagram\\.com|v\\.redd\\.it|" +
            "vimeo.*(progressive|\\.mp4)|manifest.*\\.mpd",
            RegexOption.IGNORE_CASE
        )

        /**
         * Bloqueador de anuncios/rastreadores por host (lista compacta de
         * tokens; SIN CDNs de video para no romper reproducciones).
         */
        // redes de popunder/redirección conocidas (fallback si el motor no
        // está listo; con el motor listo manda EasyList: ||red^ y $popup)
        private val REDIRECT_HOSTS = arrayOf(
            "popads", "popcash", "exoclick", "exosrv", "exdynsrv", "exdyn",
            "propellerads", "clickadu", "adsterra", "adskeeper", "hilltopads",
            "juicyads", "magsrv", "adcash", "aducdn", "onclickalgo",
            "onclickmega", "onclickperformance", "onclickpredictiv",
            "betteradsystem", "brasstraffic", "realsrv", "trafficstars")

        private val AD_HOSTS = arrayOf(
            "doubleclick.net", "googlesyndication.com", "google-analytics.com",
            "googletagmanager.com", "googletagservices.com", "adservice.google",
            "pagead2", "admob", "graph.facebook.com/act", "an.facebook.com",
            "adnxs.com", "adsystem.com", "amazon-adsystem.com", "adcolony.com",
            "applovin.com", "chartboost.com", "vungle.com", "unityads.unity3d.com",
            "taboola.com", "outbrain.com", "criteo.com", "criteo.net", "pubmatic.com",
            "rubiconproject.com", "openx.net", "smartadserver.com", "casalemedia.com",
            "scorecardresearch.com", "quantserve.com", "quantcast.com", "moatads.com",
            "adform.net", "adroll.com", "bidswitch.net", "sharethrough.com",
            "teads.tv", "yieldmo.com", "indexww.com", "spotxchange.com",
            "semanticscholar.org/ads", "zedo.com", "adblade.com", "revcontent.com",
            "mgid.com", "propellerads.com", "popads.net", "popcash.net",
            "exoclick.com", "exosrv.com", "trafficjunky", "adsrvr.org",
            "crashlytics.com/ads", "app-measurement.com", "firebaseio.com/~",
            "analytics.tiktok.com", "ads.linkedin.com", "ads-twitter.com",
            "ads.yahoo.com", "adtechus.com", "advertising.com", "advertising.r7.com"
        )

        private val READERS_JS = """
        (function(){
          var kill = 'img,video,audio,iframe,embed,object,svg,canvas,ins,aside,figure';
          try { document.querySelectorAll(kill).forEach(function(e){ e.remove(); }); } catch(e){}
          try {
            var s = document.createElement('style');
            s.textContent = 'img,video,iframe{display:none!important} *{max-width:100%!important}'+
              'body{font-size:1.15em!important;line-height:1.6!important;max-width:46em!important;margin:auto!important}';
            document.head.appendChild(s);
          } catch(e){}
        })();
        """.trimIndent()

        private val DEEP_SCAN_JS = """
        (function(){
          var M = /\.(m3u8|mpd|mp4|webm|mov|m4s|mp3|aac|m4a|ogg|opus|flac|wav|avi|flv|mkv)(\?|$)/i;
          var H = /videoplayback|googlevideo\.com|\/api\/manifest\/dash|fbcdn\.net|video\.twimg\.com|tiktokcdn|cdninstagram\.com|v\.redd\.it|vimeo.*progressive/i;
          var seen = {};
          function send(u, t){
            try {
              if (!u || u.indexOf('data:') === 0 || u.indexOf('blob:') === 0) return;
              u = new URL(u, location.href).href;
              if (seen[u] || !(M.test(u) || H.test(u))) return; seen[u] = 1;
              if (window.__tdlBridge) window.__tdlBridge.sniff(u, t, document.title || '');
            } catch(e){}
          }
          try {
            document.querySelectorAll('video,audio,source').forEach(function(e){
              var u = e.currentSrc || e.src || e.getAttribute('data-src');
              if (u) send(u, 'tag');
            });
            document.querySelectorAll('a[href]').forEach(function(e){
              send(e.href, 'link');
            });
            document.querySelectorAll('[data-src],[data-video],[data-file]').forEach(function(e){
              send(e.getAttribute('data-src') || e.getAttribute('data-video') || e.getAttribute('data-file'), 'attr');
            });
          } catch(e){}
          return 1;
        })();
        """.trimIndent()

        private val DEFAULT_SITES = arrayOf(
            "YouTube|https://m.youtube.com",
            "Facebook|https://m.facebook.com",
            "Instagram|https://www.instagram.com",
            "X (Twitter)|https://x.com",
            "TikTok|https://www.tiktok.com",
            "Reddit|https://www.reddit.com",
            "Wikipedia|https://es.wikipedia.org",
            "WhatsApp Web|https://web.whatsapp.com",
            "Google|https://www.google.com"
        )

        /** Gancho JS: intercepta XHR/fetch/<video>/<audio>/atributos src */
        val HOOK = """
        (function(){
          if (window.__tdl) return; window.__tdl = 1;
          var seen = {};
          function send(u, t){
            try {
              if (!u || u.indexOf('data:') === 0 || u.indexOf('blob:') === 0) return;
              u = new URL(u, location.href).href;
              if (seen[u]) return; seen[u] = 1;
              if (window.__tdlBridge) window.__tdlBridge.sniff(u, t || '', document.title || '');
            } catch(e){}
          }
          var M = /\.(m3u8|mpd|mp4|webm|mov|m4s|mp3|aac|m4a|ogg|opus|flac|wav|avi|flv|mkv)(\?|$)/i;
          var H = /videoplayback|googlevideo\.com|\/api\/manifest\/dash|fbcdn\.net|video\.twimg\.com|tiktokcdn|cdninstagram\.com|v\.redd\.it|vimeo.*progressive/i;
          var X = XMLHttpRequest.prototype.open;
          XMLHttpRequest.prototype.open = function(m, u){
            try { if (u && (M.test(u) || H.test(u) || /mpegurl|dash|f4m/i.test(u))) send(u, 'xhr'); } catch(e){}
            return X.apply(this, arguments);
          };
          var F = window.fetch;
          if (F) window.fetch = function(u, o){
            try {
              var s = (typeof u === 'string') ? u : (u && u.url) || '';
              if (s && (M.test(s) || H.test(s) || /mpegurl|\.mpd(\?|$)/i.test(s))) send(s, 'fetch');
            } catch(e){}
            return F.apply(this, arguments);
          };
          function scan(el){
            try {
              if (!el) return;
              var s = el.src || el.currentSrc;
              if (s) send(s, 'media.' + el.tagName);
              if (el.srcObject) send(location.href, 'media.stream');
            } catch(e){}
          }
          try {
            var P = HTMLMediaElement.prototype;
            var d = Object.getOwnPropertyDescriptor(P, 'src');
            if (d && d.set) {
              Object.defineProperty(P, 'src', {
                set: function(v){ send(v, 'prop.' + this.tagName); d.set.call(this, v); },
                get: d.get
              });
            }
            var SA = Element.prototype.setAttribute;
            Element.prototype.setAttribute = function(n, v){
              try {
                if (/^src$/i.test(n) && /^(VIDEO|AUDIO|SOURCE)$/i.test(this.tagName)) send(v, 'attr');
                if (/^data-(src|file)$/i.test(n)) send(v, 'dataattr');
              } catch(e){}
              return SA.call(this, n, v);
            };
          } catch(e){}
          try {
            var MO = new MutationObserver(function(ms){
              ms.forEach(function(m){
                (m.addedNodes || []).forEach(function(n){
                  try {
                    if (n.tagName === 'VIDEO' || n.tagName === 'AUDIO' || n.tagName === 'SOURCE') scan(n);
                    if (n.querySelectorAll) n.querySelectorAll('video,audio,source').forEach(scan);
                  } catch(e){}
                });
              });
            });
            MO.observe(document.documentElement, {childList:true, subtree:true});
          } catch(e){}
          setTimeout(function(){
            try { document.querySelectorAll('video,audio,source').forEach(scan); } catch(e){}
          }, 1200);
          setTimeout(function(){
            try { document.querySelectorAll('video,audio,source').forEach(scan); } catch(e){}
          }, 4000);
        })();
        """.trimIndent()
    }

    /** medios detectados: url -> item */
    private class Detected(val url: String, val type: String) {
        var info: MediaProbe.Info? = null
        var probed = false
        var pageTitle: String = "" // titulo de la página que lo olfateó
        var tabUid: Long = -1 // pestaña dueña de la captura (listas separadas)
    }
    private val detected = LinkedHashMap<String, Detected>()
    private val uiH = Handler(Looper.getMainLooper())
    private var liveAdapter: BaseAdapter? = null
    // cuadrícula de pestañas abierta (para refrescarla al cerrar una)
    private var tabsGridAdapter: BaseAdapter? = null
    private var tabsGridDlg: android.app.Dialog? = null
    // hojas inferiores y sugerencias (interfaz 4.0, estilo navegador mínimo)
    private lateinit var homeSearch: android.widget.EditText
    private lateinit var suggestionsHost: LinearLayout
    private lateinit var tabSheet: LinearLayout
    private lateinit var tabListHost: LinearLayout
    private lateinit var menuSheet: android.view.View
    private lateinit var menuGrid: android.widget.GridLayout
    private lateinit var menuDots: LinearLayout
    private var menuPage = 0
    @Volatile private var suppressSuggest = false
    // fusible anti-sobre-bloqueo del motor (cuenta por carga de página) y
    // aviso de red caída (para no confundir "sin conexión" con un fallo de la app)
    private var adFuseReq = 0
    private var adFuseBlk = 0
    private var offlineTold = false
    // reinicios del renderizador del WebView (presión de RAM en móviles humildes)
    private var rendererGoneCount = 0
    private var rendererGoneLast = ""
    // estadísticas acumuladas de bloqueo por dominio (histórico, se guarda al salir)
    private val blockStats = LinkedHashMap<String, Long>()
    @Volatile private var statsDirty = false

    inner class Bridge(private val tabUid: Long) {
        @JavascriptInterface
        fun sniff(url: String, type: String, title: String) {
            runOnUiThread { addDetected(url, type, title, tabUid) }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        Ui.theme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.browser)
        Ui.styleAll(findViewById(android.R.id.content))
        editUrl = findViewById(R.id.editUrl)
        btnVideos = findViewById(R.id.btnVideos)
        btnDesk = findViewById(R.id.btnDesk)
        btnTabCount = findViewById(R.id.btnTabCount)
        btnNewTab = findViewById(R.id.btnNewTab)
        tabsRow = findViewById(R.id.tabsRow)
        prog = findViewById(R.id.webProg)
        homeOverlay = findViewById(R.id.homeOverlay)
        grid = findViewById(R.id.gridSites)
        webHost = findViewById(R.id.webHost)
        stripScroll = findViewById(R.id.tabsStripScroll)
        strip = findViewById(R.id.tabsStrip)

        desktopMode = Profiles.isDesktop(eng.sp)

        CookieManager.getInstance().setAcceptCookie(true)

        // motor de bloqueo: por si la app se abrió directo aquí
        // (App.onCreate ya lo inicializa con el bloqueador activado)
        if (AdBlockEngine.activado(applicationContext)) {
            AdBlockEngine.ensure(applicationContext)
        }

        findViewById<Button>(R.id.btnBack).setOnClickListener { if (web.canGoBack()) web.goBack() }
        findViewById<Button>(R.id.btnFwd).setOnClickListener { if (web.canGoForward()) web.goForward() }
        findViewById<Button>(R.id.btnGo).setOnClickListener { go() }
        findViewById<Button>(R.id.btnShield).setOnClickListener { showBlockedDialog() }
        findViewById<Button>(R.id.btnReload).setOnClickListener {
            if (prog.visibility == View.VISIBLE) {
                web.stopLoading()
                toast("Carga detenida")
            } else web.reload()
        }
        findViewById<Button>(R.id.btnHome).setOnClickListener { showHome(!homeOverlay.isVisible()) }
        btnVideos.setOnClickListener { hideSheets(); showDetected() }
        btnDesk.setOnClickListener { toggleDesktop() }
        btnTabCount.setOnClickListener { showTabSheet() }
        btnNewTab.setOnClickListener { newTab(""); renderTabSheet() }
        setStripSwipeNewTab(stripScroll)
        findViewById<Button>(R.id.btnMore).setOnClickListener { showMenuSheet() }
        findViewById<Button>(R.id.btnAddSite).setOnClickListener { addSiteDialog() }
        findViewById<Button>(R.id.btnResetSites).setOnClickListener {
            saveSites(DEFAULT_SITES.toList())
            refreshSites()
            toast("Accesos restaurados")
        }

        editUrl.setOnEditorActionListener { _, _, _ -> go(); true }
        editUrl.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { }
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { }
            override fun afterTextChanged(s: android.text.Editable?) {
                showSuggestions(s?.toString() ?: "")
            }
        })
        setupHistoryBar()

        // buscador grande de la página de inicio (estilo 4.0)
        homeSearch = findViewById(R.id.homeSearch)
        homeSearch.setOnEditorActionListener { _, _, _ ->
            val q = homeSearch.text.toString().trim()
            if (q.isNotEmpty()) {
                load(if (q.contains("://") || q.contains("."))
                    (if (q.contains("://")) q else "https://$q")
                    else Profiles.engineUrl(eng.sp, q))
                hideKb()
            }
            true
        }
        // hojas inferiores: pestañas y menú
        tabSheet = findViewById(R.id.tabSheet)
        tabListHost = findViewById(R.id.tabListHost)
        menuSheet = findViewById(R.id.menuSheet)
        menuGrid = findViewById(R.id.menuGrid)
        menuDots = findViewById(R.id.menuDots)
        suggestionsHost = findViewById(R.id.suggestionsHost)
        findViewById<Button>(R.id.btnMenuCollapse).setOnClickListener { hideSheets() }
        findViewById<Button>(R.id.btnMenuExit).setOnClickListener { finishAffinity() }

        readerMode = eng.sp.getBoolean("readerMode", false)
        restoreTabs()
        loadStats()
        updateDetectBtn()
        showHome(true)

        // llegada directa con URL (p.ej. "abrir en navegador" desde el inicio)
        val openUrl = intent?.getStringExtra("openUrl")
        if (!openUrl.isNullOrEmpty()) load(openUrl)
    }

    private fun View.isVisible(): Boolean = visibility == View.VISIBLE

    // ---------------- perfil de navegacion / escritorio ----------------

    private fun applyProfile(reload: Boolean) {
        btnDesk.alpha = if (desktopMode) 1f else 0.45f
        btnDesk.contentDescription = if (desktopMode) "Modo escritorio activado" else "Modo móvil"
        // el UA se aplica a los WebView que ya existan (los nuevos lo heredan al crearse)
        tabs.forEach { t -> t.wv?.let { Ui.applyUa(it, eng.sp, desktopMode) } }
        if (reload) {
            web.reload()
            toast(if (desktopMode) "Modo escritorio (vuelve a pulsar para móvil)"
                  else "Modo móvil")
        }
    }

    private fun toggleDesktop() {
        desktopMode = !desktopMode
        applyProfile(true)
    }

    private fun showProfileMenu(v: View) {
        val names = Profiles.LIST.map { it.name }.toTypedArray() + arrayOf(Profiles.CUSTOM)
        val cur = if (eng.sp.getBoolean("profileCustom", false)) names.size - 1
        else Profiles.index(eng.sp).coerceIn(0, Profiles.LIST.size - 1)
        AlertDialog.Builder(this)
            .setTitle("Perfil de navegación (lo que ve el servidor)")
            .setSingleChoiceItems(names, cur) { d, which ->
                if (which == names.size - 1) {
                    d.dismiss()
                    customUaDialog()
                } else {
                    eng.sp.edit().putInt("profile", which).putBoolean("profileCustom", false).apply()
                    d.dismiss()
                    desktopMode = Profiles.LIST[which].desktop
                    applyProfile(true)
                    toast("Perfil: ${names[which]}")
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun customUaDialog() {
        val edit = android.widget.EditText(this)
        edit.setText(if (eng.sp.getBoolean("profileCustom", false))
            eng.sp.getString("uaCustom", "") else Profiles.uaFor(eng.sp))
        AlertDialog.Builder(this)
            .setTitle("User-Agent personalizado")
            .setView(edit)
            .setPositiveButton("Guardar") { _, _ ->
                eng.sp.edit()
                    .putString("uaCustom", edit.text.toString().trim())
                    .putBoolean("profileCustom", true).apply()
                applyProfile(true)
                toast("User-Agent personalizado aplicado")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------------- menu del navegador ----------------

    private fun showBrowserMenu(v: View) {
        val pm = PopupMenu(this, v)
        val curTab = if (cur in tabs.indices) tabs[cur] else null
        pm.menu.add(0, 1, 0, "🔎 Buscador: ${Profiles.ENGINES[engineIndex()]}")
        pm.menu.add(0, 2, 1, "👤 Perfil: ${Profiles.name(eng.sp)}")
        pm.menu.add(0, 3, 2, if (desktopMode) "📱 Cambiar a modo móvil" else "🖥 Cambiar a modo escritorio")
        pm.menu.add(0, 10, 6, "🕘 Historial de navegación")
        pm.menu.add(0, 12, 7, "⭐ Marcadores")
        pm.menu.add(0, 13, 8, "⭐ Añadir esta página a marcadores")
        pm.menu.add(0, 14, 9, if (readerMode) "📖 Modo lector: activado" else "📖 Modo lector")
        pm.menu.add(0, 15, 10, "🔍 Escanear medios de esta página")
        pm.menu.add(0, 16, 11, if (eng.sp.getBoolean("adblock", true))
            "🛡 Bloqueador de anuncios: activado (${curTab?.blocked ?: 0} aquí)" else "🛡 Bloqueador de anuncios: apagado")
        pm.menu.add(0, 8, 12, "↻ Recargar página")
        pm.menu.add(0, 4, 13, "🧹 Limpiar historial de búsqueda")
        pm.menu.add(0, 5, 14, "🍪 Limpiar cookies y caché")
        pm.menu.add(0, 7, 15, "📌 Añadir esta página a accesos")
        pm.menu.add(0, 17, 16, "🧪 Diagnóstico del navegador")
        val exento = curTab?.url?.let { AdBlockEngine.exempted(it) } == true
        pm.menu.add(0, 18, 17,
            if (exento) "🛡 Reactivar bloqueo en este sitio" else "🚫 Desactivar bloqueo en este sitio")
        pm.menu.add(0, 19, 18, "🖼 Vídeo en ventana flotante (PiP)")
        pm.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
                1 -> showEngineMenu(v)
                2 -> showProfileMenu(v)
                3 -> toggleDesktop()
                10 -> showPageHistory()
                12 -> showBookmarks()
                13 -> { addBookmark(curTab?.url ?: "", web.title ?: ""); toast("Añadido a marcadores") }
                14 -> toggleReader()
                15 -> { inject(DEEP_SCAN_JS); toast("Escaneando medios de la página…") }
                16 -> {
                    val nv = !eng.sp.getBoolean("adblock", true)
                    eng.sp.edit().putBoolean("adblock", nv).apply()
                    toast(if (nv) "Bloqueador activado" else "Bloqueador apagado")
                    tabs.forEach { it.wv?.reload() }
                }
                8 -> { showHome(false); web.reload() }
                4 -> { clearHistory(); toast("Historial de búsqueda borrado") }
                5 -> clearPrivacy()
                7 -> addCurrentAsSite()
                17 -> showDiagnostico()
                18 -> toggleSiteExempt(curTab?.url ?: "")
                19 -> enterPip()
            }
            true
        }
        pm.show()
    }

    /** Exención del bloqueador para el sitio de la pestaña activa. */
    private fun toggleSiteExempt(page: String) {
        if (!page.startsWith("http")) { toast("Abre una página web primero"); return }
        val nv = !AdBlockEngine.exempted(page)
        AdBlockEngine.setExempt(this, page, nv)
        toast(if (nv)
            "Bloqueo desactivado en ${MediaProbe.hostOf(page)} (se reactiva desde este mismo menú)"
        else "Bloqueo reactivado en ${MediaProbe.hostOf(page)}")
        tabs.forEach { it.wv?.reload() }
    }

    /** Vídeo en ventana flotante: la página (con su <video>) sigue visible
     *  en PiP mientras se navega o se consultan las descargas. */
    private fun enterPip() {
        try {
            val params = android.app.PictureInPictureParams.Builder()
                .setAspectRatio(android.util.Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
        } catch (e: Exception) {
            toast("PiP no disponible: ${e.message}")
        }
    }

    /** Panel de diagnóstico: estado real del motor, RAM del dispositivo,
     *  versión del WebView y reinicios del renderizador. Sustituye al
     *  logcat cuando no hay ordenador: lo que muestra aquí es lo que
     *  hay que reportar. */
    private fun showDiagnostico() {
        val t = if (cur in tabs.indices) tabs[cur] else null
        val msg = StringBuilder()
        val vName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (e: Exception) { "?" }
        msg.append("TurboDL ").append(vName).append(" (Android ")
            .append(android.os.Build.VERSION.RELEASE).append(")\n\n")
        try {
            val pi = packageManager.getPackageInfo("com.google.android.webview", 0)
            msg.append("Visor web (WebView): ").append(pi.versionName).append('\n')
        } catch (e: Exception) {
            msg.append("Visor web: versión de sistema\n")
        }
        msg.append('\n').append(AdBlockEngine.diagnostico(this))
        msg.append("- Bloqueados en esta página: ").append(t?.blocked ?: 0).append('\n')
        if (rendererGoneCount > 0) {
            msg.append("- Reinicios del visor: ").append(rendererGoneCount)
                .append(" (último ").append(rendererGoneLast).append(")\n")
        }
        msg.append("\nSi una página queda en blanco o sin imágenes: revisa la ")
            .append("conexión, recarga, y si reaparece el aviso de reinicio ")
            .append("reporta lo que muestra esta ventana.")
        AlertDialog.Builder(this)
            .setTitle("🧪 Diagnóstico del navegador")
            .setMessage(msg.toString())
            .setPositiveButton("OK", null)
            .show()
    }

    private fun engineIndex(): Int {
        val i = eng.sp.getInt("searchEng", 0)
        return if (i in Profiles.ENGINES.indices) i else 0
    }

    private fun showEngineMenu(v: View) {
        val opts = Profiles.ENGINES.toMutableList()
        opts.add("Personalizado (URL con %s)")
        val idx = eng.sp.getInt("searchEng", 0)
        AlertDialog.Builder(this)
            .setTitle("Motor de búsqueda")
            .setSingleChoiceItems(opts.toTypedArray(), idx) { d, which ->
                eng.sp.edit().putInt("searchEng", which).apply()
                if (which == opts.size - 1) {
                    d.dismiss()
                    customEngineDialog()
                } else {
                    d.dismiss()
                    toast("Buscador: ${opts[which]}")
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun customEngineDialog() {
        val edit = android.widget.EditText(this)
        edit.hint = "https://mi-buscador.example/search?q=%s"
        edit.setText(eng.sp.getString("searchCustom", ""))
        AlertDialog.Builder(this)
            .setTitle("Buscador personalizado")
            .setMessage("Usa %s donde deba ir la búsqueda.")
            .setView(edit)
            .setPositiveButton("Guardar") { _, _ ->
                eng.sp.edit().putString("searchCustom", edit.text.toString().trim()).apply()
                toast("Buscador personalizado guardado")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun clearPrivacy() {
        try {
            val cm = CookieManager.getInstance()
            cm.removeAllCookies(null)
            cm.flush()
            tabs.forEach { t ->
                t.wv?.let { w ->
                    w.clearCache(true)
                    w.clearFormData()
                    w.clearHistory()
                }
            }
            toast("Cookies y caché borradas")
        } catch (e: Exception) {
            toast("No se pudo limpiar: ${e.message}")
        }
    }

    private fun addCurrentAsSite() {
        val u = web.url ?: return
        val name = try { Uri.parse(u).host ?: u } catch (e: Exception) { u }
        val sites = loadSites().toMutableList()
        sites.add("$name|$u")
        saveSites(sites)
        toast("Añadido a accesos")
    }

    // ---------------- navegacion ----------------

    private fun go(): Boolean {
        var q = editUrl.text.toString().trim()
        if (q.isEmpty()) return true
        if (!q.contains("://") && !q.contains(".")) {
            addToHistory(q)
            load(Profiles.engineUrl(eng.sp, q))
        } else if (!q.contains("://")) {
            load("https://$q")
        } else {
            load(q)
        }
        hideKb()
        return true
    }

    private fun load(url: String) {
        homeOverlay.visibility = View.GONE
        hideSheets()
        suppressSuggest = true
        editUrl.setText(url)
        suppressSuggest = false
        editUrl.clearFocus()
        val t = tabs[if (cur in tabs.indices) cur else 0]
        t.url = url
        t.showHome = false
        val w = ensureWv(t)
        if (w.parent == null) attach(t)
        w.loadUrl(url)
        editUrl.setText(url)
        renderStrip()
        persistTabs()
    }

    private fun showHome(show: Boolean) {
        homeOverlay.visibility = if (show) View.VISIBLE else View.GONE
        if (cur in tabs.indices) tabs[cur].showHome = show
        if (show) {
            refreshSites()
            hideSuggestions()
            hideSheets()
            hideKb()
        }
        updateShield()
    }

    private fun hideKb() {
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(editUrl.windowToken, 0)
        } catch (e: Exception) { }
    }

    // ---------------- historial de busqueda ----------------

    private fun history(): MutableList<String> {
        val out = ArrayList<String>()
        try {
            val arr = JSONArray(eng.sp.getString("history", "[]") ?: "[]")
            for (i in 0 until arr.length()) out.add(arr.getString(i))
        } catch (e: Exception) { }
        return out
    }

    private fun addToHistory(q: String) {
        val h = history()
        h.remove(q)
        h.add(0, q)
        while (h.size > 30) h.removeAt(h.size - 1)
        val arr = JSONArray()
        h.forEach { arr.put(it) }
        eng.sp.edit().putString("history", arr.toString()).apply()
        setupHistoryBar()
    }

    private fun clearHistory() {
        eng.sp.edit().putString("history", "[]").apply()
        setupHistoryBar()
    }

    private fun setupHistoryBar() {
        val h = history()
        val ad = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, h)
        editUrl.setAdapter(ad)
        editUrl.threshold = 1
        editUrl.setOnItemClickListener { _, _, pos, _ ->
            editUrl.setText(h[pos])
            go()
        }
    }

    // ---------------- pestañas ----------------

    private fun tabCount(): Int = tabs.size

    private fun dp(v: Int): Int = Math.round(v * resources.displayMetrics.density)

    /** Crea (si falta) el WebView de la pestaña con toda su configuracion. */
    private fun ensureWv(t: Tab): WebView {
        t.wv?.let { return it }
        val w = WebView(this)
        val ws = w.settings
        ws.javaScriptEnabled = true
        ws.domStorageEnabled = true
        ws.mediaPlaybackRequiresUserGesture = false
        // window.open pasa SIEMPRE por onCreateWindow: los popunders dejan
        // de poder secuestrar la página actual (causa de las redirecciones
        // indeseadas en sitios de vídeo tipo eporner/tnaflix/spankbang)
        ws.setSupportMultipleWindows(true)
        ws.javaScriptCanOpenWindowsAutomatically = true
        ws.loadWithOverviewMode = true
        ws.useWideViewPort = true
        ws.cacheMode = WebSettings.LOAD_DEFAULT
        Ui.applyUa(w, eng.sp, desktopMode)
        w.addJavascriptInterface(Bridge(t.uid), "__tdlBridge")
        w.webViewClient = client
        w.webChromeClient = chrome
        w.setDownloadListener { url, _, _, _, _ ->
            askDownload(url, null)
        }
        // pulsacion larga sobre un enlace: abrir en primera fila o en segundo plano
        w.setOnLongClickListener { v ->
            val hr = (v as WebView).hitTestResult
            val u = hr.extra
            if (u != null && (hr.type == WebView.HitTestResult.SRC_ANCHOR_TYPE ||
                        hr.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE)) {
                showLinkMenu(u)
                true
            } else false
        }
        if (t.url.isNotEmpty()) w.loadUrl(t.url)
        t.wv = w
        return w
    }

    private fun tabOf(wv: WebView?): Tab? = tabs.firstOrNull { it.wv === wv }

    /** Muestra el WebView de la pestaña indicada y pausa el resto. */
    private fun attach(t: Tab) {
        val w = ensureWv(t)
        webHost.removeAllViews()
        webHost.addView(w, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        w.onResume()
        t.lastUse = System.currentTimeMillis()
        tabs.forEach { o -> if (o !== t) o.wv?.onPause() }
        updateShield()
        updateDetectBtn()
        homeOverlay.visibility = if (t.showHome && t.url.isEmpty()) View.VISIBLE else View.GONE
        trimTabs(4)
    }

    /**
     * Libera los WebViews de las pestañas menos usadas recientemente.
     * En móviles de 1 GB cada WebView vivo pesa decenas de MB: mantener
     * todas las pestañas renderizadas acaba en reinicios del visor. La
     * pestaña recortada conserva url, título y capturas de medios; al
     * volver a ella se recarga (mismo efecto que matar el proceso).
     */
    private fun trimTabs(keep: Int) {
        val inactive = tabs.filterIndexed { i, t -> i != cur && t.wv != null }
            .sortedByDescending { it.lastUse }
        for (t in inactive.drop(keep.coerceAtLeast(0))) {
            t.wv?.let { w ->
                try { w.removeJavascriptInterface("__tdlBridge"); w.destroy() } catch (e: Exception) { }
            }
            t.wv = null
            try { t.thumb?.recycle() } catch (e: Exception) { }
            t.thumb = null
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // aviso del sistema de presión de memoria: recortar más agresivo
        when {
            level >= TRIM_MEMORY_RUNNING_CRITICAL -> trimTabs(0)
            level >= TRIM_MEMORY_RUNNING_LOW -> trimTabs(1)
            level >= TRIM_MEMORY_RUNNING_MODERATE -> trimTabs(3)
        }
    }

    /** Copia al objeto Tab lo que el WebView activo tiene ahora mismo. */
    private fun syncActive() {
        if (cur !in tabs.indices) return
        val t = tabs[cur]
        t.url = t.wv?.url ?: t.url
        t.title = t.wv?.title ?: t.title
        t.showHome = homeOverlay.isVisible()
    }

    /** Miniatura de la pestaña para el conmutador (se captura al salir de ella). */
    private fun grabThumb(t: Tab) {
        val w = t.wv ?: return
        if (w.width <= 0 || w.height <= 0) return
        try {
            val b = Bitmap.createBitmap(160, 240, Bitmap.Config.RGB_565)
            val c = Canvas(b)
            c.drawColor(Color.parseColor("#37474F"))
            val s = 160f / w.width
            c.scale(s, s)
            w.draw(c)
            t.thumb = b
        } catch (e: Exception) { }
    }

    private fun newTab(url: String = "", switchNow: Boolean = true, privateTab: Boolean = false) {
        if (tabs.size >= 8) { toast("Máximo 8 pestañas"); return }
        syncActive()
        val nt = Tab(url, "")
        nt.priv = privateTab
        tabs.add(nt)
        if (switchNow) {
            cur = tabs.size - 1
            attach(nt)
            editUrl.setText(url)
            hideKb()
        } else {
            // segundo plano: el WebView carga ya (detecta medios) sin robarte la vista
            ensureWv(nt)
            toast("Pestaña abierta en segundo plano")
        }
        renderStrip()
        persistTabs()
        updateTabButton()
        if (privateTab) toast("Pestaña privada: no se guarda historial")
    }

    private fun closeTab(index: Int) {
        if (index < 0 || index >= tabs.size) return
        val wasActive = index == cur
        tabs[index].let { t ->
            t.wv?.let { w ->
                try { w.removeJavascriptInterface("__tdlBridge"); w.destroy() } catch (e: Exception) { }
            }
            t.thumb = null
        }
        purgeTabMedia(tabs[index].uid)
        tabs.removeAt(index)
        if (tabs.isEmpty()) {
            tabs.add(Tab("", ""))
            cur = 0
            attach(tabs[0])
            editUrl.setText("")
        } else {
            if (index < cur) cur--
            else if (wasActive) {
                cur = index.coerceAtMost(tabs.size - 1)
                attach(tabs[cur])
                editUrl.setText(tabs[cur].url)
            }
        }
        renderStrip()
        persistTabs()
        updateTabButton()
        toast("Pestaña cerrada")
    }

    private fun switchTab(index: Int) {
        if (index < 0 || index >= tabs.size || index == cur) return
        syncActive()
        grabThumb(tabs[cur])
        cur = index
        attach(tabs[index])
        editUrl.setText(tabs[index].url)
        renderStrip()
        persistTabs()
        updateTabButton()
    }

    private fun updateTabButton() {
        btnTabCount.text = "[${tabCount()}]"
        if (cur in tabs.indices) {
            val t = tabs[cur]
            btnTabCount.contentDescription =
                "Pestañas: ${t.title.ifEmpty { t.url.ifEmpty { "inicio" } }}. Pulsa para verlas todas"
        }
    }

    // ---------------- tira de pestañas (arriba, siempre visible) ----------------

    /** Mini-tarjetas con titulo y cerrar; la fila [🗂n][tarjetas][＋] vive siempre arriba. */
    private fun renderStrip() {
        strip.removeAllViews()
        tabs.forEachIndexed { i, t -> strip.addView(miniTabView(t)) }
        strip.post {
            if (cur in tabs.indices) {
                val v = strip.getChildAt(cur)
                if (v != null) stripScroll.smoothScrollTo((v.left - dp(30)).coerceAtLeast(0), 0)
            }
        }
    }

    private fun miniTabView(t: Tab): View {
        val i = tabs.indexOf(t)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(10), dp(3), dp(8), dp(3))
        val bg = GradientDrawable()
        bg.cornerRadius = dp(14).toFloat()
        bg.setColor(if (i == cur) Color.WHITE else 0x33FFFFFF)
        row.background = bg
        val tv = TextView(this)
        tv.textSize = 12f
        tv.setSingleLine(true)
        tv.maxWidth = dp(100)
        val label = (if (t.priv) "🕵 " else "") +
            t.title.ifEmpty { if (t.url.isEmpty()) "Inicio" else MediaProbe.hostOf(t.url) }
        tv.text = label
        tv.setTextColor(if (i == cur) 0xFF212121.toInt() else Color.WHITE)
        row.addView(tv)
        val x = TextView(this)
        x.text = "×"
        x.textSize = 16f
        x.setPadding(dp(8), 0, 0, 0)
        x.setTextColor(if (i == cur) 0xFF212121.toInt() else Color.WHITE)
        x.contentDescription = "Cerrar ${label.take(20)}"
        x.setOnClickListener { closeTab(tabs.indexOf(t)) }
        row.addView(x)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
        lp.marginEnd = dp(6)
        row.layoutParams = lp
        row.setOnClickListener { val j = tabs.indexOf(t); if (j >= 0) switchTab(j) }
        row.setOnLongClickListener {
            val j = tabs.indexOf(t)
            if (j < 0) return@setOnLongClickListener true
            AlertDialog.Builder(this)
                .setTitle(label.take(40))
                .setItems(arrayOf("✕ Cerrar pestaña", "🗐 Cerrar las demás")) { _, w ->
                    if (w == 0) closeTab(tabs.indexOf(t)) else closeOthers(tabs.indexOf(t))
                }
                .setNegativeButton("Cancelar", null)
                .show()
            true
        }
        setTabSwipeGestures(row, t)
        return row
    }

    /**
     * Gestos sobre una tarjeta de la tira:
     *  · arrastre horizontal -> la tarjeta se desliza y se cierra (estilo navegador)
     *  · arrastre vertical hacia abajo -> nueva pestaña
     */
    private fun setTabSwipeGestures(v: View, t: Tab) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        val downSwipe = dp(36)
        var downX = 0f
        var downY = 0f
        var closeDrag = false
        var created = false
        v.setOnTouchListener { view, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x; downY = ev.y
                    closeDrag = false; created = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (!closeDrag && !created && dy > downSwipe && Math.abs(dy) > Math.abs(dx)) {
                        // swipe hacia abajo: crear pestaña
                        created = true
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        newTab("")
                        return@setOnTouchListener true
                    }
                    if (!closeDrag && !created &&
                        Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.2f) {
                        closeDrag = true
                        view.parent.requestDisallowInterceptTouchEvent(true)
                        view.cancelLongPress()
                    }
                    if (closeDrag) {
                        view.translationX = dx
                        view.alpha = (1f - Math.abs(dx) / (view.width * 0.9f).coerceAtLeast(1f))
                            .coerceIn(0.15f, 1f)
                        true
                    } else false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (created) {
                        true
                    } else if (closeDrag) {
                        val w = view.width.coerceAtLeast(1)
                        val away = Math.abs(view.translationX) > w * 0.45f
                        if (away) {
                            val dir = (if (view.translationX > 0) w else -w).toFloat()
                            view.animate().translationX(dir).alpha(0f).setDuration(130)
                                .withEndAction { closeTab(tabs.indexOf(t)) }
                                .start()
                        } else {
                            view.animate().translationX(0f).alpha(1f).setDuration(130).start()
                        }
                        true
                    } else false
                }
                else -> false
            }
        }
    }

    /** Swipe hacia abajo sobre el fondo de la tira (fuera de una tarjeta): nueva pestaña. */
    private fun setStripSwipeNewTab(v: View) {
        val downSwipe = dp(36)
        var downY = 0f
        var downX = 0f
        var armed = true
        v.setOnTouchListener { view, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = ev.y; downX = ev.x; armed = true; false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = ev.y - downY
                    val dx = ev.x - downX
                    if (armed && dy > downSwipe && Math.abs(dy) > Math.abs(dx)) {
                        armed = false
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        newTab("")
                        true
                    } else false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!armed) { armed = true; true } else false
                }
                else -> false
            }
        }
    }

    private fun closeOthers(keep: Int) {
        for (i in tabs.indices.reversed()) if (i != keep) {
            purgeTabMedia(tabs[i].uid)
            tabs[i].wv?.let { w ->
                try { w.removeJavascriptInterface("__tdlBridge"); w.destroy() } catch (e: Exception) { }
            }
            tabs[i].thumb = null
            tabs.removeAt(i)
        }
        cur = 0
        attach(tabs[0])
        editUrl.setText(tabs[0].url)
        renderStrip()
        persistTabs()
        updateTabButton()
        toast("Solo queda 1 pestaña")
    }

    // ---------------- conmutador de pestañas (cuadricula con miniaturas) ----------------

    /** Conmutador estilo navegador moderno: tarjetas con miniatura, cerrar y nueva. */
    private fun showTabs() {
        syncActive()
        if (cur in tabs.indices) grabThumb(tabs[cur])
        val lv = GridView(this)
        lv.numColumns = 2
        lv.horizontalSpacing = dp(10)
        lv.verticalSpacing = dp(10)
        lv.setPadding(dp(10), dp(12), dp(10), dp(4))
        val adapter = object : BaseAdapter() {
            // ultima celda: tarjeta "+" para crear pestaña desde la cuadrícula
            override fun getCount(): Int = tabs.size + 1
            override fun getItem(p: Int): Tab = tabs[p.coerceAtMost(tabs.size - 1)]
            override fun getItemId(p: Int): Long = p.toLong()
            override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
                if (p == tabs.size) {
                    val card = FrameLayout(this@BrowserActivity)
                    val pad = dp(8)
                    card.setPadding(pad, pad, pad, pad)
                    val bg = GradientDrawable()
                    bg.cornerRadius = dp(10).toFloat()
                    bg.setColor(0x334CAF50)
                    card.background = bg
                    val box = LinearLayout(this@BrowserActivity)
                    box.orientation = LinearLayout.VERTICAL
                    box.gravity = Gravity.CENTER
                    val plus = TextView(this@BrowserActivity)
                    plus.text = "＋"
                    plus.textSize = 40f
                    val lb = TextView(this@BrowserActivity)
                    lb.text = "Nueva pestaña"
                    lb.textSize = 12f
                    box.addView(plus)
                    box.addView(lb)
                    card.addView(box, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(150)))
                    return card
                }
                val t = getItem(p)
                val card = (cv as? FrameLayout) ?: buildTabCard()
                bindTabCard(card, p, t)
                return card
            }
        }
        lv.adapter = adapter
        val lay = LinearLayout(this)
        lay.orientation = LinearLayout.VERTICAL
        lay.addView(lv, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(430)))
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        fun b(label: String, f: () -> Unit): Button {
            val bt = Button(this)
            bt.text = label
            bt.textSize = 12f
            bt.setOnClickListener { f() }
            row.addView(bt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            return bt
        }
        val dlgRef = arrayOfNulls<android.app.Dialog>(1)
        b("+ Nueva") {
            dlgRef[0]?.dismiss()
            newTab("")
        }
        b("+ Privada") {
            dlgRef[0]?.dismiss()
            newTab("", privateTab = true)
        }
        b("Cerrar todas") {
            dlgRef[0]?.dismiss()
            tabs.forEach { t ->
                purgeTabMedia(t.uid)
                t.wv?.let { w ->
                    try { w.removeJavascriptInterface("__tdlBridge"); w.destroy() } catch (e: Exception) { }
                }
                t.thumb = null
            }
            tabs.clear()
            tabs.add(Tab("", ""))
            cur = 0
            attach(tabs[0])
            editUrl.setText("")
            renderStrip()
            persistTabs()
            updateTabButton()
        }
        lay.addView(row)
        val dlg = AlertDialog.Builder(this)
            .setTitle("Pestañas (${tabs.size})")
            .setView(lay)
            .setNegativeButton("Cerrar", null)
            .create()
        dlgRef[0] = dlg
        // referencias para que closeTabFromGrid refresque esta vista al instante
        tabsGridAdapter = adapter
        tabsGridDlg = dlg
        dlg.setOnDismissListener { tabsGridAdapter = null; tabsGridDlg = null }
        lv.setOnItemClickListener { _, _, p, _ ->
            dlg.dismiss()
            if (p == tabs.size) newTab("") else switchTab(p)
        }
        lv.setOnItemLongClickListener { _, _, p, _ ->
            dlg.dismiss()
            if (p == tabs.size) newTab("", privateTab = true) else closeTab(p)
            true
        }
        dlg.show()
    }

    /** Tarjeta del conmutador: miniatura + titulo + host + boton cerrar. */
    private fun buildTabCard(): FrameLayout {
        val card = FrameLayout(this)
        val pad = dp(8)
        card.setPadding(pad, pad, pad, pad)
        val bg = GradientDrawable()
        bg.cornerRadius = dp(10).toFloat()
        bg.setColor(0x1F888888)
        card.background = bg
        // miniatura
        val img = ImageView(this)
        img.id = android.R.id.icon
        img.scaleType = ImageView.ScaleType.FIT_START
        img.adjustViewBounds = false
        card.addView(img, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(150)))
        // franja inferior: titulo + host
        val band = LinearLayout(this)
        band.orientation = LinearLayout.VERTICAL
        val bandLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM)
        band.setPadding(dp(8), dp(4), dp(30), dp(6))
        val title = TextView(this)
        title.id = android.R.id.text1
        title.textSize = 12f
        title.setSingleLine(true)
        val host = TextView(this)
        host.id = android.R.id.text2
        host.textSize = 10f
        host.setSingleLine(true)
        band.addView(title)
        band.addView(host)
        card.addView(band, bandLp)
        // cerrar
        val x = TextView(this)
        x.id = android.R.id.button1
        x.text = "✕"
        x.textSize = 14f
        x.setPadding(dp(8), dp(4), dp(8), dp(4))
        val xLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END)
        card.addView(x, xLp)
        return card
    }

    private fun bindTabCard(card: FrameLayout, p: Int, t: Tab) {
        val img = card.findViewById<ImageView>(android.R.id.icon)
        if (t.thumb != null && !t.thumb!!.isRecycled) {
            img.setImageBitmap(t.thumb)
            img.visibility = View.VISIBLE
        } else {
            img.setImageBitmap(null)
            img.visibility = View.INVISIBLE
        }
        val title = card.findViewById<TextView>(android.R.id.text1)
        val host = card.findViewById<TextView>(android.R.id.text2)
        title.text = (if (t.priv) "🕵 " else "") + t.title.ifEmpty {
            if (t.url.isEmpty()) "Inicio" else "Nueva pestaña"
        }
        host.text = t.url.ifEmpty { "página de inicio" }
        val bg = GradientDrawable()
        bg.cornerRadius = dp(10).toFloat()
        bg.setColor(if (p == cur) 0x30FF6D00 else 0x1F888888)
        if (p == cur) bg.setStroke(dp(2), 0xFFFF6D00.toInt())
        card.background = bg
        card.findViewById<TextView>(android.R.id.button1).setOnClickListener { closeTabFromGrid(p) }
    }

    /** Cerrar pestaña desde la cuadrícula: refleja el cambio AL INSTANTE
     *  (el informe del usuario: la tarjeta seguía visible tras cerrar). */
    private fun closeTabFromGrid(p: Int) {
        closeTab(p)
        try {
            tabsGridAdapter?.notifyDataSetChanged()
        } catch (e: Exception) { }
        updateTabButton()
        renderStrip()
        if (tabsGridDlg?.isShowing == true && tabs.size <= 1) {
            // quedó la pestaña vacía que crea closeTab: cerrar la cuadrícula
            tabsGridDlg?.dismiss()
        }
    }

    /** Menu de pulsacion larga sobre un enlace: primera fila o segundo plano. */
    private fun showLinkMenu(url: String) {
        val labels = arrayOf(
            "🗂 Abrir en nueva pestaña (primera fila)",
            "🌙 Abrir en segundo plano",
            "⬇ Descargar este enlace",
            "🔗 Copiar enlace")
        AlertDialog.Builder(this)
            .setTitle(MediaProbe.hostOf(url))
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> newTab(url, true)
                    1 -> newTab(url, false)
                    2 -> askDownload(url, null)
                    3 -> {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("url", url))
                        toast("Copiado")
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Guarda la lista de pestañas (solo url/titulo; el WebView vivo no cabe).
     *  Las pestañas privadas no se persisten. */
    private fun persistTabs() {
        try {
            val arr = org.json.JSONArray()
            var shift = 0
            for ((i, t) in tabs.withIndex()) {
                if (t.priv) { if (i < cur) shift--; continue }
                val o = org.json.JSONObject()
                o.put("u", t.url)
                o.put("t", t.title)
                o.put("h", t.showHome)
                arr.put(o)
            }
            val normalCur = (cur + shift).coerceIn(0, if (arr.length() == 0) 0 else arr.length() - 1)
            eng.sp.edit().putString("tabsV21", arr.toString()).putInt("tabsCur", normalCur).apply()
        } catch (e: Exception) { }
    }

    private fun restoreTabs() {
        tabs.clear()
        try {
            val arr = org.json.JSONArray(eng.sp.getString("tabsV21", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val t = Tab(o.optString("u", ""), o.optString("t", ""))
                t.showHome = o.optBoolean("h", true)
                tabs.add(t)
            }
        } catch (e: Exception) { }
        if (tabs.isEmpty()) tabs.add(Tab("", ""))
        cur = eng.sp.getInt("tabsCur", 0).coerceIn(0, tabs.size - 1)
        attach(tabs[cur])
        editUrl.setText(tabs[cur].url)
        renderStrip()
        updateTabButton()
    }

    override fun onPause() {
        syncActive()
        persistTabs()
        tabs.forEach { it.wv?.onPause() }
        flushStats()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (cur in tabs.indices) tabs[cur].wv?.onResume()
    }

    // ---------------- historial de paginas ----------------

    private fun showPageHistory() {
        val rows = eng.db.history(200)
        val lay = LinearLayout(this)
        lay.orientation = LinearLayout.VERTICAL
        val lv = ListView(this)
        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = rows.size
            override fun getItem(p: Int): Db.HistRow = rows[p]
            override fun getItemId(p: Int): Long = rows[p].id
            override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
                val r = getItem(p)
                val v = TextView(this@BrowserActivity)
                v.setPadding(40, 26, 40, 26)
                v.textSize = 13f
                v.text = "${(r.title.ifEmpty { r.url }).take(60)}\n" +
                        "${MediaProbe.hostOf(r.url)} · ${java.text.DateFormat.getDateTimeInstance(
                            java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(r.time))}"
                v.background = getDrawable(android.R.drawable.list_selector_background)
                return v
            }
        }
        lv.adapter = adapter
        lay.addView(lv, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 640))
        val btnClear = Button(this)
        btnClear.text = "🧹 Borrar historial completo"
        btnClear.textSize = 12f
        lay.addView(btnClear)
        if (rows.isEmpty()) {
            val empty = TextView(this)
            empty.text = "Sin visitas todavía"
            empty.setPadding(40, 30, 40, 30)
            lay.addView(empty, 0)
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("Historial de navegación")
            .setView(lay)
            .setNegativeButton("Cerrar", null)
            .create()
        btnClear.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("¿Borrar todo el historial?")
                .setPositiveButton("Borrar") { _, _ ->
                    eng.db.clearHistory()
                    dlg.dismiss()
                    toast("Historial borrado")
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
        lv.setOnItemClickListener { _, _, p, _ ->
            dlg.dismiss()
            load(rows[p].url)
        }
        lv.setOnItemLongClickListener { _, _, p, _ ->
            eng.db.deleteHistory(rows[p].id)
            rows.removeAt(p)
            adapter.notifyDataSetChanged()
            toast("Entrada eliminada")
            true
        }
        dlg.show()
    }

    // ---------------- accesos directos ----------------

    private fun loadSites(): List<String> {
        try {
            val arr = JSONArray(eng.sp.getString("sites", "[]") ?: "[]")
            if (arr.length() > 0) {
                val out = ArrayList<String>()
                for (i in 0 until arr.length()) out.add(arr.getString(i))
                return out
            }
        } catch (e: Exception) { }
        return DEFAULT_SITES.toList()
    }

    private fun saveSites(list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        eng.sp.edit().putString("sites", arr.toString()).apply()
    }

    private fun refreshSites() {
        val sites = loadSites()
        val labels = sites.map { it.substringBefore('|') }
        grid.adapter = object : BaseAdapter() {
            override fun getCount(): Int = labels.size
            override fun getItem(p: Int): String = labels[p]
            override fun getItemId(p: Int): Long = p.toLong()
            override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
                val v = cv ?: layoutInflater.inflate(R.layout.item_site, parent, false)
                (v.findViewById<ImageView>(R.id.siteIcon)).setImageResource(R.mipmap.ic_launcher)
                v.findViewById<TextView>(R.id.siteName).text = labels[p]
                return v
            }
        }
        Ui.styleAll(grid)
        grid.setOnItemClickListener { _, _, p, _ ->
            load(sites[p].substringAfter('|'))
        }
        grid.setOnItemLongClickListener { _, _, p, _ ->
            AlertDialog.Builder(this)
                .setTitle("Quitar «${labels[p]}»")
                .setMessage("¿Eliminar este acceso directo de la lista?")
                .setPositiveButton("Quitar") { _, _ ->
                    val l = loadSites().toMutableList()
                    l.removeAt(p)
                    saveSites(l)
                    refreshSites()
                }
                .setNegativeButton("Cancelar", null)
                .show()
            true
        }
    }

    private fun addSiteDialog() {
        val lay = LinearLayout(this)
        lay.orientation = LinearLayout.VERTICAL
        lay.setPadding(50, 30, 50, 0)
        val eName = android.widget.EditText(this); eName.hint = "Nombre (YouTube)"
        val eUrl = android.widget.EditText(this); eUrl.hint = "URL (https://…)"
        lay.addView(eName); lay.addView(eUrl)
        AlertDialog.Builder(this)
            .setTitle("Añadir acceso directo")
            .setView(lay)
            .setPositiveButton("Añadir") { _, _ ->
                var u = eUrl.text.toString().trim()
                val n = eName.text.toString().trim()
                if (u.isEmpty()) return@setPositiveButton
                if (!u.contains("://")) u = "https://$u"
                val l = loadSites().toMutableList()
                l.add("${n.ifEmpty { Uri.parse(u).host ?: u }}|$u")
                saveSites(l)
                refreshSites()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------------- deteccion de medios ----------------

    private val client = object : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            val t = tabOf(view) ?: return
            val isActive = view === activeWv()
            t.blocked = 0
            t.popups = 0
            t.redirects = 0
            synchronized(t.blockedItems) { t.blockedItems.clear() }
            if (isActive) updateShield()
            // fusible y aviso de red se reinician en cada carga
            adFuseReq = 0
            adFuseBlk = 0
            offlineTold = false
            if (url != null && url != "about:blank") {
                t.url = url
                if (isActive) {
                    prog.visibility = View.VISIBLE
                    editUrl.setText(url)
                    homeOverlay.visibility = View.GONE
                }
            }
            renderStrip()
        }

        /** Diagnóstico claro de errores de carga: si el teléfono está sin
         *  conexión, las imágenes y vídeos no se ven aunque el bloqueador
         *  esté desactivado — se avisa para no confundir la causa. */
        override fun onReceivedError(
            view: WebView?, request: WebResourceRequest?, error: WebResourceError?
        ) {
            super.onReceivedError(view, request, error)
            if (request?.isForMainFrame == true && error != null && !offlineTold) {
                offlineTold = true
                val desc = error.description?.toString()?.takeIf { it.isNotBlank() }
                    ?: "error de red"
                toast("La página no cargó ($desc). Si el teléfono está sin " +
                    "internet, no se ven imágenes ni vídeos aunque el " +
                    "bloqueador esté desactivado.")
            }
        }

        /** El proceso que renderiza el WebView murió (presión de RAM en
         *  móviles humildes, crash del visor…): SIN ESTO la página queda en
         *  blanco para siempre y parece que "el navegador no renderiza nada".
         *  Se reconstruye el WebView de la pestaña y se recarga. */
        override fun onRenderProcessGone(
            view: WebView?, detail: RenderProcessGoneDetail?
        ): Boolean {
            val t = tabOf(view) ?: return true
            rendererGoneCount++
            rendererGoneLast = java.text.SimpleDateFormat("HH:mm:ss")
                .format(java.util.Date()) +
                if (detail?.didCrash() == true) " (crash del visor)" else " (memoria)"
            try { (view?.parent as? ViewGroup)?.removeView(view) } catch (e: Exception) { }
            try { view?.destroy() } catch (e: Exception) { }
            t.wv = null
            t.thumb = null
            ensureWv(t)
            if (t.url.isNotEmpty()) {
                try { t.wv?.loadUrl(t.url) } catch (e: Exception) { }
            }
            if (t === tabs.getOrNull(cur)) {
                attach(t)
                editUrl.setText(t.wv?.url ?: t.url)
            }
            renderStrip()
            toast("Visor reiniciado #$rendererGoneCount — la página se recargó")
            return true
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            val t = tabOf(view) ?: return
            val isActive = view === activeWv()
            if (isActive) prog.visibility = View.GONE
            injectOn(view)
            if (readerMode) injectOn(view, READERS_JS)
            // filtro cosmético del motor adblock: oculta huecos de anuncios.
            // Se calcula en HILO DE FONDO (los selectores genéricos de EasyList
            // pueden ocupar mucho) y se inyecta al volver al hilo UI; nunca en
            // el hilo principal para no bloquearlo ni reventar el heap.
            if (eng.sp.getBoolean("adblock", true) && url != null && url.startsWith("http")) {
                val v = view
                val pageUrl = url
                Thread {
                    val css = try { AdBlockEngine.cssFor(pageUrl) } catch (e: Throwable) { "" }
                    if (css.isNotEmpty()) {
                        runOnUiThread {
                            try { injectOn(v, cosmeticJs(css)) } catch (e: Throwable) { }
                        }
                    }
                }.apply { isDaemon = true; name = "adblock-css" }.start()
            }
            if (url != null && url.startsWith("http")) {
                t.url = url
                t.title = view?.title ?: t.title
                t.showHome = false
                updateTabButton()
                renderStrip()
                // historial de paginas (las pestañas privadas no registran)
                if (!t.priv) {
                    try { eng.db.addHistory(url, t.title) } catch (e: Exception) { }
                }
                if (isActive) editUrl.setText(url)
            }
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val u = request?.url?.toString() ?: return false
            if (u.startsWith("magnet:")) {
                eng.add(u, "", Kind.TORRENT, eng.cats.size - 1, 1, "", true, null) {
                    if (it != null) toast("Magnet añadido a la cola")
                }
                return true
            }
            if (u.startsWith("thunder://") || u.startsWith("qqdl://") || u.startsWith("flashget://")) {
                eng.add(u, "", 0, 0, 4, "", true, null) { }
                return true
            }
            // Guardia contra redirecciones de anuncio (popunders de sitios de
            // vídeo): navegación del frame principal SIN toque de usuario y
            // hacia OTRO dominio base. Los enlaces tocados por el usuario
            // nunca entran aquí (hitTest con ancla = gesto). Consulta a la
            // lista de filtros (reglas $popup y ||red-de-anuncios^).
            try {
                if (request.isForMainFrame && view != null) {
                    val cur = view.url ?: ""
                    if (u.startsWith("http") && cur.startsWith("http")) {
                        val hit = view.hitTestResult
                        val userTapped = hit.type != WebView.HitTestResult.UNKNOWN_TYPE
                        if (!userTapped) {
                            val hT = hostOf(u)
                            val hC = hostOf(cur)
                            if (hT.isNotEmpty() && hT != hC && baseOf(hT) != baseOf(hC)) {
                                val block = if (AdBlockEngine.ready())
                                    AdBlockEngine.shouldBlock(cur, u, "popup")
                                else !AdBlockEngine.exempted(cur) &&
                                    REDIRECT_HOSTS.any { hT.contains(it) }
                                if (block) {
                                    val t = tabOf(view)
                                    if (t != null) {
                                        bumpBlocked(t, "↪ $hT${pathOf(u)} — redirección de anuncio")
                                        synchronized(t) { t.redirects++ }
                                    }
                                    toast("Redirección de anuncio bloqueada")
                                    return true
                                }
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                // ni un error detiene la navegación
            }
            return false
        }

        override fun shouldInterceptRequest(
            view: WebView?, request: WebResourceRequest?
        ): WebResourceResponse? {
            val u = request?.url?.toString() ?: return null
            if (MEDIA_RE.containsMatchIn(u) || DETECT_RE.containsMatchIn(u))
                addDetected(Kit.normalizeSniffUrl(u), "req",
                    tabUid = tabOf(view)?.uid ?: tabs.getOrNull(cur)?.uid ?: -1)
            // bloqueador de anuncios: motor 100% Kotlin (EasyList + EasyPrivacy,
            // semantica EasyList); si aun no esta listo o fallo, cae a la
            // lista compacta de hosts que existia antes. Nunca debe lanzar:
            // try(Throwable) para que ni un Error detenga la navegacion.
            // El FRAME PRINCIPAL nunca se bloquea: una regla que pifie la
            // pagina entera deja la pestaña en blanco (solo se filtran
            // subrecursos, como hace uBlock).
            try {
                if (eng.sp.getBoolean("adblock", true) && !request.isForMainFrame) {
                    val host = (request.url.host ?: "").lowercase(Locale.US)
                    var block = false
                    if (AdBlockEngine.ready()) {
                        adFuseReq++
                        block = AdBlockEngine.shouldBlock(view?.url ?: u, u, AdBlockEngine.typeOf(u))
                        if (block) adFuseBlk++
                        // fusible: si el motor bloquease una proporcion anomala
                        // (p. ej. todas las imagenes), se pausa y queda la
                        // lista basica de hosts; la web vuelve a renderizar
                        if (adFuseReq >= 12 && adFuseBlk * 100 > adFuseReq * 60) {
                            AdBlockEngine.deshabilitarSesion()
                            adFuseReq = 0
                            adFuseBlk = 0
                            runOnUiThread {
                                toast("Motor de bloqueo pausado (bloqueo anómalo); " +
                                    "se usa la lista básica")
                            }
                        }
                    } else if (host.isNotEmpty() &&
                        !AdBlockEngine.exempted(view?.url ?: u) &&
                        AD_HOSTS.any { host.contains(it) }) {
                        block = true
                    }
                    if (block) {
                        val t = tabOf(view)
                        if (t != null) {
                            val tipo = AdBlockEngine.typeOf(u)
                            val regla = if (AdBlockEngine.ready())
                                AdBlockEngine.ruleFor(view?.url ?: u, u, tipo)
                            else "lista básica de hosts"
                            bumpBlocked(t, "${tipoIcono(tipo)} $host${pathOf(u)}" +
                                (if (regla.isNotEmpty()) " — $regla" else ""))
                        }
                        return WebResourceResponse(
                            "text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                    }
                }
            } catch (e: Throwable) {
                // cualquier problema del bloqueador no tira la peticion
            }
            return null
        }
    }

    private val chrome = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            if (view === activeWv()) {
                prog.progress = newProgress
                prog.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                // el icono de recarga pasa a "detener" mientras la página carga
                findViewById<Button>(R.id.btnReload).text =
                    if (newProgress in 1..99) "✕" else "⟳"
            }
            if (newProgress >= 90) injectOn(view)
        }

        /** target=_blank / window.open: nueva pestaña en primera fila. */
        override fun onCreateWindow(
            view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?
        ): Boolean {
            if (!isUserGesture) {
                // popunder sin gesto (típico de sitios de vídeo): se bloquea.
                // Con multipleWindows=true ya NO puede redirigir la página actual.
                val t = tabOf(view)
                if (t != null) {
                    bumpBlocked(t, null)
                    synchronized(t) { t.popups++ }
                }
                val now = System.currentTimeMillis()
                if (now - lastPopToast > 2500) {
                    lastPopToast = now
                    toast("Ventana emergente de anuncio bloqueada")
                }
                return true
            }
            val href = arrayOfNulls<String>(1)
            val tmp = WebView(this@BrowserActivity)
            tmp.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView?, r: WebResourceRequest?): Boolean {
                    href[0] = r?.url?.toString() ?: href[0]
                    return true
                }
            }
            try {
                (resultMsg!!.obj as WebView.WebViewTransport).setWebView(tmp)
                resultMsg.sendToTarget()
            } catch (e: Exception) {
                return false
            }
            Handler(Looper.getMainLooper()).postDelayed({
                val u = href[0] ?: tmp.url
                try { tmp.destroy() } catch (e: Exception) { }
                if (!u.isNullOrEmpty()) newTab(u, true)
                else toast("El enlace no se pudo abrir en nueva pestaña")
            }, 500)
            return true
        }
    }

    // ---------------- escudo de bloqueos (contador + detalles) ----------------

    private var lastPopToast = 0L

    private fun hostOf(u: String): String = try {
        (java.net.URI(u).host ?: "").lowercase(Locale.US)
    } catch (e: Exception) { "" }

    /** dominio base aproximado: quita la primera etiqueta si hay subdominio
     *  (www.ejemplo.com -> ejemplo.com); el host de 2 etiquetas ya es base */
    private fun baseOf(h: String): String {
        val f = h.indexOf('.')
        if (f < 0) return ""
        val g = h.indexOf('.', f + 1)
        return if (g < 0) h else h.substring(f + 1)
    }

    private fun pathOf(u: String): String {
        val noQ = u.substringBefore('?').substringBefore('#')
        return "/" + noQ.substringAfterLast('/')
    }

    private fun tipoIcono(tipo: String): String = when (tipo) {
        "script" -> "📜"
        "image" -> "🖼"
        "stylesheet" -> "🎨"
        "media" -> "🎬"
        "document" -> "🌐"
        "font" -> "🔤"
        else -> "⚙"
    }

    /** Cuenta un bloqueo y refresca el escudo si la pestaña es la activa. */
    private fun bumpBlocked(t: Tab, line: String?) {
        synchronized(t) {
            t.blocked++
            if (line != null) {
                synchronized(t.blockedItems) {
                    t.blockedItems.add(line)
                    if (t.blockedItems.size > 120) t.blockedItems.removeAt(0)
                }
            }
        }
        statBump(t)
        if (tabs.getOrNull(cur) === t) runOnUiThread { updateShield() }
    }

    // ---------------- estadísticas acumuladas de bloqueo ----------------

    /** Suma un bloqueo al histórico del dominio de la pestaña (en memoria;
     *  se persiste en onPause/onDestroy). */
    private fun statBump(t: Tab) {
        val host = hostOf(t.url)
        if (host.isEmpty()) return
        val key = baseOf(host)
        if (key.isEmpty()) return
        synchronized(blockStats) {
            blockStats[key] = (blockStats[key] ?: 0L) + 1L
            if (blockStats.size > 200) {
                // cap: expulsa el dominio con menos bloqueos acumulados
                val minKey = blockStats.entries.minByOrNull { it.value }?.key
                if (minKey != null) blockStats.remove(minKey)
            }
        }
        statsDirty = true
    }

    private fun loadStats() {
        synchronized(blockStats) {
            blockStats.clear()
            try {
                val o = JSONObject(eng.sp.getString("blockStats", "{}") ?: "{}")
                val it = o.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    blockStats[k] = o.optLong(k, 0L)
                }
            } catch (e: Exception) { }
        }
    }

    private fun flushStats() {
        if (!statsDirty) return
        synchronized(blockStats) {
            try {
                val o = JSONObject()
                for ((k, v) in blockStats) o.put(k, v)
                eng.sp.edit().putString("blockStats", o.toString()).apply()
                statsDirty = false
            } catch (e: Exception) { }
        }
    }

    private fun updateShield() {
        val t = tabs.getOrNull(cur)
        val n = t?.blocked ?: 0
        // estilo 4.0: lupa en el inicio, escudo con contador al navegar
        findViewById<Button>(R.id.btnShield).text =
            if (homeOverlay.isVisible()) "🔍"
            else (if (n > 0) "🛡$n" else "🛡")
    }

    /** Detalles de lo bloqueado en la página activa (subrecursos, reglas,
     *  ventanas y redirecciones) + acción de exención del sitio. */
    private fun showBlockedDialog() {
        val t = tabs.getOrNull(cur) ?: return
        val page = t.url
        val sb = StringBuilder()
        val subs = (t.blocked - t.popups - t.redirects).coerceAtLeast(0)
        sb.append("Subrecursos bloqueados: $subs")
        if (t.popups > 0) sb.append("\nVentanas emergentes bloqueadas: ${t.popups}")
        if (t.redirects > 0) sb.append("\nRedirecciones de anuncio bloqueadas: ${t.redirects}")
        // histórico acumulado por dominio (se conserva entre sesiones)
        val key = baseOf(hostOf(page))
        val acum = synchronized(blockStats) { blockStats[key] ?: 0L }
        val total = synchronized(blockStats) { blockStats.values.sum() }
        if (page.startsWith("http")) {
            sb.append("\nAcumulado en este sitio: $acum · total histórico: $total")
        }
        val items = synchronized(t.blockedItems) { t.blockedItems.toList() }
        val cuerpo = if (items.isEmpty()) {
            sb.toString() + "\n\nTodavía no se ha bloqueado nada en esta página."
        } else {
            sb.toString() + "\n\n" + items.joinToString("\n")
        }
        val tv = TextView(this)
        tv.text = cuerpo
        tv.textSize = 12f
        tv.typeface = android.graphics.Typeface.MONOSPACE
        tv.setPadding(50, 40, 50, 20)
        val sv = android.widget.ScrollView(this)
        sv.addView(tv)
        val exento = AdBlockEngine.exempted(page)
        val accion = if (exento) "✅ Reactivar bloqueo aquí" else "🚫 Desactivar bloqueo aquí"
        android.app.AlertDialog.Builder(this)
            .setTitle("🛡 Bloqueos en esta página")
            .setView(sv)
            .setPositiveButton("Cerrar", null)
            .setNeutralButton(accion) { _, _ ->
                toggleSiteExempt(page)
            }
            .show()
    }

    /** JS idempotente que aplica el CSS cosmético (un solo <style> por página). */
    private fun cosmeticJs(css: String): String {
        val j = org.json.JSONObject.quote(css)
        return "(function(){try{if(window.__tdlCosm)return;window.__tdlCosm=1;" +
            "var s=document.createElement('style');s.textContent=$j;" +
            "(document.head||document.documentElement).appendChild(s);}catch(e){}})();"
    }

    private fun inject() {
        try { web.evaluateJavascript(HOOK, null) } catch (e: Exception) { }
    }

    private fun inject(js: String) {
        try { web.evaluateJavascript(js, null) } catch (e: Exception) { }
    }

    private fun injectOn(view: WebView?) {
        try { view?.evaluateJavascript(HOOK, null) } catch (e: Exception) { }
    }

    private fun injectOn(view: WebView?, js: String) {
        try { view?.evaluateJavascript(js, null) } catch (e: Exception) { }
    }

    /** Modo lector: quita imagenes/videos y agranda el texto en esta y las proximas paginas. */
    private fun toggleReader() {
        readerMode = !readerMode
        eng.sp.edit().putBoolean("readerMode", readerMode).apply()
        if (readerMode) {
            inject(READERS_JS)
            toast("Modo lector activado (se aplica a las páginas que cargues)")
        } else {
            toast("Modo lector desactivado: recarga la página")
        }
    }

    // ---------------- marcadores ----------------

    private fun bookmarks(): ArrayList<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        try {
            val arr = JSONArray(eng.sp.getString("bookmarks", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(o.optString("u", "") to o.optString("t", ""))
            }
        } catch (e: Exception) { }
        return out
    }

    private fun addBookmark(url: String, title: String) {
        if (!url.startsWith("http")) { toast("Página no válida"); return }
        val list = bookmarks()
        if (list.any { it.first == url }) { toast("Ya estaba en marcadores"); return }
        if (list.size >= 100) { toast("Máximo 100 marcadores"); return }
        val arr = JSONArray()
        arr.put(JSONObject().put("u", url).put("t", title.ifEmpty { url }))
        for ((u, t) in list) arr.put(JSONObject().put("u", u).put("t", t))
        eng.sp.edit().putString("bookmarks", arr.toString()).apply()
    }

    /** Visor de marcadores: abrir (toque) o borrar (pulsacion larga). */
    private fun showBookmarks() {
        val rows = bookmarks()
        val lay = LinearLayout(this)
        lay.orientation = LinearLayout.VERTICAL
        val lv = ListView(this)
        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = rows.size
            override fun getItem(p: Int): Pair<String, String> = rows[p]
            override fun getItemId(p: Int): Long = p.toLong()
            override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
                val (u, t) = getItem(p)
                val v = TextView(this@BrowserActivity)
                v.setPadding(40, 26, 40, 26)
                v.textSize = 13f
                v.text = "${t.take(60)}\n${MediaProbe.hostOf(u)}"
                v.background = getDrawable(android.R.drawable.list_selector_background)
                return v
            }
        }
        lv.adapter = adapter
        lay.addView(lv, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 640))
        if (rows.isEmpty()) {
            val empty = TextView(this)
            empty.text = "Sin marcadores. Añade páginas desde el menú ⋮"
            empty.setPadding(40, 30, 40, 30)
            lay.addView(empty, 0)
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("⭐ Marcadores")
            .setView(lay)
            .setNegativeButton("Cerrar", null)
            .create()
        lv.setOnItemClickListener { _, _, p, _ ->
            dlg.dismiss()
            load(rows[p].first)
        }
        lv.setOnItemLongClickListener { _, _, p, _ ->
            rows.removeAt(p)
            val arr = JSONArray()
            for ((u, t) in rows) arr.put(JSONObject().put("u", u).put("t", t))
            eng.sp.edit().putString("bookmarks", arr.toString()).apply()
            adapter.notifyDataSetChanged()
            toast("Marcador eliminado")
            true
        }
        dlg.show()
    }

    @Synchronized
    private fun addDetected(url: String, type: String, title: String = "", tabUid: Long = -1) {
        if (detected.size > 200) return
        if (detected.containsKey(url)) return
        val keep = Kit.isVideoOrAudio(url) || url.contains("m3u8") ||
            url.contains(".mpd") || url.contains("/api/manifest/dash") ||
            DETECT_RE.containsMatchIn(url)
        if (!keep) return
        detected[url] = Detected(url, type)
        detected[url]?.tabUid = tabUid
        if (title.isNotBlank()) detected[url]?.pageTitle = title
        updateDetectBtn()
        // sondeo automatico en background nada mas snifear: al abrir la
        // lista la duracion/tamano ya estan resueltos (o llegan en vivo)
        uiH.postDelayed({ kickProbes() }, 500)
    }

    /**
     * Contador del botón de medios: SOLO la pestaña activa. Cada pestaña
     * tiene su propia lista de capturas (informe del usuario: al cambiar
     * de pestaña aparecía todo unificado y no se sabía qué era de qué).
     */
    private fun updateDetectBtn() {
        val uid = tabs.getOrNull(cur)?.uid ?: -1
        val n = synchronized(detected) { detected.values.count { it.tabUid == uid } }
        btnVideos.text = if (n > 0) "⬇ Detectados: $n" else "⬇ Detectados"
    }

    /** Quita las capturas de una pestaña (al cerrarla). */
    private fun purgeTabMedia(uid: Long) {
        synchronized(detected) {
            detected.values.removeAll { it.tabUid == uid }
        }
        updateDetectBtn()
    }

    // ================= hojas inferiores y sugerencias (interfaz 4.0) =================

    private fun hideSheets() {
        tabSheet.visibility = View.GONE
        menuSheet.visibility = View.GONE
    }

    /** Conmutador de pestañas: hoja inferior con una fila por pestaña
     *  (favicon circular + título + ✕) y botón «+» para abrir otra. */
    private fun showTabSheet() {
        hideSheets()
        renderTabSheet()
        tabSheet.visibility = View.VISIBLE
    }

    private fun renderTabSheet() {
        tabListHost.removeAllViews()
        tabs.forEachIndexed { i, t -> tabListHost.addView(tabRowView(i, t)) }
    }

    private fun tabRowView(i: Int, t: Tab): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(16), dp(10), dp(8), dp(10))
        row.background = getDrawable(android.R.drawable.list_selector_background)
        // favicon circular: letra inicial sobre color derivado del host
        val fav = TextView(this)
        fav.textSize = 15f
        fav.gravity = Gravity.CENTER
        val host = MediaProbe.hostOf(t.url)
        val bgc = GradientDrawable()
        bgc.shape = GradientDrawable.OVAL
        bgc.setColor(if (t.url.isEmpty()) 0xFF8A8A8A.toInt()
        else Color.HSVToColor(floatArrayOf(
            (Math.abs(host.hashCode()) % 360).toFloat(), 0.55f, 0.62f)))
        fav.background = bgc
        fav.setTextColor(Color.WHITE)
        fav.text = host.ifEmpty { "T" }.take(1).uppercase(Locale.US)
        fav.layoutParams = LinearLayout.LayoutParams(dp(30), dp(30))
        row.addView(fav)
        val tv = TextView(this)
        tv.textSize = 14f
        tv.setSingleLine(true)
        tv.ellipsize = android.text.TextUtils.TruncateAt.END
        tv.setPadding(dp(12), 0, dp(8), 0)
        tv.text = (if (t.priv) "🕵 " else "") +
            t.title.ifEmpty { if (t.url.isEmpty()) "Inicio" else host }
        tv.setTextColor(if (i == cur) 0xFF1565C0.toInt() else 0xFF212121.toInt())
        row.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val x = TextView(this)
        x.text = "✕"
        x.textSize = 15f
        x.setPadding(dp(12), dp(4), dp(12), dp(4))
        x.setTextColor(0xFF5A5A5A.toInt())
        x.contentDescription = "Cerrar ${tv.text.take(20)}"
        x.setOnClickListener {
            closeTab(i)
            if (tabsGridDlg?.isShowing == true || tabSheet.visibility == View.VISIBLE)
                renderTabSheet()
            if (tabs.isEmpty()) hideSheets()
        }
        row.addView(x)
        row.setOnClickListener { switchTab(i); hideSheets() }
        row.setOnLongClickListener {
            AlertDialog.Builder(this)
                .setTitle(tv.text.take(40))
                .setItems(arrayOf("✕ Cerrar pestaña", "🗐 Cerrar las demás")) { _, w ->
                    if (w == 0) closeTab(i) else closeOthers(i)
                    renderTabSheet()
                }
                .setNegativeButton("Cancelar", null)
                .show()
            true
        }
        return row
    }

    private class MenuCell(
        val icon: String, val label: String,
        val active: Boolean = false, val action: () -> Unit)

    /** Menú inferior en cuadrícula de 5 columnas y 2 filas por página,
     *  con puntos de página y botones salir/colapsar (estilo 4.0). */
    private fun showMenuSheet() {
        hideSheets()
        menuPage = 0
        renderMenuPage()
        menuSheet.visibility = View.VISIBLE
    }

    private fun renderMenuPage() {
        menuGrid.removeAllViews()
        for (c in menuCells(menuPage)) menuGrid.addView(menuCell(c))
        menuDots.removeAllViews()
        for (i in 0 until 2) {
            val dot = TextView(this)
            dot.text = "•"
            dot.textSize = 18f
            dot.setPadding(dp(6), 0, dp(6), 0)
            dot.setTextColor(if (i == menuPage) 0xFFFF6D00.toInt() else 0xFFCCCCCC.toInt())
            dot.setOnClickListener { menuPage = i; renderMenuPage() }
            menuDots.addView(dot)
        }
    }

    /** Las dos páginas del menú: navegación/ajustes y herramientas de la
     *  página. Todos los botones corresponden a funciones reales. */
    private fun menuCells(page: Int): List<MenuCell> {
        val t = tabs.getOrNull(cur)
        val pageUrl = t?.url ?: ""
        return if (page == 0) listOf(
            MenuCell("🌙", "Modo nocturno") { toggleDarkTheme() },
            MenuCell("⭐", "Marcadores") { hideSheets(); showBookmarks() },
            MenuCell("🕘", "Historial") { hideSheets(); showPageHistory() },
            MenuCell("⬇", "Detectados") { hideSheets(); showDetected() },
            MenuCell("🕵", "Incógnito") { newTab("", privateTab = true); hideSheets() },
            MenuCell("↗", "Compartir") { hideSheets(); sharePage() },
            MenuCell("☆+", "Añadir marcador") { addBookmark(pageUrl, web.title ?: ""); hideSheets() },
            MenuCell("🖥", "Escritorio", active = desktopMode) { toggleDesktop(); renderMenuPage() },
            MenuCell("🧪", "Diagnóstico") { hideSheets(); showDiagnostico() },
            MenuCell("⚙", "Ajustes") { hideSheets(); startActivity(android.content.Intent(this, SettingsActivity::class.java)) }
        ) else listOf(
            MenuCell("🛡", "Bloqueos") { hideSheets(); showBlockedDialog() },
            MenuCell("🛡", "Bloqueador", active = eng.sp.getBoolean("adblock", true)) {
                val nv = !eng.sp.getBoolean("adblock", true)
                eng.sp.edit().putBoolean("adblock", nv).apply()
                toast(if (nv) "Bloqueador activado" else "Bloqueador apagado")
                tabs.forEach { it.wv?.reload() }
                renderMenuPage()
            },
            MenuCell("🚫", "Eximir sitio") { hideSheets(); toggleSiteExempt(pageUrl) },
            MenuCell("📖", "Lector", active = readerMode) { toggleReader(); renderMenuPage() },
            MenuCell("🔍", "Buscar pág.") { hideSheets(); findInPage() },
            MenuCell("📌", "Añadir acceso") { addCurrentAsSite(); renderMenuPage() },
            MenuCell("🍪", "Limpiar datos") { hideSheets(); clearPrivacy() },
            MenuCell("🖼", "Ventana flot.") { hideSheets(); enterPip() },
            MenuCell("🔎", "Escanear medios") { hideSheets(); inject(DEEP_SCAN_JS); toast("Escaneando medios de la página…") },
            MenuCell("🚪", "Salir") { finishAffinity() }
        )
    }

    private fun menuCell(c: MenuCell): View {
        val cell = LinearLayout(this)
        cell.orientation = LinearLayout.VERTICAL
        cell.gravity = Gravity.CENTER
        cell.setPadding(dp(4), dp(10), dp(4), dp(10))
        cell.background = getDrawable(android.R.drawable.list_selector_background)
        val ic = TextView(this)
        ic.text = c.icon
        ic.textSize = 22f
        ic.gravity = Gravity.CENTER
        ic.setTextColor(if (c.active) 0xFF1565C0.toInt() else 0xFF212121.toInt())
        cell.addView(ic)
        val lb = TextView(this)
        lb.text = c.label
        lb.textSize = 11f
        lb.gravity = Gravity.CENTER
        lb.maxLines = 2
        lb.setPadding(dp(2), dp(4), dp(2), 0)
        lb.setTextColor(if (c.active) 0xFF1565C0.toInt() else 0xFF3A3A3A.toInt())
        cell.addView(lb)
        val lp = android.widget.GridLayout.LayoutParams()
        lp.width = 0
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        lp.columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
        lp.rowSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
        cell.layoutParams = lp
        cell.setOnClickListener { c.action() }
        return cell
    }

    /** Tema claro/oscuro desde el menú (recrea la actividad). */
    private fun toggleDarkTheme() {
        val nv = if (Ui.themeMode(this) == 1) 0 else 1
        eng.sp.edit().putInt("themeMode", nv).apply()
        recreate()
    }

    private fun sharePage() {
        val u = tabs.getOrNull(cur)?.url ?: return
        if (!u.startsWith("http")) { toast("Abre una página primero"); return }
        try {
            val i = android.content.Intent(android.content.Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(android.content.Intent.EXTRA_TEXT, u)
            startActivity(android.content.Intent.createChooser(i, "Compartir página"))
        } catch (e: Exception) {
            toast("No se pudo compartir")
        }
    }

    /** Buscar texto dentro de la página (resaltado nativo del WebView). */
    private fun findInPage() {
        val e = android.widget.EditText(this)
        e.hint = "Texto a buscar"
        e.setSingleLine(true)
        val lay = LinearLayout(this)
        lay.orientation = LinearLayout.VERTICAL
        lay.setPadding(50, 30, 50, 0)
        lay.addView(e)
        AlertDialog.Builder(this)
            .setTitle("Buscar en la página")
            .setView(lay)
            .setPositiveButton("Buscar") { _, _ ->
                val q = e.text.toString().trim()
                if (q.isNotEmpty()) {
                    try {
                        web.findAllAsync(q)
                        toast("Buscando «$q»…")
                    } catch (ex: Exception) {
                        toast("No disponible: ${ex.message}")
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ================= sugerencias de la barra superior (interfaz 4.0) =================

    private fun hideSuggestions() {
        suggestionsHost.visibility = View.GONE
    }

    /** Panel de sugerencias bajo la barra superior: URL directa, marcadores,
     *  historial de páginas y búsquedas previas filtrados por lo tecleado. */
    private fun showSuggestions(q: String) {
        if (suppressSuggest || q.isBlank()) { hideSuggestions(); return }
        val low = q.lowercase(Locale.US)
        val rows = ArrayList<Array<String>>() // [icono, titulo, url]
        val uq = q.trim()
        if (uq.contains(".") && !uq.contains(" ")) {
            rows.add(arrayOf("↗", "Ir a $uq",
                if (uq.startsWith("http")) uq else "https://$uq"))
        }
        try {
            val arr = JSONArray(eng.sp.getString("bookmarks", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                if (rows.size > 8) break
                val o = arr.optJSONObject(i) ?: continue
                val t = o.optString("t", "")
                val u = o.optString("u", "")
                if (t.lowercase(Locale.US).contains(low) ||
                    u.lowercase(Locale.US).contains(low))
                    rows.add(arrayOf("☆", t.ifEmpty { MediaProbe.hostOf(u) }, u))
            }
        } catch (e: Exception) { }
        try {
            for (r in eng.db.history(50)) {
                if (rows.size > 10) break
                if (r.url.startsWith("http") &&
                    ((r.title.ifEmpty { r.url }).lowercase(Locale.US).contains(low) ||
                        r.url.lowercase(Locale.US).contains(low)))
                    rows.add(arrayOf("🕘", (r.title.ifEmpty { r.url }).take(60), r.url))
            }
        } catch (e: Exception) { }
        try {
            for (h in history()) {
                if (rows.size > 12) break
                if (h.lowercase(Locale.US).contains(low))
                    rows.add(arrayOf("🔎", h, Profiles.engineUrl(eng.sp, h)))
            }
        } catch (e: Exception) { }
        if (rows.isEmpty()) { hideSuggestions(); return }
        suggestionsHost.removeAllViews()
        for (r in rows) suggestionsHost.addView(suggestionRow(r[0], r[1], r[2]))
        suggestionsHost.visibility = View.VISIBLE
    }

    private fun suggestionRow(icon: String, title: String, url: String): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(14), dp(9), dp(6), dp(9))
        row.background = getDrawable(android.R.drawable.list_selector_background)
        val ic = TextView(this)
        ic.text = icon
        ic.textSize = 15f
        ic.setPadding(dp(4), 0, dp(10), 0)
        row.addView(ic)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val t1 = TextView(this)
        t1.textSize = 13.5f
        t1.setSingleLine(true)
        t1.ellipsize = android.text.TextUtils.TruncateAt.END
        t1.text = title
        t1.setTextColor(0xFF212121.toInt())
        col.addView(t1)
        val t2 = TextView(this)
        t2.textSize = 11f
        t2.setSingleLine(true)
        t2.ellipsize = android.text.TextUtils.TruncateAt.END
        t2.text = url
        t2.setTextColor(0xFF9E9E9E.toInt())
        col.addView(t2)
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val go = TextView(this)
        go.text = "↖"
        go.textSize = 15f
        go.setPadding(dp(10), dp(6), dp(10), dp(6))
        go.contentDescription = "Abrir en nueva pestaña"
        go.setOnClickListener {
            suppressSuggest = true
            editUrl.setText(url)
            suppressSuggest = false
            newTab(url, true)
            hideSuggestions()
            hideKb()
        }
        row.addView(go)
        row.setOnClickListener {
            suppressSuggest = true
            editUrl.setText(url)
            suppressSuggest = false
            load(url)
            hideKb()
        }
        return row
    }

    /** Sonda en background de todo medio pendiente; 4 hilos concurrentes. */
    private fun kickProbes() {
        val pending = synchronized(detected) {
            detected.values.filter { it.info == null && !it.probed }.toList()
        }
        if (pending.isEmpty()) return
        val headers = HashMap<String, String>()
        try {
            headers["User-Agent"] = web.settings.userAgentString
            val ck = CookieManager.getInstance().getCookie(web.url)
            if (!ck.isNullOrBlank()) headers["Cookie"] = ck
            val page = web.url
            if (!page.isNullOrBlank()) headers["Referer"] = page
        } catch (e: Exception) { }
        for (d in pending) {
            d.probed = true
            MediaProbe.probe(d.url, headers) { info ->
                d.info = info
                // la lista abierta se refresca en vivo al llegar cada resultado
                liveAdapter?.notifyDataSetChanged()
            }
        }
    }

    private fun pageHeaders(forUrl: String?): String {
        val h = JSONObject()
        try {
            val ck = CookieManager.getInstance().getCookie(forUrl ?: web.url)
            if (ck != null) h.put("Cookie", ck)
            val ref = web.url
            if (ref != null) h.put("Referer", ref)
        } catch (e: Exception) { }
        return h.toString()
    }

    // ---------------- lista de detectados ----------------

    private fun sortMode(): Int = eng.sp.getInt("sortMedia", 0)

    private fun sortedDetected(): List<Detected> {
        // SOLO la pestaña activa: cada pestaña tiene su lista propia
        val uid = tabs.getOrNull(cur)?.uid ?: -1
        val list = synchronized(detected) { detected.values.filter { it.tabUid == uid } }
        return when (sortMode()) {
            1 -> list.sortedByDescending { it.info?.size ?: -1 }
            2 -> list.sortedBy { it.info?.size ?: Long.MAX_VALUE }
            3 -> list.sortedByDescending { it.info?.durationSec ?: -1.0 }
            4 -> list.sortedBy { if (it.info == null) Double.MAX_VALUE else (it.info?.durationSec ?: Double.MAX_VALUE) }
            5 -> list.sortedBy { Kit.fmtOf(it.url) }
            6 -> list.sortedBy { it.url.substringAfterLast('/').lowercase(Locale.US) }
            else -> list
        }
    }

    private fun showDetected() {
        val uid = tabs.getOrNull(cur)?.uid ?: -1
        val mine = synchronized(detected) { detected.values.count { it.tabUid == uid } }
        if (mine == 0) {
            toast("Aún no se detectan videos en ESTA pestaña")
            return
        }
        // relanzar sondeos pendientes (con cookies/referer de la pagina)
        kickProbes()

        val dlgView = layoutInflater.inflate(R.layout.dialog_media, null)
        val lv = dlgView.findViewById<ListView>(R.id.mediaList)
        val btnSort = dlgView.findViewById<Button>(R.id.btnSort)
        val btnClear = dlgView.findViewById<Button>(R.id.btnClear)
        var dlgRef: android.app.Dialog? = null
        val modes = arrayOf(
            "por detección", "tamaño ↓", "tamaño ↑", "duración ↓", "duración ↑", "tipo", "nombre")
        btnSort.text = "Ordenar: ${modes[sortMode()]}"

        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = sortedDetected().size
            override fun getItem(p: Int): Detected = sortedDetected()[p]
            override fun getItemId(p: Int): Long = p.toLong()
            override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
                val v = cv ?: layoutInflater.inflate(R.layout.item_media, parent, false)
                val d = getItem(p)
                v.findViewById<TextView>(R.id.mBadge).text = Kit.fmtOf(d.url)
                v.findViewById<TextView>(R.id.mTitle).text =
                    "${MediaProbe.hostOf(d.url)} — ${d.url.substringAfterLast('/').takeLast(52)}"
                val info = d.info
                var line = info?.line() ?: "consultando…"
                val cls = Kit.mediaClass(d.url)
                if (cls == "stream" && info?.variants?.isEmpty() == false) line += " · ${info.variants.size} calidades"
                if (info?.audioUrl != null) line += " · audio separado"
                v.findViewById<TextView>(R.id.mInfo).text = line
                return v
            }
        }
        lv.adapter = adapter

        btnSort.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Ordenar medios detectados")
                .setSingleChoiceItems(modes, sortMode()) { dd, which ->
                    eng.sp.edit().putInt("sortMedia", which).apply()
                    btnSort.text = "Ordenar: ${modes[which]}"
                    adapter.notifyDataSetChanged()
                    dd.dismiss()
                }
                .setNegativeButton("Cerrar", null)
                .show()
        }
        btnClear.setOnClickListener {
            val cu = tabs.getOrNull(cur)?.uid ?: -1
            synchronized(detected) { detected.values.removeAll { it.tabUid == cu } }
            updateDetectBtn()
            dlgRef?.dismiss()
            toast("Capturas de esta pestaña limpiadas")
        }

        val dlg = AlertDialog.Builder(this)
            .setTitle("Medios detectados: $mine en esta pestaña")
            .setView(dlgView)
            .setNeutralButton("Cerrar", null)
            .create()
        dlgRef = dlg
        liveAdapter = adapter
        dlg.setOnDismissListener { if (dlgRef === dlg) liveAdapter = null }
        lv.setOnItemClickListener { _, _, p, _ ->
            val d = sortedDetected()[p]
            showMediaActions(dlg, d)
        }
        dlg.show()
        // refresco mientras llegan sondeos
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                if (!dlg.isShowing) return
                adapter.notifyDataSetChanged()
                handler.postDelayed(this, 1200)
            }
        }
        handler.post(tick)
    }

    private fun showMediaActions(parentDlg: android.app.Dialog, d: Detected) {
        val labels = ArrayList<String>()
        val info = d.info
        val esStream = d.url.substringBefore('?').let {
            it.endsWith(".m3u8") || it.endsWith(".mpd") || it.contains("/api/manifest/dash")
        }
        // la elección de calidad PRIMERO cuando hay variantes; si el sondeo
        // automático aún no las resolvió, ofrecer analizarlas al momento
        if (info != null && info.variants.any { !it.isAudio }) labels.add("🎚 Elegir calidad…")
        else if (esStream) labels.add("🔎 Analizar calidades…")
        labels.add("⬇ Descargar ahora")
        labels.add("⏳ Añadir a la cola")
        val audioVar = info?.variants?.firstOrNull { it.isAudio }
        if (info?.audioUrl != null || audioVar != null) labels.add("🎵 Descargar solo audio")
        labels.add("🔗 Copiar enlace")

        AlertDialog.Builder(this)
            .setTitle(Kit.fmtOf(d.url) + " · " + (info?.line() ?: MediaProbe.hostOf(d.url)))
            .setItems(labels.toTypedArray()) { _, which ->
                when {
                    labels[which].startsWith("🎚") -> showVariants(d, false)
                    labels[which].startsWith("🔎") -> analyzeVariants(d, false)
                    labels[which].startsWith("⬇") -> startDownload(d, null, false)
                    labels[which].startsWith("⏳") -> startDownload(d, null, true)
                    labels[which].startsWith("🎵") -> showVariants(d, true)
                    labels[which].startsWith("🔗") -> {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("url", d.url))
                        toast("Copiado")
                    }
                }
            }
            .show()
    }

    /**
     * Analiza el manifiesto bajo demanda (sondeo forzado, sin caché) y
     * abre el selector de calidades. Cubre el informe del usuario: streams
     * m3u8 cuyas variantes no llegaron a resolverse en el sondeo automático.
     */
    private fun analyzeVariants(d: Detected, onlyAudio: Boolean) {
        val dlg = AlertDialog.Builder(this)
            .setTitle("Analizando el stream…")
            .setMessage("Leyendo el manifiesto y resolviendo calidades…")
            .setCancelable(false)
            .create()
        dlg.show()
        val headers = HashMap<String, String>()
        try {
            headers["User-Agent"] = web.settings.userAgentString
            val ck = CookieManager.getInstance().getCookie(d.url)
            if (!ck.isNullOrBlank()) headers["Cookie"] = ck
            val page = web.url
            if (!page.isNullOrBlank()) headers["Referer"] = page
        } catch (e: Exception) { }
        MediaProbe.probe(d.url, headers, force = true) { info ->
            dlg.dismiss()
            d.info = info
            d.probed = true
            val vars = info.variants.filter { if (onlyAudio) it.isAudio else !it.isAudio }
            if (vars.isEmpty()) toast("No se han encontrado calidades para este stream")
            else showVariants(d, onlyAudio)
        }
    }

    private fun showVariants(d: Detected, onlyAudio: Boolean) {
        val info = d.info ?: return
        val vars = info.variants.filter { if (onlyAudio) it.isAudio else !it.isAudio }
        if (vars.isEmpty()) { toast("Sin calidades disponibles"); return }
        val labels = vars.map { v ->
            val sz = if (v.size > 0) " · " + Kit.fmtBytes(v.size) + " ≈" else ""
            val du = if (v.dur > 0) " · " + Kit.fmtDur(v.dur) else ""
            "${v.label}$du$sz"
        }
        AlertDialog.Builder(this)
            .setTitle(if (onlyAudio) "Pistas de audio" else "Calidades disponibles")
            .setItems(labels.toTypedArray()) { _, which ->
                val v = vars[which]
                // "Descargar ahora" o "cola" para esta calidad
                AlertDialog.Builder(this)
                    .setTitle(v.label)
                    .setItems(arrayOf("⬇ Descargar ahora", "⏳ Añadir a la cola")) { _, w ->
                        startDownload(d, v, w == 1)
                    }
                    .show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun startDownload(d: Detected, variant: MediaProbe.Variant?, enqueue: Boolean) {
        startDownloadRaw(d.url, variant?.extraJson, enqueue)
    }

    /** Descarga de un enlace (deteccion o listener del WebView) preguntando si va a la cola.
     *  Para streams (m3u8/mpd) ofrece también ELEGIR LA CALIDAD concreta:
     *  el motor acepta la variante elegida vía extra "variantUrl". */
    private fun askDownload(url: String, extraJson: String?) {
        val low = url.substringBefore('?').substringBefore('#').lowercase(Locale.US)
        val esStream = low.endsWith(".m3u8") || low.endsWith(".mpd") ||
            low.contains("/api/manifest/dash")
        val dlg = AlertDialog.Builder(this)
            .setTitle("Descarga detectada")
            .setMessage(MediaProbe.hostOf(url) + " — " + url.takeLast(80))
            .setPositiveButton("⬇ Ahora") { _, _ -> startDownloadRaw(url, extraJson, false) }
            .setNegativeButton("⏳ Cola") { _, _ -> startDownloadRaw(url, extraJson, true) }
        if (esStream) {
            dlg.setNeutralButton("🎚 Elegir calidad") { _, _ ->
                analyzeVariants(Detected(url, "captura").apply {
                    tabUid = tabs.getOrNull(cur)?.uid ?: -1
                }, false)
            }
        } else {
            dlg.setNeutralButton("Cancelar", null)
        }
        dlg.show()
    }

    private fun startDownloadRaw(url: String, variantExtra: String?, enqueue: Boolean) {
        val headers = pageHeaders(url)
        val low = url.substringBefore('?').lowercase(Locale.US)
        val kind = when {
            low.endsWith(".m3u8") -> Kind.HLS
            low.endsWith(".mpd") -> Kind.DASH
            else -> 0 // auto
        }
        val cls = Kit.mediaClass(url)
        val cat = when (cls) {
            "video", "stream" -> 1
            "audio" -> 2
            else -> 0
        }
        // titulo de la pagina que olfateo el medio (para "Sitio - Titulo")
        val dInfo = synchronized(detected) { detected[url] }
        val pageTitle = dInfo?.pageTitle?.takeIf { it.isNotBlank() }
            ?: web.title?.takeIf { it.isNotBlank() } ?: ""
        // extension: de la URL, del mime del sondeo o del tipo googlevideo
        var ext = Kit.extOf(Kit.nameFromUrl(url))
        if (ext.isEmpty()) {
            val mime = dInfo?.info?.mimeType ?: ""
            ext = when {
                mime.contains("mp4") || mime.contains("quicktime") -> "mp4"
                mime.contains("webm") -> "webm"
                mime.contains("audio/mp4") -> "m4a"
                mime.contains("mpegurl") -> "m3u8"
                mime.contains("dash") -> "mpd"
                url.contains("videoplayback") && url.contains("mime=audio") -> "m4a"
                url.contains("videoplayback") -> "mp4"
                else -> ""
            }
        }
        var extra = variantExtra
        if (extra != null) {
            val o = JSONObject(extra)
            // duracion conocida por el sondeo
            synchronized(detected) {
                val d = detected[url]
                val i = d?.info
                if (i != null && i.durationSec > 0) o.put("dur", Math.round(i.durationSec))
                if (i != null && i.height > 0 && !o.has("q")) o.put("q", "${i.height}p")
            }
            extra = o.toString()
        }
        // guardar la página de origen: permite refrescar el enlace si caduca
        val o2 = if (extra != null) JSONObject(extra) else JSONObject()
        val origin = web.url?.takeIf { it.startsWith("http") } ?: ""
        if (origin.isNotEmpty()) o2.put("origin", origin)
        if (pageTitle.isNotBlank()) o2.put("ptitle", pageTitle)
        extra = o2.toString()
        // nombre "Sitio - Titulo": el sitio sale de la página de origen
        // (Youtube, Facebook...), no del CDN donde vive el archivo
        val finalName = if (pageTitle.isNotBlank())
            Kit.suggestName(pageTitle, url, ext, origin.ifEmpty { url }) else ""
        eng.add(url, finalName, kind, cat, eng.sp.getInt("conns", 4), headers, enqueue, extra) {
            if (it != null) {
                toast(if (enqueue) "En cola: ${it.name}" else "Descargando: ${it.name}")
                val i = android.content.Intent(this, com.turbodl.service.DownloadService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(i)
                else startService(i)
            }
        }
    }

    override fun onBackPressed() {
        if (homeOverlay.isVisible()) {
            showHome(false)
        } else if (web.canGoBack()) web.goBack()
        else super.onBackPressed()
    }

    override fun onDestroy() {
        flushStats()
        tabs.forEach { t ->
            t.wv?.let { w ->
                try {
                    w.removeJavascriptInterface("__tdlBridge")
                    w.destroy()
                } catch (e: Exception) { }
            }
        }
        super.onDestroy()
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
