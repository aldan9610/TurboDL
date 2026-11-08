package com.turbodl.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.GridView
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

    private lateinit var web: WebView
    private lateinit var editUrl: AutoCompleteTextView
    private lateinit var btnVideos: Button
    private lateinit var btnDesk: Button
    private lateinit var btnTabs: Button
    private lateinit var prog: ProgressBar
    private lateinit var homeOverlay: LinearLayout
    private lateinit var grid: GridView
    private val eng get() = App.app.engine
    private var desktopMode = false

    // ---------------- pestañas (1 WebView vivo: bajo consumo) ----------------

    /** Estado ligero de una pestaña: url, titulo y back/forward del WebView. */
    inner class Tab(var url: String, var title: String) {
        var state: Bundle? = null
        var showHome: Boolean = url.isEmpty()
        var priv: Boolean = false // pestaña privada: sin historial ni persistencia
    }
    private val tabs = ArrayList<Tab>()
    private var cur = 0
    private var blockedInPage = 0
    private var readerMode = false

    companion object {
        private val MEDIA_RE = Regex(
            "\\.(m3u8|mpd|mp4|webm|mov|m4s|mp3|aac|m4a|ogg|opus|flac|wav|avi|flv|mkv)(\\?|$)",
            RegexOption.IGNORE_CASE
        )

        /**
         * Bloqueador de anuncios/rastreadores por host (lista compacta de
         * tokens; SIN CDNs de video para no romper reproducciones).
         */
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
          var seen = {};
          function send(u, t){
            try {
              if (!u || u.indexOf('data:') === 0 || u.indexOf('blob:') === 0) return;
              u = new URL(u, location.href).href;
              if (seen[u] || !M.test(u)) return; seen[u] = 1;
              if (window.__tdlBridge) window.__tdlBridge.sniff(u, t);
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
              if (window.__tdlBridge) window.__tdlBridge.sniff(u, t || '');
            } catch(e){}
          }
          var M = /\.(m3u8|mpd|mp4|webm|mov|m4s|mp3|aac|m4a|ogg|opus|flac|wav|avi|flv|mkv)(\?|$)/i;
          var X = XMLHttpRequest.prototype.open;
          XMLHttpRequest.prototype.open = function(m, u){
            try { if (u && (M.test(u) || /mpegurl|dash|f4m/i.test(u))) send(u, 'xhr'); } catch(e){}
            return X.apply(this, arguments);
          };
          var F = window.fetch;
          if (F) window.fetch = function(u, o){
            try {
              var s = (typeof u === 'string') ? u : (u && u.url) || '';
              if (s && (M.test(s) || /mpegurl|\.mpd(\?|$)/i.test(s))) send(s, 'fetch');
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
    }
    private val detected = LinkedHashMap<String, Detected>()
    private var probing = false

    inner class Bridge {
        @JavascriptInterface
        fun sniff(url: String, type: String) {
            runOnUiThread { addDetected(url, type) }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        Ui.theme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.browser)
        Ui.styleAll(findViewById(android.R.id.content))
        web = findViewById(R.id.web)
        editUrl = findViewById(R.id.editUrl)
        btnVideos = findViewById(R.id.btnVideos)
        btnDesk = findViewById(R.id.btnDesk)
        btnTabs = findViewById(R.id.btnTabs)
        prog = findViewById(R.id.webProg)
        homeOverlay = findViewById(R.id.homeOverlay)
        grid = findViewById(R.id.gridSites)

        val ws = web.settings
        ws.javaScriptEnabled = true
        ws.domStorageEnabled = true
        ws.mediaPlaybackRequiresUserGesture = false
        ws.loadWithOverviewMode = true
        ws.useWideViewPort = true
        ws.cacheMode = WebSettings.LOAD_DEFAULT
        desktopMode = Profiles.isDesktop(eng.sp)
        applyProfile(false)

        CookieManager.getInstance().setAcceptCookie(true)

        web.addJavascriptInterface(Bridge(), "__tdlBridge")
        web.webViewClient = client
        web.webChromeClient = chrome

        web.setDownloadListener { url, _, _, _, _ ->
            askDownload(url, null)
        }

        findViewById<Button>(R.id.btnBack).setOnClickListener { if (web.canGoBack()) web.goBack() }
        findViewById<Button>(R.id.btnFwd).setOnClickListener { if (web.canGoForward()) web.goForward() }
        findViewById<Button>(R.id.btnGo).setOnClickListener { go() }
        findViewById<Button>(R.id.btnHome).setOnClickListener { showHome(!homeOverlay.isVisible()) }
        btnVideos.setOnClickListener { showDetected() }
        btnDesk.setOnClickListener { toggleDesktop() }
        btnTabs.setOnClickListener { showTabs() }
        findViewById<Button>(R.id.btnMore).setOnClickListener { showBrowserMenu(it) }
        findViewById<Button>(R.id.btnAddSite).setOnClickListener { addSiteDialog() }
        findViewById<Button>(R.id.btnResetSites).setOnClickListener {
            saveSites(DEFAULT_SITES.toList())
            refreshSites()
            toast("Accesos restaurados")
        }

        editUrl.setOnEditorActionListener { _, _, _ -> go(); true }
        setupHistoryBar()

        readerMode = eng.sp.getBoolean("readerMode", false)
        restoreTabs()
        showHome(true)

        // llegada directa con URL (p.ej. "abrir en navegador" desde el inicio)
        val openUrl = intent?.getStringExtra("openUrl")
        if (!openUrl.isNullOrEmpty()) load(openUrl)
    }

    private fun View.isVisible(): Boolean = visibility == View.VISIBLE

    // ---------------- perfil de navegacion / escritorio ----------------

    private fun applyProfile(reload: Boolean) {
        Ui.applyUa(web, eng.sp, desktopMode)
        btnDesk.alpha = if (desktopMode) 1f else 0.45f
        btnDesk.contentDescription = if (desktopMode) "Modo escritorio activado" else "Modo móvil"
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
        pm.menu.add(0, 9, 3, "🗂 Pestañas (${tabCount()}) · nueva")
        pm.menu.add(0, 11, 4, "🕵 Nueva pestaña privada")
        pm.menu.add(0, 10, 5, "🕘 Historial de navegación")
        pm.menu.add(0, 12, 6, "⭐ Marcadores")
        pm.menu.add(0, 13, 7, "⭐ Añadir esta página a marcadores")
        pm.menu.add(0, 14, 8, if (readerMode) "📖 Modo lector: activado" else "📖 Modo lector")
        pm.menu.add(0, 15, 9, "🔍 Escanear medios de esta página")
        pm.menu.add(0, 16, 10, if (eng.sp.getBoolean("adblock", true))
            "🛡 Bloqueador de anuncios: activado (${blockedInPage} aquí)" else "🛡 Bloqueador de anuncios: apagado")
        pm.menu.add(0, 8, 11, "↻ Recargar página")
        pm.menu.add(0, 4, 12, "🧹 Limpiar historial de búsqueda")
        pm.menu.add(0, 5, 13, "🍪 Limpiar cookies y caché")
        pm.menu.add(0, 7, 14, "📌 Añadir esta página a accesos")
        pm.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
                1 -> showEngineMenu(v)
                2 -> showProfileMenu(v)
                3 -> toggleDesktop()
                9 -> newTab("")
                11 -> newTab("", privateTab = true)
                10 -> showPageHistory()
                12 -> showBookmarks()
                13 -> { addBookmark(curTab?.url ?: "", web.title ?: ""); toast("Añadido a marcadores") }
                14 -> toggleReader()
                15 -> { inject(DEEP_SCAN_JS); toast("Escaneando medios de la página…") }
                16 -> {
                    val nv = !eng.sp.getBoolean("adblock", true)
                    eng.sp.edit().putBoolean("adblock", nv).apply()
                    toast(if (nv) "Bloqueador activado" else "Bloqueador apagado")
                    web.reload()
                }
                8 -> { showHome(false); web.reload() }
                4 -> { clearHistory(); toast("Historial de búsqueda borrado") }
                5 -> clearPrivacy()
                7 -> addCurrentAsSite()
            }
            true
        }
        pm.show()
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
            web.clearCache(true)
            web.clearFormData()
            web.clearHistory()
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
        if (tabs.isNotEmpty()) {
            tabs[cur].url = url
            tabs[cur].showHome = false
        }
        web.loadUrl(url)
        persistTabs()
    }

    private fun showHome(show: Boolean) {
        homeOverlay.visibility = if (show) View.VISIBLE else View.GONE
        if (tabs.isNotEmpty()) tabs[cur].showHome = show
        if (show) {
            refreshSites()
            hideKb()
        }
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

    /** Guarda el estado del WebView en la pestaña activa. */
    private fun stashCurrent() {
        if (tabs.isEmpty()) return
        val t = tabs[cur]
        t.url = web.url ?: t.url
        t.title = web.title ?: t.title
        t.showHome = homeOverlay.isVisible()
        try {
            // congelado: a partir de 5 pestañas, el estado back/forward de las
            // viejas se suelta (quedan por URL) para acotar la RAM
            if (tabs.size <= 5) {
                val b = Bundle()
                if (web.saveState(b) != null) t.state = b else t.state = t.state
            } else if (t.state != null && t.state!!.size() > 0) {
                // conservar si ya existia; no crecer mas
            }
        } catch (e: Exception) { }
    }

    private fun newTab(url: String = "", switchNow: Boolean = true, privateTab: Boolean = false) {
        if (tabs.size >= 8) { toast("Máximo 8 pestañas"); return }
        stashCurrent()
        val nt = Tab(url, "")
        nt.priv = privateTab
        tabs.add(nt)
        if (switchNow) switchTab(tabs.size - 1)
        persistTabs()
        if (privateTab) toast("Pestaña privada: no se guarda historial")
    }

    private fun closeTab(index: Int) {
        if (index < 0 || index >= tabs.size) return
        val wasCurrent = index == cur
        tabs.removeAt(index)
        if (tabs.isEmpty()) {
            cur = 0
            tabs.add(Tab("", ""))
            activateTab(0)
        } else {
            if (cur >= tabs.size) cur = tabs.size - 1
            if (wasCurrent) activateTab(cur)
        }
        persistTabs()
        updateTabButton()
    }

    private fun switchTab(index: Int) {
        if (index < 0 || index >= tabs.size || index == cur) return
        stashCurrent()
        cur = index
        activateTab(index)
        persistTabs()
    }

    /** Carga en el WebView el estado de la pestaña indicada. */
    private fun activateTab(index: Int) {
        val t = tabs[index]
        web.stopLoading()
        val st = t.state
        var restored = false
        if (st != null && st.size() > 0) {
            try { restored = web.restoreState(st) != null } catch (e: Exception) { }
        }
        if (!restored) {
            if (t.url.isNotEmpty()) web.loadUrl(t.url)
        }
        editUrl.setText(t.url)
        homeOverlay.visibility = if (t.showHome && t.url.isEmpty()) View.VISIBLE else View.GONE
        if (t.showHome && t.url.isNotEmpty() && !restored) homeOverlay.visibility = View.GONE
        updateTabButton()
    }

    private fun updateTabButton() {
        btnTabs.text = "🗂 ${tabCount()}"
        if (cur in tabs.indices && tabs[cur].title.isNotEmpty() && tabs[cur].url.isNotEmpty()) {
            btnTabs.contentDescription = "Pestañas: ${tabs[cur].title}"
        }
    }

    /** Conmutador de pestañas: abrir, cerrar (pulsación larga), nueva, limpiar. */
    private fun showTabs() {
        stashCurrent()
        val lay = LinearLayout(this)
        lay.orientation = LinearLayout.VERTICAL
        val lv = ListView(this)
        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = tabs.size
            override fun getItem(p: Int): Tab = tabs[p]
            override fun getItemId(p: Int): Long = p.toLong()
            override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
                val t = getItem(p)
                val pad = 40
                val v = TextView(this@BrowserActivity)
                v.setPadding(pad, 30, pad, 30)
                v.textSize = 13f
                val mark = when {
                    p == cur -> "▶  "
                    t.priv -> "🕵  "
                    else -> "    "
                }
                val host = if (t.url.isEmpty()) "inicio" else (MediaProbe.hostOf(t.url) + " — " + t.title)
                v.text = mark + host.take(70)
                v.background = getDrawable(android.R.drawable.list_selector_background)
                return v
            }
        }
        lv.adapter = adapter
        lay.addView(lv, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 600))
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
            tabs.clear()
            tabs.add(Tab("", ""))
            cur = 0
            activateTab(0)
            persistTabs()
        }
        lay.addView(row)
        val dlg = AlertDialog.Builder(this)
            .setTitle("Pestañas (${tabs.size})")
            .setView(lay)
            .setNegativeButton("Cerrar", null)
            .create()
        dlgRef[0] = dlg
        lv.setOnItemClickListener { _, _, p, _ ->
            dlg.dismiss()
            switchTab(p)
        }
        lv.setOnItemLongClickListener { _, _, p, _ ->
            dlg.dismiss()
            closeTab(p)
            toast("Pestaña cerrada")
            true
        }
        dlg.show()
    }

    /** Guarda la lista de pestañas (solo url/titulo; el estado vivo no cabe).
     *  Las pestañas privadas no se persisten. */
    private fun persistTabs() {
        try {
            val arr = org.json.JSONArray()
            var normalCur = 0
            var normalIdx = 0
            for ((i, t) in tabs.withIndex()) {
                if (t.priv) { if (i < cur) normalIdx-- ; continue }
                val o = org.json.JSONObject()
                o.put("u", t.url)
                o.put("t", t.title)
                o.put("h", t.showHome)
                arr.put(o)
            }
            normalCur = (cur + normalIdx).coerceIn(0, if (arr.length() == 0) 0 else arr.length() - 1)
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
        activateTab(cur)
        updateTabButton()
    }

    override fun onPause() {
        stashCurrent()
        persistTabs()
        super.onPause()
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
            prog.visibility = View.VISIBLE
            blockedInPage = 0
            if (url != null) {
                editUrl.setText(url)
                if (tabs.isNotEmpty() && url != "about:blank") tabs[cur].url = url
            }
            homeOverlay.visibility = View.GONE
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            prog.visibility = View.GONE
            inject()
            if (readerMode) inject(READERS_JS)
            if (url != null && url.startsWith("http") && tabs.isNotEmpty()) {
                val t = tabs[cur]
                t.url = url
                t.title = web.title ?: ""
                t.showHome = false
                updateTabButton()
                // historial de paginas (las pestañas privadas no registran)
                if (!t.priv) {
                    try { eng.db.addHistory(url, t.title) } catch (e: Exception) { }
                }
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
            return false
        }

        override fun shouldInterceptRequest(
            view: WebView?, request: WebResourceRequest?
        ): WebResourceResponse? {
            val u = request?.url?.toString() ?: return null
            if (MEDIA_RE.containsMatchIn(u)) addDetected(u, "req")
            // bloqueador de anuncios/rastreadores (por host, lista compacta)
            if (eng.sp.getBoolean("adblock", true)) {
                val host = (request.url.host ?: "").lowercase(Locale.US)
                if (host.isNotEmpty() && AD_HOSTS.any { host.contains(it) }) {
                    blockedInPage++
                    return WebResourceResponse(
                        "text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                }
            }
            return null
        }
    }

    private val chrome = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            prog.progress = newProgress
            prog.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            if (newProgress >= 90) inject()
        }
    }

    private fun inject() {
        try { web.evaluateJavascript(HOOK, null) } catch (e: Exception) { }
    }

    private fun inject(js: String) {
        try { web.evaluateJavascript(js, null) } catch (e: Exception) { }
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
    private fun addDetected(url: String, type: String) {
        if (detected.size > 200) return
        if (detected.containsKey(url)) return
        if (!Kit.isVideoOrAudio(url) && !url.contains("m3u8") && !url.contains(".mpd")) return
        detected[url] = Detected(url, type)
        btnVideos.text = "⬇ Detectados: ${detected.size}"
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
        val list = detected.values.toList()
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
        if (detected.isEmpty()) {
            toast("Aún no se detectan videos en esta página")
            return
        }
        // lanzar sondeos pendientes (con cookies/referer de la pagina)
        if (!probing) {
            probing = true
            val headers = HashMap<String, String>()
            headers["User-Agent"] = web.settings.userAgentString
            try {
                val ck = CookieManager.getInstance().getCookie(web.url)
                if (!ck.isNullOrBlank()) headers["Cookie"] = ck
                val page = web.url
                if (!page.isNullOrBlank()) headers["Referer"] = page
            } catch (e: Exception) { }
            val snapshot = detected.values.toList()
            var pending = snapshot.count { it.info == null }
            if (pending == 0) probing = false
            for (d in snapshot) {
                if (d.info != null) continue
                MediaProbe.probe(d.url, headers) { info ->
                    d.info = info
                    d.probed = true
                    pending--
                    if (pending <= 0) probing = false
                }
            }
        }

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
            detected.clear()
            MediaProbe.clearCache()
            btnVideos.text = "⬇ Detectados: 0"
            dlgRef?.dismiss()
        }

        val dlg = AlertDialog.Builder(this)
            .setTitle("Medios detectados (${detected.size})")
            .setView(dlgView)
            .setNeutralButton("Cerrar", null)
            .create()
        dlgRef = dlg
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
        labels.add("⬇ Descargar ahora")
        labels.add("⏳ Añadir a la cola")
        val info = d.info
        if (info != null && info.variants.any { !it.isAudio }) labels.add("🎚 Elegir calidad…")
        val audioVar = info?.variants?.firstOrNull { it.isAudio }
        if (info?.audioUrl != null || audioVar != null) labels.add("🎵 Descargar solo audio")
        labels.add("🔗 Copiar enlace")

        AlertDialog.Builder(this)
            .setTitle(Kit.fmtOf(d.url) + " · " + (info?.line() ?: MediaProbe.hostOf(d.url)))
            .setItems(labels.toTypedArray()) { _, which ->
                when {
                    labels[which].startsWith("⬇") -> startDownload(d, null, false)
                    labels[which].startsWith("⏳") -> startDownload(d, null, true)
                    labels[which].startsWith("🎚") -> showVariants(d, false)
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

    /** Descarga de un enlace (deteccion o listener del WebView) preguntando si va a la cola. */
    private fun askDownload(url: String, extraJson: String?) {
        AlertDialog.Builder(this)
            .setTitle("Descarga detectada")
            .setMessage(MediaProbe.hostOf(url) + " — " + url.takeLast(80))
            .setPositiveButton("⬇ Ahora") { _, _ -> startDownloadRaw(url, extraJson, false) }
            .setNegativeButton("⏳ Cola") { _, _ -> startDownloadRaw(url, extraJson, true) }
            .setNeutralButton("Cancelar", null)
            .show()
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
        eng.add(url, "", kind, cat, eng.sp.getInt("conns", 4), headers, enqueue, extra) {
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
        try {
            web.removeJavascriptInterface("__tdlBridge")
            web.destroy()
        } catch (e: Exception) { }
        super.onDestroy()
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
