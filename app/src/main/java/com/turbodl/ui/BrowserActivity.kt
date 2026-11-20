package com.turbodl.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.SearchManager
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
import com.turbodl.util.Favicons
import com.turbodl.util.Profiles
import com.turbodl.util.Users
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

    /** Lock para todas las mutaciones e iteraciones de `tabs` (4.2: H2).
     *  `shouldInterceptRequest` corre en hilo de red y antes iteraba `tabs`
     *  con `firstOrNull` mientras el hilo UI la mutaba (newTab/closeTab) →
     *  ConcurrentModificationException al cerrar una pestaña mientras
     *  cargan subrecursos. Ahora toda mutación y toda iteración de `tabs`
     *  pasa por este lock. La lista es pequeña (max 8) así que el coste es
     *  despreciable y no hay riesgo de ANR. */
    private val tabsLock = Any()

    /** Snapshot sincronizada de las pestañas (para iterar sin CME). */
    private fun snapshotTabs(): List<Tab> = synchronized(tabsLock) { ArrayList(tabs) }

    /** Pestaña activa de forma segura (hilo de red o UI). */
    private fun currentTab(): Tab? = synchronized(tabsLock) {
        if (cur in tabs.indices) tabs[cur] else null
    }

    /** Handler del hilo UI compartido (4.2: para poder cancelar los
     *  postDelayed pendientes en onDestroy y evitar crashes por
     *  tocar Views destruidos). Antes se creaba ad-hoc con
     *  Handler(Looper.getMainLooper()) en onCreateWindow, lo que
     *  dejaba el newTab diferido colgando tras onDestroy. */
    private val uiHandler = Handler(Looper.getMainLooper())
    /** WebViews temporales creados en onCreateWindow para capturar la
     *  URL del window.open; se destruyen al disparar el postDelayed o
     *  en onDestroy para evitar fugas. */
    private val pendingTempWebViews = java.util.Collections.synchronizedList(mutableListOf<WebView>())

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

        // Colores centralizados de la interfaz (4.0): sustituyen a los
        // literales hex 0xFF212121 / 0xFF1565C0 / 0xFF3A3A3A repartidos por
        // toda la clase, para que un cambio de paleta se haga en un punto.
        /** Color de acento (azul) para elementos activos/seleccionados. */
        internal val COLOR_ACCENT = 0xFF1565C0.toInt()
        /** Color principal del texto sobre fondo claro. */
        internal val COLOR_TEXT_PRIMARY = 0xFF212121.toInt()
        /** Color secundario del texto (etiquetas, descripciones). */
        internal val COLOR_TEXT_SECONDARY = 0xFF3A3A3A.toInt()
        /** Gris medio para hints y texto deshabilitado. */
        internal val COLOR_TEXT_HINT = 0xFF9E9E9E.toInt()
        /** Naranja de acento (colorPrimary del tema) para indicadores activos. */
        internal val COLOR_ORANGE = 0xFFFF6D00.toInt()
        /** Gris claro para puntos inactivos del paginador. */
        internal val COLOR_DOT_OFF = 0xFFCCCCCC.toInt()

        // ---------------- menú inferior paginado (4.0) ----------------
        // BUG 1: antes el menú asumaba 2 páginas; aquí se declaran como
        // constantes para que añadir/eliminar páginas no exija tocar todos
        // los `for (i in 0 until 3)` repartidos por la clase.
        /** Número de páginas (submenús) del menú inferior. */
        internal const val MENU_PAGE_COUNT = 3
        /** Columnas por página del menú (5 iconos por fila, como Via Browser). */
        internal const val MENU_COLUMNS = 5
        /** Filas por página del menú (10 acciones por página). */
        internal const val MENU_ROWS = 2

        // ---------------- paneles de accesos directos (4.0) ----------------
        /** Número de paneles de accesos (Comunes / IA / Mis sitios). */
        internal const val SITES_PANEL_COUNT = 3
        /** Columnas de cada panel de accesos (4 por fila, estilo launchador). */
        internal const val SITES_COLUMNS = 4
        /** Filas visibles por panel antes de scroll vertical. */
        internal const val SITES_ROWS_VISIBLE = 3

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

        /** Panel 1: sitios comunes (catálogo inicial, reordenable/eximible). */
        private val COMMON_SITES = arrayOf(
            "YouTube|https://m.youtube.com",
            "Facebook|https://m.facebook.com",
            "Instagram|https://www.instagram.com",
            "X (Twitter)|https://x.com",
            "TikTok|https://www.tiktok.com",
            "Reddit|https://www.reddit.com",
            "Wikipedia|https://es.wikipedia.org",
            "WhatsApp Web|https://web.whatsapp.com",
            "Google|https://www.google.com",
            "Gmail|https://mail.google.com",
            "Maps|https://maps.google.com",
            "Amazon|https://www.amazon.com"
        )

        /** Panel 2: inteligencia artificial (chats para prompts y notas). */
        private val AI_SITES = arrayOf(
            "Z.ai|https://chat.z.ai",
            "ChatGPT|https://chatgpt.com",
            "DeepSeek|https://chat.deepseek.com",
            "Gemini|https://gemini.google.com",
            "Claude|https://claude.ai",
            "Perplexity|https://www.perplexity.ai",
            "Copilot|https://copilot.microsoft.com",
            "Qwen|https://chat.qwen.ai",
            "Hugging Face|https://huggingface.co/chat",
            "Grok|https://grok.com"
        )

        /** Compatibilidad: catálogo antiguo (migración de «Mis sitios»). */
        private val DEFAULT_SITES = COMMON_SITES

        /** Detecta formularios de inicio de sesión y captura el envío (4.0). */
        private val CREDS_JS = """
        (function(){
          if (window.__tdlCreds) return; window.__tdlCreds = 1;
          function forms(){ return Array.prototype.slice.call(document.querySelectorAll('form')).filter(function(f){ return f.querySelector('input[type=password]'); }); }
          function hook(f){
            if (f.__tdlHooked) return; f.__tdlHooked = 1;
            f.addEventListener('submit', function(){
              try {
                var p = f.querySelector('input[type=password]');
                var u = f.querySelector('input[type=text],input[type=email],input[name*=user i],input[name*=mail i],input[name*=login i],input[id*=user i],input[id*=mail i]');
                if (p && p.value && u && u.value && window.__tdlBridge)
                  window.__tdlBridge.saveCreds(location.hostname, u.value, p.value);
              } catch(e){}
            }, true);
          }
          forms().forEach(hook);
          try {
            new MutationObserver(function(){ forms().forEach(hook); })
              .observe(document.documentElement, {childList:true, subtree:true});
          } catch(e){}
        })();
        """.trimIndent()

        /** Reenvía errores JS no capturados a la consola interna (4.0). */
        private val ONERROR_JS = """
        (function(){
          if (window.__tdlErr) return; window.__tdlErr = 1;
          window.onerror = function(msg, src, line){
            try { if (window.__tdlBridge)
              window.__tdlBridge.logConsole('error', msg + ' @ ' + (src||'') + ':' + (line||0));
            } catch(e){}
          };
        })();
        """.trimIndent()

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
    private lateinit var suggestionsList: android.widget.ListView
    private lateinit var tabSheet: LinearLayout
    private lateinit var tabListHost: LinearLayout
    private lateinit var menuSheet: android.view.View
    private lateinit var menuPager: HorizontalScrollView
    private lateinit var menuPages: LinearLayout
    private lateinit var menuDots: LinearLayout
    private var menuPage = 0
    // paneles de accesos (4.0): swipe entre Sitios comunes / IA / Mis sitios
    private lateinit var sitesPager: HorizontalScrollView
    private lateinit var sitesPages: LinearLayout
    private lateinit var sitesDots: LinearLayout
    private lateinit var txtSitesTitle: TextView
    private lateinit var btnEditSites: Button
    private lateinit var fab: Button
    private var sitesPage = 0
    private var editSites = false
    // descargas directas detectadas por el DownloadListener (además del sniffing)
    private val downloadEvents = LinkedHashMap<String, Long>()
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

        @JavascriptInterface
        fun saveCreds(host: String, user: String, pass: String) {
            runOnUiThread { offerCredSave(host, user, pass) }
        }

        @JavascriptInterface
        fun logConsole(level: String, msg: String) {
            com.turbodl.util.DevLog.logCon(level.ifEmpty { "log" }, msg)
        }
    }

    /** Diálogo «¿Guardar inicio de sesión?» (4.0, estilo navegador). */
    private fun offerCredSave(host: String, user: String, pass: String) {
        if (host.isEmpty() || user.isEmpty() || pass.isEmpty()) return
        // 4.2 (H7): si la Activity ya se está cerrando, abrir un
        // AlertDialog lanza BadTokenException. El callback del
        // @JavascriptInterface (submit de un formulario) puede llegar
        // justo cuando el usuario ya salió del navegador.
        if (isFinishing || isDestroyed) return
        val uid = Users.active(this)
        val hostBase = baseOf(host)
        if (hostBase.isEmpty() || eng.db.neverFor(uid, hostBase)) return
        try {
            AlertDialog.Builder(this)
                .setTitle("¿Guardar inicio de sesión?")
                .setMessage("$hostBase\n\nUsuario: $user\n\nLas credenciales se cifran con la clave segura del dispositivo (AES-256-GCM, AndroidKeyStore) y solo las usa el usuario activo.")
                .setPositiveButton("Guardar") { _, _ ->
                    // 4.2 (H7): Secrets.encrypt puede lanzar si el
                    // AndroidKeyStore no está disponible (emuladores sin
                    // lock, ROMs raras). Lo envolvemos para no matar la
                    // app si el cifrado falla; el usuario lo reintenta.
                    try {
                        eng.db.saveCred(uid, hostBase, user,
                            com.turbodl.util.Secrets.encrypt(pass))
                        toast("Credenciales guardadas para $hostBase")
                    } catch (e: Throwable) {
                        android.util.Log.e("TurboDL.creds", "Fallo al cifrar/credenciales", e)
                        toast("No se pudieron guardar las credenciales: ${e.message ?: "error de cifrado"}")
                    }
                }
                .setNeutralButton("Nunca en este sitio") { _, _ ->
                    try { eng.db.setNeverCred(uid, hostBase) } catch (e: Exception) { }
                    toast("No se volverá a preguntar en $hostBase")
                }
                .setNegativeButton("No", null)
                .show()
        } catch (e: Exception) {
            android.util.Log.w("TurboDL.creds", "offerCredSave omitido (Activity no activa)", e)
        }
    }

    /** Autorrelleno de credenciales guardadas para el host de la página. */
    private fun maybeAutofill(view: WebView?, url: String) {
        val uid = Users.active(this)
        val host = baseOf(hostOf(url))
        if (host.isEmpty() || view == null) return
        try {
            if (eng.db.neverFor(uid, host)) return
            val c = eng.db.findCred(uid, host) ?: return
            val pass = com.turbodl.util.Secrets.decrypt(c.secret) ?: return
            if (c.username.isEmpty()) return
            injectOn(view, fillCredsJs(c.username, pass))
            toast("TurboDL rellenó tus credenciales guardadas")
        } catch (e: Exception) { }
    }

    private fun fillCredsJs(username: String, pass: String): String {
        val u = org.json.JSONObject.quote(username)
        val p = org.json.JSONObject.quote(pass)
        return "(function(){try{" +
            "var U=$u, P=$p;" +
            "var f=null;var fs=document.querySelectorAll('form');" +
            "for (var i=0;i<fs.length;i++){if(fs[i].querySelector('input[type=password]')){f=fs[i];break;}}" +
            "if(!f)return;" +
            "var pw=f.querySelector('input[type=password]');" +
            "var us=f.querySelector('input[type=text],input[type=email],input[name*=user i],input[name*=mail i],input[name*=login i],input[id*=user i],input[id*=mail i]');" +
            "function sv(el,v){if(!el)return;el.value=v;" +
            "el.dispatchEvent(new Event('input',{bubbles:true}));" +
            "el.dispatchEvent(new Event('change',{bubbles:true}));}" +
            "sv(us,U);sv(pw,P);" +
            "}catch(e){}})();"
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
        webHost = findViewById(R.id.webHost)
        sitesPager = findViewById(R.id.sitesPager)
        sitesPages = findViewById(R.id.sitesPages)
        sitesDots = findViewById(R.id.sitesDots)
        txtSitesTitle = findViewById(R.id.txtSitesTitle)
        btnEditSites = findViewById(R.id.btnEditSites)
        fab = findViewById(R.id.btnDetectFab)
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
            AlertDialog.Builder(this)
                .setTitle("Restaurar accesos")
                .setMessage("Vuelven a su estado inicial los tres paneles (Sitios comunes, Inteligencia artificial y Mis sitios). ¿Continuar?")
                .setPositiveButton("Restaurar") { _, _ ->
                    val u = Users.active(this)
                    eng.sp.edit()
                        .remove("sitesP0_$u").remove("sitesP1_$u")
                        .remove("sitesP2_$u").putString("sites", "[]").apply()
                    buildSitesPanels()
                    toast("Accesos restaurados")
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }

        editUrl.setOnEditorActionListener { _, _, _ -> go(); true }
        editUrl.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { }
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { }
            override fun afterTextChanged(s: android.text.Editable?) {
                showSuggestions(s?.toString() ?: "")
                // 4.0 U2: la X de limpieza apenas hay algo escrito
                findViewById<Button>(R.id.btnClear).visibility =
                    if ((s?.length ?: 0) > 0) View.VISIBLE else View.GONE
            }
        })
        // BUG 4 (informado por el usuario): "una vez ya realizada la búsqueda
        // al hacer click nuevamente no se elimina por defecto la URL donde
        // estoy algo que estropea la navegación rápida, quiero que esto
        // pase pero manteniendo la posibilidad de compartir enlace y copiar
        // URL al dejar presionado sobre esa".
        //
        // Solución:
        //  · TOQUE simple sobre la barra: si hay una URL cargada, la
        //    seleccionamos TODA y pedimos foco + teclado. Así, al escribir,
        //    el primer carácter sustituye la URL entera (navegación rápida).
        //    Si el campo está vacío no hacemos nada (el foco normal basta).
        //  · PULSACIÓN LARGA sobre la barra: menú contextual "Compartir
        //    enlace" / "Copiar URL" — se mantiene la posibilidad de copiar y
        //    compartir la URL actual sin necesidad de tocar el campo.
        editUrl.setOnClickListener {
            val currentUrl = editUrl.text?.toString() ?: ""
            if (currentUrl.isNotEmpty()) {
                // Selecciona todo el texto: el siguiente carácter escrito
                // reemplaza la URL (mismo comportamiento que Chrome/Firefox).
                editUrl.selectAll()
                editUrl.requestFocus()
                showKb()
            }
        }
        editUrl.setOnLongClickListener {
            showUrlContextMenu()
            true
        }
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
        // 4.0 U1: al tocar el buscador central, el texto pasa a la barra
        // superior (siempre visible sobre el teclado, nunca tapada)
        homeSearch.setOnFocusChangeListener { _, has ->
            if (has) {
                editUrl.setText(homeSearch.text.toString())
                editUrl.requestFocus()
                editUrl.setSelection(editUrl.text.length)
                showKb()
            }
        }
        // hojas inferiores: pestañas y menú
        tabSheet = findViewById(R.id.tabSheet)
        tabListHost = findViewById(R.id.tabListHost)
        menuSheet = findViewById(R.id.menuSheet)
        menuPager = findViewById(R.id.menuPager)
        menuPages = findViewById(R.id.menuPages)
        menuDots = findViewById(R.id.menuDots)
        suggestionsHost = findViewById(R.id.suggestionsHost)
        suggestionsList = findViewById(R.id.suggestionsList)
        findViewById<Button>(R.id.btnMenuCollapse).setOnClickListener { hideSheets() }
        findViewById<Button>(R.id.btnMenuExit).setOnClickListener { finishAffinity() }
        // 4.0 U2: X de limpieza — aparece con texto, borra sin cerrar el teclado
        findViewById<Button>(R.id.btnClear).setOnClickListener {
            suppressSuggest = true
            editUrl.setText("")
            suppressSuggest = false
            editUrl.requestFocus()
            showKb()
        }
        // 4.0 U8: modo edición explícito de accesos (arrastrar/eliminar)
        btnEditSites.setOnClickListener {
            editSites = !editSites
            buildSitesPanels()
            updateSitesTitle()
            toast(if (editSites)
                "Modo edición: mantén pulsado y arrastra para reordenar, ✕ para quitar"
            else "Modo lectura")
        }
        // 4.0 U5: FAB de descargas detectadas con contador en tiempo real
        fab.setOnClickListener { showDetectChooser() }
        // 4.0 U9: título del panel activo al deslizar los accesos
        sitesPager.setOnScrollChangeListener { _, sx, _, _, _ ->
            val w = sitesPager.width.coerceAtLeast(1)
            val np = Math.round(sx / w.toFloat()).coerceIn(0, SITES_PANEL_COUNT - 1)
            if (np != sitesPage) {
                sitesPage = np
                renderSitesDots()
                updateSitesTitle()
            }
        }
        // 4.0 U3: página activa del menú al deslizar
        menuPager.setOnScrollChangeListener { _, sx, _, _, _ ->
            val w = menuPager.width.coerceAtLeast(1)
            val np = Math.round(sx / w.toFloat()).coerceIn(0, MENU_PAGE_COUNT - 1)
            if (np != menuPage) {
                menuPage = np
                renderMenuDots()
            }
        }

        readerMode = eng.sp.getBoolean("readerMode", false)
        restoreTabs()
        loadStats()
        updateDetectBtn()
        showHome(true)

        // 4.1: procesar el intent con el que nos lanzaron (desde el sistema
        // u otra app). Cubre ACTION_VIEW (enlace http/https abierto desde
        // Gmail/WhatsApp), ACTION_SEND (URL compartida), PROCESS_TEXT
        // (texto seleccionado → "Abrir en TurboDL"), WEB_SEARCH (búsqueda
        // del asistente) y el extra interno "openUrl" (de MainActivity).
        handleExternalIntent(intent)
    }

    /**
     * Procesa el intent con el que otra app abrió el navegador (4.1).
     *
     * Tipos de intent soportados:
     *  - ACTION_VIEW con data http/https  → carga la URL.
     *  - ACTION_SEND con EXTRA_TEXT        → busca la primera URL del
     *    texto compartido y la carga; si no hay URL, hace búsqueda web.
     *  - ACTION_PROCESS_TEXT               → búsqueda web del texto
     *    seleccionado (igual que "Buscar en Google").
     *  - ACTION_WEB_SEARCH                → búsqueda web del QUERY.
     *  - Extra "openUrl"                   → usado por MainActivity para
     *    "Abrir en navegador".
     *
     * Si el intent no lleva URL ni texto, no hace nada (queda en la
     * página de inicio).
     */
    /** 4.2 (H4): marca de deduplicación de intents. Con launchMode=
     *  singleTask y 5 intent-filters nuevos en 4.1, el sistema puede
     *  entregar onNewIntent inmediatamente después de onCreate (típico
     *  al re-lanzar con CLEAR_TASK), procesando el mismo intent DOS
     *  veces y mezclando load()s. Guardamos la instancia ya tratada y
     *  la saltamos si llega de nuevo. */
    private var lastHandledIntent: android.content.Intent? = null

    private fun handleExternalIntent(intent: android.content.Intent?) {
        if (intent == null) return
        // deduplicar: si ya procesamos este mismo objeto Intent (mismo
        // ref), no hacemos nada (caso onCreate+onNewIntent con el mismo
        // intent re-entregado por singleTask).
        if (intent === lastHandledIntent) return
        lastHandledIntent = intent
        // 1) Extra interno "openUrl" (de MainActivity): prioridad alta,
        //    porque significa que el usuario ya eligió abrir en navegador.
        val openUrl = intent.getStringExtra("openUrl")
        if (!openUrl.isNullOrEmpty()) { load(openUrl); return }
        when (intent.action) {
            android.content.Intent.ACTION_VIEW -> {
                // Enlace http/https abierto por el sistema.
                val data = intent.dataString ?: ""
                if (data.isNotEmpty() && (data.startsWith("http") ||
                            data.startsWith("magnet") || data.contains("://"))) {
                    load(data)
                }
            }
            android.content.Intent.ACTION_SEND -> {
                // URL compartida desde otra app ("Compartir enlace").
                val text = intent.getStringExtra(android.content.Intent.EXTRA_TEXT) ?: ""
                val url = text.split(Regex("\\s+")).firstOrNull {
                    it.startsWith("http") || it.startsWith("magnet") || it.contains("://")
                } ?: ""
                if (url.isNotEmpty()) load(url)
                else if (text.trim().isNotEmpty()) {
                    // No es URL: hacer búsqueda web del texto compartido.
                    load(Profiles.engineUrl(eng.sp, text.trim()))
                }
            }
            android.content.Intent.ACTION_PROCESS_TEXT -> {
                // Texto seleccionado en otra app → "Abrir en TurboDL".
                val text = intent.getStringExtra(android.content.Intent.EXTRA_PROCESS_TEXT) ?: ""
                if (text.trim().isNotEmpty()) {
                    // Si parece URL, navegar; si no, buscar.
                    if (text.contains("://") || text.contains(".")) {
                        load(if (text.contains("://")) text else "https://$text")
                    } else {
                        load(Profiles.engineUrl(eng.sp, text.trim()))
                    }
                }
            }
            android.content.Intent.ACTION_WEB_SEARCH -> {
                // Búsqueda del asistente de Google.
                val query = intent.getStringExtra(SearchManager.QUERY) ?: ""
                if (query.trim().isNotEmpty()) {
                    load(Profiles.engineUrl(eng.sp, query.trim()))
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        // 4.1: si ya estábamos abiertos y otra app lanza ACTION_VIEW/SEND,
        // no creamos una nueva instancia (launchMode=singleTask): el
        // sistema nos entrega el intent aquí. Lo procesamos igual que en
        // onCreate para que el enlace compartido se cargue en una pestaña
        // nueva (mantiene el historial de la pestaña actual).
        setIntent(intent)
        handleExternalIntent(intent)
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
        val l = loadPanel(2)
        if (l.any { it.substringAfter('|') == u }) { toast("Ya está en tus accesos"); return }
        l.add("$name|$u")
        savePanel(2, l)
        toast("Añadido a «Mis sitios»")
    }

    // ---------------- navegacion ----------------

    /**
     * Resuelve lo tecleado en la barra de dirección y carga la URL.
     *
     * Si el texto no tiene esquema ni punto → búsqueda en el motor activo.
     * Si tiene punto pero no esquema → se añade https://.
     * Si ya tiene esquema → se usa literal. BUG 3: tras la navegación,
     * ocultamos sugerencias y bajamos el teclado (las sugerencias no deben
     * tapar la página que se va a cargar).
     */
    private fun go(): Boolean {
        val query = editUrl.text.toString().trim()
        if (query.isEmpty()) return true
        if (!query.contains("://") && !query.contains(".")) {
            addToHistory(query)
            load(Profiles.engineUrl(eng.sp, query))
        } else if (!query.contains("://")) {
            load("https://$query")
        } else {
            load(query)
        }
        hideSuggestions()
        hideKb()
        return true
    }

    /**
     * Carga la URL indicada en la pestaña activa.
     *
     * BUG 3 (informado por el usuario): "una vez realizada una búsqueda las
     * sugerencias de búsquedas siguen saliendo y me ocultan la interfaz del
     * navegador". Causa raíz: `load()` actualizaba el `editUrl` con la URL
     * final (lo que disparaba el TextWatcher -> showSuggestions), pero
     * nunca ocultaba el panel de sugerencias. Como `suppressSuggest`
     * volvía a `false` antes de que el TextWorker corriera, el panel
     * reaparecía tapando el WebView. Lo arreglamos ocultando el panel
     * explícitamente aquí y en `onPageStarted/onPageFinished` (por si la
     * carga se retrasa o cambia el URL por redirección).
     */
    private fun load(url: String) {
        // 4.2 (H4): validar la URL antes de pasársela al WebView. Antes
        // load() no estaba en try/catch y una URL inválida llegada por
        // ACTION_PROCESS_TEXT/WEB_SEARCH (texto con espacios, saltos de
        // línea…) lanzaba IllegalArgumentException y mataba la app.
        // Rechazamos silenciosamente lo que no sea http/https/file/about
        // o magnet (lo demás no tiene sentido abrirlo en WebView).
        if (!isSafeWebUrl(url)) {
            android.util.Log.w("TurboDL.load", "URL rechazada (no navegable): ${url.take(80)}")
            toast("No se pudo abrir ese enlace")
            return
        }
        try {
            homeOverlay.visibility = View.GONE
            hideSheets()
            // BUG 3: ocultar sugerencias ANTES de actualizar el texto del campo
            // para que el TextWatcher no las vuelva a mostrar al reescribir la URL.
            hideSuggestions()
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
            // BUG 3: por si el setText anterior reactivó sugerencias, volvemos
            // a ocultarlas (defensivo ante el orden de llamadas del TextWatcher).
            hideSuggestions()
            renderStrip()
            persistTabs()
        } catch (e: Throwable) {
            android.util.Log.e("TurboDL.load", "Fallo al cargar URL", e)
            toast("No se pudo cargar la página")
        }
    }

    /**
     * ¿Es una URL que el WebView puede cargar sin riesgo de lanzar?
     * 4.2 (H4): acepta http/https/file/about/magnet; rechaza javascript:,
     * data:, content: y todo lo que no empiece por esquema conocido.
     * Acepta también "about:blank" y páginas guardadas ("file://...").
     */
    private fun isSafeWebUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val u = url.trim()
        // magnet NO lo carga el WebView pero la app lo enruta a descargas;
        // se admite aquí y la lógica de detección lo recoge.
        return u.startsWith("http://", true) ||
            u.startsWith("https://", true) ||
            u.startsWith("file://") ||
            u.startsWith("about:") ||
            u.startsWith("magnet:")
    }

    private fun showHome(show: Boolean) {
        homeOverlay.visibility = if (show) View.VISIBLE else View.GONE
        if (cur in tabs.indices) tabs[cur].showHome = show
        if (show) {
            buildSitesPanels()
            hideSuggestions()
            hideSheets()
            hideKb()
        }
        updateShield()
        updateDetectBtn()
    }

    private fun hideKb() {
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(editUrl.windowToken, 0)
        } catch (e: Exception) { }
    }

    private fun showKb() {
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(editUrl, InputMethodManager.SHOW_IMPLICIT)
        } catch (e: Exception) { }
    }

    // ---------------- historial de busqueda (por usuario) ----------------

    private fun history(): MutableList<String> {
        val out = ArrayList<String>()
        try {
            val arr = JSONArray(eng.sp.getString("history_" + Users.active(this), "[]") ?: "[]")
            for (i in 0 until arr.length()) out.add(arr.getString(i))
        } catch (e: Exception) { }
        return out
    }

    private fun addToHistory(q: String) {
        val key = "history_" + Users.active(this)
        val h = history()
        h.remove(q)
        h.add(0, q)
        while (h.size > 30) h.removeAt(h.size - 1)
        val arr = JSONArray()
        h.forEach { arr.put(it) }
        eng.sp.edit().putString(key, arr.toString()).apply()
        setupHistoryBar()
    }

    private fun clearHistory() {
        eng.sp.edit().putString("history_" + Users.active(this), "[]").apply()
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
        // 4.0: abrir páginas guardadas (.mht) desde el panel de guardadas
        ws.allowFileAccess = true
        ws.allowContentAccess = true
        Ui.applyUa(w, eng.sp, desktopMode)
        w.addJavascriptInterface(Bridge(t.uid), "__tdlBridge")
        w.webViewClient = client
        w.webChromeClient = chrome
        w.setDownloadListener { url, _, _, _, _ ->
            // 4.0 U5: toda descarga directa entra en el contador del FAB
            try {
                if (downloadEvents.size > 50) downloadEvents.remove(downloadEvents.keys.first())
                downloadEvents[url] = System.currentTimeMillis()
            } catch (e: Exception) { }
            updateDetectBtn()
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

    private fun tabOf(wv: WebView?): Tab? = synchronized(tabsLock) {
        tabs.firstOrNull { it.wv === wv }
    }

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
        val toTrim: List<Tab>
        synchronized(tabsLock) {
            toTrim = tabs.filterIndexed { i, t -> i != cur && t.wv != null }
                .sortedByDescending { it.lastUse }
                .drop(keep.coerceAtLeast(0))
        }
        for (t in toTrim) {
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
        synchronized(tabsLock) {
            if (tabs.size >= 8) { toast("Máximo 8 pestañas"); return }
        }
        syncActive()
        val nt = Tab(url, "")
        nt.priv = privateTab
        synchronized(tabsLock) { tabs.add(nt) }
        if (switchNow) {
            synchronized(tabsLock) { cur = tabs.size - 1 }
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
        synchronized(tabsLock) {
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
            } else {
                when {
                    index < cur -> cur--
                    wasActive -> cur = index.coerceAtMost(tabs.size - 1)
                    else -> { /* sin cambios */ }
                }
            }
        }
        // fuera del lock: attach hace addView/onResume y puede tocar el WebView
        if (cur in tabs.indices) {
            attach(tabs[cur])
            editUrl.setText(tabs[cur].url)
        }
        renderStrip()
        persistTabs()
        updateTabButton()
        toast("Pestaña cerrada")
    }

    private fun switchTab(index: Int) {
        synchronized(tabsLock) {
            if (index < 0 || index >= tabs.size || index == cur) return
            syncActive()
            grabThumb(tabs[cur])
            cur = index
        }
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
        synchronized(tabsLock) {
            for (i in tabs.indices.reversed()) if (i != keep) {
                purgeTabMedia(tabs[i].uid)
                tabs[i].wv?.let { w ->
                    try { w.removeJavascriptInterface("__tdlBridge"); w.destroy() } catch (e: Exception) { }
                }
                tabs[i].thumb = null
                tabs.removeAt(i)
            }
            cur = 0
        }
        if (tabs.isNotEmpty()) {
            attach(tabs[0])
            editUrl.setText(tabs[0].url)
        }
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
            val snapshot = snapshotTabs()
            val localCur = synchronized(tabsLock) { cur }
            for ((i, t) in snapshot.withIndex()) {
                if (t.priv) { if (i < localCur) shift--; continue }
                val o = org.json.JSONObject()
                o.put("u", t.url)
                o.put("t", t.title)
                o.put("h", t.showHome)
                arr.put(o)
            }
            val normalCur = (localCur + shift).coerceIn(0, if (arr.length() == 0) 0 else arr.length() - 1)
            eng.sp.edit().putString("tabsV21", arr.toString()).putInt("tabsCur", normalCur).apply()
        } catch (e: Exception) { }
    }

    private fun restoreTabs() {
        synchronized(tabsLock) {
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
        }
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
        val rows = eng.db.history(200, Users.active(this))
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

    // ---------------- accesos directos: 3 paneles con swipe (4.0) ----------------
    // Panel 0: sitios comunes · Panel 1: inteligencia artificial · Panel 2: mis sitios
    // Cada panel reordena por arrastre en modo edición; los iconos son el
    // favicon real del sitio (caché en disco) o un monograma por dominio.

    private val PANEL_TITLES = arrayOf("Sitios comunes", "Inteligencia artificial", "Mis sitios")

    private fun panelKey(panel: Int): String = "sitesP${panel}_" + Users.active(this)

    private fun loadPanel(panel: Int): MutableList<String> {
        val saved = eng.sp.getString(panelKey(panel), null)
        if (saved != null) {
            try {
                val arr = JSONArray(saved)
                val out = ArrayList<String>()
                for (i in 0 until arr.length()) out.add(arr.getString(i))
                return out
            } catch (e: Exception) { }
        }
        return when (panel) {
            0 -> COMMON_SITES.toMutableList()
            1 -> AI_SITES.toMutableList()
            else -> loadLegacySites()
        }
    }

    /** Migración 4.0: la lista antigua de accesos pasa a «Mis sitios». */
    private fun loadLegacySites(): MutableList<String> {
        val out = ArrayList<String>()
        try {
            val arr = JSONArray(eng.sp.getString("sites", "[]") ?: "[]")
            for (i in 0 until arr.length()) out.add(arr.getString(i))
        } catch (e: Exception) { }
        val esCatalogo = out == COMMON_SITES.toList() ||
            out.map { it.substringAfter('|') } ==
                DEFAULT_SITES.map { it.substringAfter('|') }
        if (out.isNotEmpty() && !esCatalogo) {
            savePanel(2, out)
        }
        eng.sp.edit().putString("sites", "[]").apply()
        return if (out.isNotEmpty() && !esCatalogo) out else ArrayList()
    }

    private fun savePanel(panel: Int, list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        eng.sp.edit().putString(panelKey(panel), arr.toString()).apply()
    }

    /**
     * Construye los paneles de accesos directos (Comunes / IA / Mis sitios)
     * dentro del `HorizontalScrollView sitesPager`.
     *
     * BUG 2 (informado por el usuario): "no se puede hacer swipe de los
     * accesos rápidos ni estos muestran las páginas". Causa raíz: cada
     * panel se añadía a `sitesPages` (LinearLayout horizontal cuyo propio
     * ancho es `wrap_content`) con `LayoutParams(MATCH_PARENT, …)`. La
     * primera página medía todo el ancho del pager y desplazaba a las dos
     * siguientes fuera de vista (ancho 0). El swipe quedaba "atascado"
     * porque solo existía una página visible.
     *
     * Solución: cada panel se crea con un LayoutParams de ancho fijo igual
     * al ancho del pager (en el primer render el pager aún no se ha
     * medido, así que usamos el ancho de pantalla como fallback y
     * repintamos en un `post {}`). Con esto las tres páginas se ven y se
     * deslizan correctamente entre sí.
     */
    private fun buildSitesPanels() {
        sitesPages.removeAllViews()
        // Ancho fijo de cada panel: si el pager ya se midió, usamos su
        // ancho real; si no, el ancho de pantalla. Se repinta tras medir.
        val panelWidth = sitesPager.width.takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels
        for (p in 0 until SITES_PANEL_COUNT) {
            val page = LinearLayout(this)
            page.orientation = LinearLayout.VERTICAL
            // LayoutParams del panel dentro del LinearLayout horizontal:
            // ancho fijo = ancho del pager (igual que el menú), alto = padre.
            val pageLp = LinearLayout.LayoutParams(panelWidth, ViewGroup.LayoutParams.MATCH_PARENT)
            page.layoutParams = pageLp
            val gv = GridView(this)
            gv.numColumns = SITES_COLUMNS
            gv.horizontalSpacing = dp(6)
            gv.verticalSpacing = dp(6)
            gv.setPadding(dp(8), dp(4), dp(8), dp(8))
            gv.adapter = sitesAdapter(p)
            page.addView(gv, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            sitesPages.addView(page)
            gv.setOnItemClickListener { _, _, pos, _ -> openSite(p, pos) }
            gv.setOnItemLongClickListener { _, v, pos, _ ->
                siteLongPress(p, pos, v); true
            }
            setupSiteDrag(gv, p)
        }
        renderSitesDots()
        updateSitesTitle()
        // Si el pager aún no tenía ancho cuando se construyeron los
        // paneles, repintamos una vez medido para fijar el ancho real.
        if (sitesPager.width == 0) {
            sitesPager.post { buildSitesPanels() }
        }
    }

    private fun sitesAdapter(panel: Int): BaseAdapter {
        val items = loadPanel(panel)
        return object : BaseAdapter() {
            override fun getCount(): Int = items.size
            override fun getItem(p: Int): String = items[p]
            override fun getItemId(p: Int): Long = p.toLong()
            override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
                val v = cv ?: layoutInflater.inflate(R.layout.item_site, parent, false)
                val name = items[p].substringBefore('|')
                val url = items[p].substringAfter('|')
                val host = hostOf(url)
                val icon = v.findViewById<ImageView>(R.id.siteIcon)
                // monograma inmediato + favicon real (memoria/disco/red)
                icon.setImageBitmap(Favicons.cached(this@BrowserActivity, host)
                    ?: Favicons.monogram(host, dp(40)))
                if (host.isNotEmpty()) {
                    Favicons.fetchAsync(this@BrowserActivity, host) { b ->
                        if (b != null) runOnUiThread {
                            try { icon.setImageBitmap(b) } catch (e: Exception) { }
                        }
                    }
                }
                v.findViewById<TextView>(R.id.siteName).text = name
                // 4.0 U8: en modo edición cada celda muestra su ✕
                v.findViewById<View>(R.id.siteDel).visibility =
                    if (editSites) View.VISIBLE else View.GONE
                v.findViewById<View>(R.id.siteDel).setOnClickListener {
                    AlertDialog.Builder(this@BrowserActivity)
                        .setTitle("Quitar «$name»")
                        .setMessage(if (panel == 2) "¿Eliminar este acceso de «Mis sitios»?"
                            else "¿Quitar «$name» del panel? Puedes recuperarlo con «Restaurar».")
                        .setPositiveButton("Quitar") { _, _ -> removeSite(panel, p) }
                        .setNegativeButton("Cancelar", null)
                        .show()
                }
                return v
            }
        }
    }

    private fun openSite(panel: Int, pos: Int) {
        val items = loadPanel(panel)
        if (pos >= items.size) return
        if (editSites) {
            toast("Modo edición: usa ✕ para quitar, o mantén pulsado y arrastra")
            return
        }
        load(items[pos].substringAfter('|'))
    }

    /** Menú contextual de pulsación larga (4.0 U8): en lectura ofrece
     *  abrir/renombrar/reordenar/eliminar; en edición inicia el arrastre. */
    private fun siteLongPress(panel: Int, pos: Int, view: View) {
        val items = loadPanel(panel)
        if (pos >= items.size) return
        if (editSites) {
            startSiteDrag(pos, view)
            return
        }
        val entry = items[pos]
        val name = entry.substringBefore('|')
        val url = entry.substringAfter('|')
        val opts = arrayOf(
            "🗂 Abrir en pestaña nueva",
            "✏ Renombrar",
            "⬆ Mover al inicio",
            "🗑 Eliminar este acceso")
        AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(opts) { _, w ->
                when (w) {
                    0 -> newTab(url, true)
                    1 -> renameSite(panel, pos)
                    2 -> {
                        items.removeAt(pos); items.add(0, entry)
                        savePanel(panel, items); buildSitesPanels()
                    }
                    3 -> AlertDialog.Builder(this)
                        .setTitle("Eliminar «$name»")
                        .setPositiveButton("Eliminar") { _, _ -> removeSite(panel, pos) }
                        .setNegativeButton("Cancelar", null).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun renameSite(panel: Int, pos: Int) {
        val items = loadPanel(panel)
        if (pos >= items.size) return
        val e = android.widget.EditText(this)
        e.setText(items[pos].substringBefore('|'))
        AlertDialog.Builder(this)
            .setTitle("Renombrar acceso")
            .setView(e)
            .setPositiveButton("Guardar") { _, _ ->
                val n = e.text.toString().trim()
                if (n.isNotEmpty()) {
                    items[pos] = n + "|" + items[pos].substringAfter('|')
                    savePanel(panel, items)
                    buildSitesPanels()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun removeSite(panel: Int, pos: Int) {
        val items = loadPanel(panel).toMutableList()
        if (pos < items.size) {
            items.removeAt(pos)
            savePanel(panel, items)
            buildSitesPanels()
            toast("Acceso eliminado")
        }
    }

    /** Arrastre para reordenar (4.0 U8): suelta sobre otra celda y queda ahí. */
    private fun startSiteDrag(pos: Int, view: View) {
        try {
            val data = android.content.ClipData.newPlainText("pos", pos.toString())
            view.startDragAndDrop(data, View.DragShadowBuilder(view), null, 0)
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        } catch (e: Exception) { }
    }

    private fun setupSiteDrag(gv: GridView, panel: Int) {
        gv.setOnDragListener { _, ev ->
            when (ev.action) {
                android.view.DragEvent.ACTION_DRAG_STARTED -> true
                android.view.DragEvent.ACTION_DROP -> {
                    val from = ev.clipData?.getItemAt(0)?.text?.toString()?.toIntOrNull() ?: -1
                    val to = gv.pointToPosition(ev.x.toInt(), ev.y.toInt())
                    if (from >= 0 && to != GridView.INVALID_POSITION && from != to) {
                        val items = loadPanel(panel).toMutableList()
                        if (from < items.size && to < items.size) {
                            val moved = items.removeAt(from)
                            items.add(to, moved)
                            savePanel(panel, items)
                            buildSitesPanels()
                        }
                    }
                    true
                }
                else -> true
            }
        }
    }

    private fun renderSitesDots() {
        sitesDots.removeAllViews()
        for (i in 0 until SITES_PANEL_COUNT) {
            val dot = TextView(this)
            dot.text = "•"
            dot.textSize = 17f
            dot.setPadding(dp(5), 0, dp(5), 0)
            dot.setTextColor(if (i == sitesPage) COLOR_ORANGE else COLOR_DOT_OFF)
            dot.setOnClickListener { jumpSitesPage(i) }
            sitesDots.addView(dot)
        }
    }

    private fun jumpSitesPage(i: Int) {
        sitesPage = i.coerceIn(0, SITES_PANEL_COUNT - 1)
        val w = sitesPager.width.coerceAtLeast(1)
        sitesPager.smoothScrollTo(sitesPage * w, 0)
        renderSitesDots()
        updateSitesTitle()
    }

    private fun updateSitesTitle() {
        txtSitesTitle.text = PANEL_TITLES[sitesPage.coerceIn(0, 2)] +
            if (editSites) "  ·  modo edición" else ""
    }

    private fun addSiteDialog() {
        val lay = LinearLayout(this)
        lay.orientation = LinearLayout.VERTICAL
        lay.setPadding(50, 30, 50, 0)
        val eName = android.widget.EditText(this); eName.hint = "Nombre (YouTube)"
        val eUrl = android.widget.EditText(this); eUrl.hint = "URL (https://…)"
        lay.addView(eName); lay.addView(eUrl)
        AlertDialog.Builder(this)
            .setTitle("Añadir acceso directo a «Mis sitios»")
            .setView(lay)
            .setPositiveButton("Añadir") { _, _ ->
                var u = eUrl.text.toString().trim()
                val n = eName.text.toString().trim()
                if (u.isEmpty()) return@setPositiveButton
                if (!u.contains("://")) u = "https://$u"
                val l = loadPanel(2)
                l.add("${n.ifEmpty { Uri.parse(u).host ?: u }}|$u")
                savePanel(2, l)
                buildSitesPanels()
                toast("Añadido a «Mis sitios» con su icono")
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
                    // BUG 3: al actualizar el campo con la URL final (que
                    // puede cambiar por redirección del servidor), el
                    // TextWatcher reabría el panel de sugerencias. Lo
                    // bloqueamos aquí y nos aseguramos de ocultarlo.
                    suppressSuggest = true
                    editUrl.setText(url)
                    suppressSuggest = false
                    homeOverlay.visibility = View.GONE
                    hideSuggestions()
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
                // historial de paginas (las pestañas privadas no registran;
                // cada usuario tiene su propio historial)
                if (!t.priv) {
                    try { eng.db.addHistory(url, t.title, Users.active(this@BrowserActivity)) } catch (e: Exception) { }
                }
                if (isActive) {
                    // BUG 3: al terminar la carga, el campo se reescribe con
                    // la URL final; ocultamos sugerencias para que no tapen
                    // la página recién cargada.
                    suppressSuggest = true
                    editUrl.setText(url)
                    suppressSuggest = false
                    hideSuggestions()
                }
            }
            // 4.0: credenciales — errores JS a consola + detección de formularios
            // + autorrelleno de lo guardado para este host
            if (!t.priv && url != null && url.startsWith("http")) {
                injectOn(view, ONERROR_JS)
                injectOn(view, CREDS_JS)
                maybeAutofill(view, url)
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
            // 4.0 F6: registro de peticiones de red (activable por sesión)
            if (eng.sp.getBoolean("netlog", false)) {
                com.turbodl.util.DevLog.logNet(
                    request.method ?: "GET", u, "", request.isForMainFrame)
            }
            if (MEDIA_RE.containsMatchIn(u) || DETECT_RE.containsMatchIn(u))
                addDetected(Kit.normalizeSniffUrl(u), "req",
                    tabUid = tabOf(view)?.uid ?: currentTab()?.uid ?: -1)
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

        /** 4.0 F6: consola de JavaScript estilo DevTools (nativa del WebView). */
        override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
            try {
                val m = consoleMessage
                if (m != null) {
                    val nivel = when (m.messageLevel()) {
                        android.webkit.ConsoleMessage.MessageLevel.ERROR -> "error"
                        android.webkit.ConsoleMessage.MessageLevel.WARNING -> "warn"
                        else -> "log"
                    }
                    com.turbodl.util.DevLog.logCon(nivel,
                        "${m.message()} (${m.sourceId()?.substringAfterLast('/') ?: ""}:${m.lineNumber()})")
                }
            } catch (e: Exception) { }
            return super.onConsoleMessage(consoleMessage)
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
            pendingTempWebViews.add(tmp)
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
                try { tmp.destroy() } catch (e2: Exception) { }
                pendingTempWebViews.remove(tmp)
                return false
            }
            // 4.2 (H3): el postDelayed antes no se cancelaba en onDestroy,
            // y newTab corría 500 ms después sin chequear isFinishing →
            // IllegalStateException al añadir un WebView a un host ya
            // detaching. Ahora usamos el uiHandler compartido (se cancela
            // en onDestroy) y guardamos contra isFinishing/isDestroyed.
            uiHandler.postDelayed({
                val u = href[0] ?: tmp.url
                try { tmp.destroy() } catch (e: Exception) { }
                pendingTempWebViews.remove(tmp)
                if (isFinishing || isDestroyed) return@postDelayed
                if (!u.isNullOrEmpty()) newTab(u, true)
                else if (!isFinishing) toast("El enlace no se pudo abrir en nueva pestaña")
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
        if (currentTab() === t) runOnUiThread { updateShield() }
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
            val arr = JSONArray(eng.sp.getString("bookmarks_" + Users.active(this),
                eng.sp.getString("bookmarks", "[]") ?: "[]") ?: "[]")
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
        eng.sp.edit().putString("bookmarks_" + Users.active(this), arr.toString()).apply()
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
            eng.sp.edit().putString("bookmarks_" + Users.active(this), arr.toString()).apply()
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
     * Contador del botón de medios y del FAB flotante (4.0 U5): SOLO la
     * pestaña activa. Cada pestaña tiene su propia lista de capturas.
     */
    private fun updateDetectBtn() {
        val uid = currentTab()?.uid ?: -1
        val n = synchronized(detected) { detected.values.count { it.tabUid == uid } }
        btnVideos.text = if (n > 0) "⬇ Detectados: $n" else "⬇ Detectados"
        // FAB flotante: medios + descargas directas, en tiempo real
        val total = n + downloadEvents.size
        fab.text = "▶ $total"
        fab.visibility =
            if (total > 0 && !homeOverlay.isVisible()) View.VISIBLE else View.GONE
    }

    /** El FAB abre un selector: medios de la página o descargas directas. */
    private fun showDetectChooser() {
        val uid = tabs.getOrNull(cur)?.uid ?: -1
        val n = synchronized(detected) { detected.values.count { it.tabUid == uid } }
        val d = downloadEvents.size
        val opts = ArrayList<String>()
        if (n > 0) opts.add("🎬 Medios detectados ($n)")
        if (d > 0) opts.add("📥 Descargas directas ($d)")
        if (opts.isEmpty()) {
            toast("Nada detectado aún en esta pestaña")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Elementos detectados en esta página")
            .setItems(opts.toTypedArray()) { _, w ->
                when {
                    opts[w].startsWith("🎬") -> showDetected()
                    else -> showDownloadEvents()
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /** Lista de descargas directas capturadas por el DownloadListener. */
    private fun showDownloadEvents() {
        val urls = ArrayList(downloadEvents.keys)
        if (urls.isEmpty()) { toast("No hay descargas directas capturadas"); return }
        val labels = urls.map { "${MediaProbe.hostOf(it)} — ${it.takeLast(60)}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Descargas directas (${urls.size})")
            .setItems(labels) { dlg, w ->
                val url = urls[w]
                AlertDialog.Builder(this)
                    .setTitle("Descargar este archivo")
                    .setMessage(url.takeLast(80))
                    .setPositiveButton("⬇ Ahora") { _, _ ->
                        downloadEvents.remove(url)
                        updateDetectBtn()
                        startDownloadRaw(url, null, false)
                        dlg.dismiss()
                    }
                    .setNegativeButton("En cola") { _, _ ->
                        downloadEvents.remove(url)
                        updateDetectBtn()
                        startDownloadRaw(url, null, true)
                        dlg.dismiss()
                    }
                    .setNeutralButton("Cerrar", null)
                    .show()
            }
            .setNeutralButton("Limpiar lista") { _, _ ->
                downloadEvents.clear()
                updateDetectBtn()
            }
            .setNegativeButton("Cerrar", null)
            .show()
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

    /** Menú inferior en PÁGINAS DESLIZABLES (4.0): swipe izquierda/derecha
     *  cambia de página; los puntos reflejan y permiten saltar. 3 páginas
     *  × 10 acciones, todas reales. */
    private fun showMenuSheet() {
        hideSheets()
        renderMenuPages()
        menuSheet.visibility = View.VISIBLE
        // BUG 1: al abrir el menú la primera vez, `menuPager.width` todavía
        // es 0 (no se ha medido), así que el GridLayout se construye con el
        // ancho de fallback. Tras medir, repintamos y posicionamos el
        // scroll en la página activa.
        menuPager.post {
            if (menuPager.width > 0) {
                renderMenuPages()
                menuPager.scrollTo(menuPage.coerceIn(0, MENU_PAGE_COUNT - 1) * menuPager.width, 0)
            }
        }
    }

    /**
     * Construye (y cachea en el container horizontal `menuPages`) una página
     * GridLayout de 5 columnas × 2 filas. BUG 1: antes cada página se añadía
     * a un LinearLayout horizontal con `MATCH_PARENT` en width, pero el
     * LinearLayout tiene `wrap_content` en su propio ancho — el resultado
     * es que la primera página medía el ancho del padre y las dos siguientes
     * se "aplastaban" a 0; al deslizar, no se veían. Aquí forzamos que cada
     * GridLayout mida exactamente el ancho de pantalla del paginador usando
     * un LayoutParams de ancho fijo igual a `menuPager.width`. (En el primer
     * render ese ancho puede ser 0; se corrige con un `post {}` que repinta
     * cuando ya se ha medido.)
     */
    private fun renderMenuPages() {
        menuPages.removeAllViews()
        // Ancho de cada página = ancho del paginador (0 la primera vez; el
        // post() de showMenuSheet repinta tras medir).
        val pageWidth = menuPager.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        for (p in 0 until MENU_PAGE_COUNT) {
            val page = android.widget.GridLayout(this)
            page.columnCount = MENU_COLUMNS
            page.rowCount = MENU_ROWS
            page.setPadding(dp(6), dp(4), dp(6), dp(4))
            // LayoutParams del GridLayout dentro del LinearLayout horizontal:
            // ancho fijo = ancho del paginador, alto = MATCH_PARENT.
            val gridLp = LinearLayout.LayoutParams(pageWidth, ViewGroup.LayoutParams.MATCH_PARENT)
            page.layoutParams = gridLp
            val cells = menuCells(p)
            // Posiciona cada celda en su (col, row) para que el peso 1f del
            // GridLayout reparta uniformemente el ancho y el alto.
            for ((index, cell) in cells.withIndex()) {
                val col = index % MENU_COLUMNS
                val row = index / MENU_COLUMNS
                page.addView(menuCell(cell, col, row))
            }
            menuPages.addView(page)
        }
        renderMenuDots()
    }

    private fun renderMenuDots() {
        menuDots.removeAllViews()
        for (i in 0 until MENU_PAGE_COUNT) {
            val dot = TextView(this)
            dot.text = "•"
            dot.textSize = 18f
            dot.setPadding(dp(6), 0, dp(6), 0)
            dot.setTextColor(if (i == menuPage) COLOR_ORANGE else COLOR_DOT_OFF)
            dot.setOnClickListener { jumpMenuPage(i) }
            menuDots.addView(dot)
        }
    }

    private fun jumpMenuPage(i: Int) {
        menuPage = i.coerceIn(0, MENU_PAGE_COUNT - 1)
        val w = menuPager.width.coerceAtLeast(1)
        menuPager.smoothScrollTo(menuPage * w, 0)
        // BUG 1: antes no se repintaban los puntos al saltar; el indicador
        // se quedaba en la página anterior hasta que el usuario soltaba el
        // gesto. Repintamos en cuanto cambia el estado.
        renderMenuDots()
    }

    /** Las tres páginas del menú: navegación, herramientas y privacidad.
     *  Los elementos con estado activo se pintan en color de acento. */
    private fun menuCells(page: Int): List<MenuCell> {
        val t = tabs.getOrNull(cur)
        val pageUrl = t?.url ?: ""
        return when (page) {
            0 -> listOf(
                MenuCell("🌙", "Modo nocturno", active = Ui.isDark(this)) { toggleDarkTheme() },
                MenuCell("⭐", "Marcadores") { hideSheets(); showBookmarks() },
                MenuCell("🕘", "Historial") { hideSheets(); showPageHistory() },
                MenuCell("⬇", "Detectados") { hideSheets(); showDetected() },
                MenuCell("🕵", "Incógnito") { newTab("", privateTab = true); hideSheets() },
                MenuCell("↗", "Compartir") { hideSheets(); sharePage() },
                MenuCell("☆+", "Añadir marcador") { addBookmark(pageUrl, web.title ?: ""); hideSheets() },
                MenuCell("🖥", "Escritorio", active = desktopMode) { toggleDesktop(); renderMenuPages() },
                MenuCell("🧪", "Diagnóstico") { hideSheets(); showDiagnostico() },
                MenuCell("⚙", "Ajustes") { hideSheets(); startActivity(android.content.Intent(this, SettingsActivity::class.java)) }
            )
            1 -> listOf(
                MenuCell("🔍", "Buscar pág.") { hideSheets(); findInPage() },
                MenuCell("🗒", "Notas") { hideSheets(); showNotes() },
                MenuCell("📤", "Exportar") { hideSheets(); showExportDialog() },
                MenuCell("📦", "Guardar offline") { hideSheets(); saveCurrentOffline() },
                MenuCell("🗄", "Págs. guardadas") { hideSheets(); showSavedPages() },
                MenuCell("🔬", "Ver código") { hideSheets(); showSource() },
                MenuCell("⌨", "Consola JS") { hideSheets(); showJsConsole() },
                MenuCell("📡", "Registro red") { hideSheets(); showNetLog() },
                MenuCell("🖼", "Ventana flot.") { hideSheets(); enterPip() },
                MenuCell("🔎", "Escanear medios") { hideSheets(); inject(DEEP_SCAN_JS); toast("Escaneando medios de la página…") }
            )
            else -> listOf(
                MenuCell("🛡", "Bloqueador", active = eng.sp.getBoolean("adblock", true)) {
                    val nv = !eng.sp.getBoolean("adblock", true)
                    eng.sp.edit().putBoolean("adblock", nv).apply()
                    toast(if (nv) "Bloqueador activado" else "Bloqueador apagado")
                    updateShield()
                    tabs.forEach { it.wv?.reload() }
                    renderMenuPages()
                },
                MenuCell("🚫", "Eximir sitio") { hideSheets(); toggleSiteExempt(pageUrl) },
                MenuCell("🍪", "Limpiar datos") { hideSheets(); clearPrivacy() },
                MenuCell("🔑", "Credenciales") { hideSheets(); showCredsDialog() },
                MenuCell("👥", "Usuarios") { hideSheets(); showUsersDialog() },
                MenuCell("👁", "Sandbox") { hideSheets(); showSandboxViewer() },
                MenuCell("📖", "Lector", active = readerMode) { toggleReader(); renderMenuPages() },
                MenuCell("🧹", "Borrar historial") { clearHistory(); toast("Historial de búsqueda borrado"); renderMenuPages() },
                MenuCell("👤", "Perfil nav.") { hideSheets(); showProfileMenu(menuSheet) },
                MenuCell("📌", "Añadir acceso") { addCurrentAsSite(); renderMenuPages() },
                MenuCell("🚪", "Salir") { finishAffinity() }
            )
        }
    }

    /**
     * Construye una celda del menú inferior (icono + etiqueta).
     *
     * BUG 1 (informado por el usuario): "el menú del navegador no se muestra
     * correctamente, no salen todos los botones ni se puede cambiar entre
     * submenús". Causa raíz: la celda se añadía al GridLayout con
     * LayoutParams WRAP_CONTENT y el `columnSpec` con peso 1f se fijaba
     * DESPUÉS de addView, por lo que el GridLayout no lo aplicaba y las
     * celdas se amontonaban a la izquierda. La segunda fila (filas 6..10)
     * quedaba recortada porque el `rowCount=2` no reservaba espacio
     * suficiente. Además el contenedor `menuPages` era un LinearLayout
     * horizontal con cada página a `MATCH_PARENT` — pero al medirse, la
     * primera página se "comía" el ancho y la segunda y tercera no se
     * mostraban al deslizar.
     *
     * Solución: el GridLayout se configura con `columnCount=5` y se fija
     * explícitamente el LayoutParams a la celda ANTES de añadirla, con
     * `columnSpec = spec(col, 1f)` y `width = 0` para que el peso reparta
     * el ancho entre las 5 columnas a partes iguales; el alto se da con
     * `rowSpec = spec(row, 1f)` y `height = 0` para que las 2 filas se
     * repartan el alto del contenedor uniformemente. Cada página del
     * menú se mide al ancho de pantalla (`MeasureSpec` del padre), no al
     * del LinearLayout horizontal.
     */
    private fun menuCell(c: MenuCell, col: Int, row: Int): View {
        val cell = LinearLayout(this)
        cell.orientation = LinearLayout.VERTICAL
        cell.gravity = Gravity.CENTER
        cell.setPadding(dp(4), dp(10), dp(4), dp(10))
        cell.background = getDrawable(android.R.drawable.list_selector_background)
        // Icono emoji de la celda.
        val iconView = TextView(this)
        iconView.text = c.icon
        iconView.textSize = 22f
        iconView.gravity = Gravity.CENTER
        iconView.setTextColor(if (c.active) COLOR_ACCENT else COLOR_TEXT_PRIMARY)
        cell.addView(iconView)
        // Etiqueta bajo el icono (hasta 2 líneas para no recortar "Añadir marcador").
        val labelView = TextView(this)
        labelView.text = c.label
        labelView.textSize = 11f
        labelView.gravity = Gravity.CENTER
        labelView.maxLines = 2
        labelView.setPadding(dp(2), dp(4), dp(2), 0)
        labelView.setTextColor(if (c.active) COLOR_ACCENT else COLOR_TEXT_SECONDARY)
        cell.addView(labelView)
        // LayoutParams: peso 1f en X e Y, ancho/alto 0 para que el GridLayout
        // reparta el espacio. Se asignan ANTES de addView para que tengan
        // efecto (GridLayout lee los LayoutParams al medir, no al layout).
        val lp = android.widget.GridLayout.LayoutParams()
        lp.width = 0
        lp.height = 0
        lp.columnSpec = android.widget.GridLayout.spec(col, 1f)
        lp.rowSpec = android.widget.GridLayout.spec(row, 1f)
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

    /**
     * Menú contextual de la barra de dirección (BUG 4): pulsación larga
     * sobre el campo URL. Mantiene la posibilidad de compartir y copiar la
     * URL actual aunque el toque simple haya borrado el texto para escribir
     * una nueva. Las acciones son:
     *   · Compartir enlace  → ACTION_SEND con la URL de la pestaña activa.
     *   · Copiar URL        → copia al portapapeles (ClipData).
     *   · Pegar y abrir      → si hay una URL en el portapapeles, la pega
     *                          en el campo y la carga.
     *   · Editar URL        → pone el cursor al final sin seleccionar todo,
     *                          para corregir un carácter sin borrar el resto.
     */
    private fun showUrlContextMenu() {
        val currentUrl = tabs.getOrNull(cur)?.url ?: ""
        if (!currentUrl.startsWith("http")) {
            toast("Abre una página primero para copiarla o compartirla")
            return
        }
        val options = arrayOf(
            "🔗 Compartir enlace",
            "📋 Copiar URL",
            "📂 Pegar y abrir",
            "✏ Editar URL (sin borrar)")
        AlertDialog.Builder(this)
            .setTitle(MediaProbe.hostOf(currentUrl))
            .setItems(options) { _, which ->
                when (which) {
                    // Compartir enlace: usa el chooser del sistema.
                    0 -> sharePage()
                    // Copiar URL: pega en el portapapeles del sistema.
                    1 -> {
                        try {
                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                            clipboard.setPrimaryClip(
                                android.content.ClipData.newPlainText("url", currentUrl))
                            toast("URL copiada")
                        } catch (e: Exception) {
                            toast("No se pudo copiar: ${e.message}")
                        }
                    }
                    // Pegar y abrir: lee la URL del portapapeles y la carga.
                    2 -> {
                        try {
                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                            val clip = clipboard.primaryClip
                            val text = clip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
                                ?: ""
                            if (text.isNotEmpty() && (text.startsWith("http") ||
                                        text.contains("://") || text.contains("."))) {
                                load(if (text.contains("://")) text else "https://$text")
                            } else {
                                toast("El portapapeles no tiene una URL")
                            }
                        } catch (e: Exception) {
                            toast("No se pudo leer el portapapeles")
                        }
                    }
                    // Editar URL: sitúa el cursor al final sin seleccionar
                    // todo, para corregir un carácter concreto.
                    3 -> {
                        editUrl.requestFocus()
                        editUrl.setSelection(editUrl.text.length)
                        showKb()
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
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

    private class Sugg(val icon: String, val title: String, val url: String)

    /**
     * Panel de sugerencias bajo la barra superior (4.0 U10): LISTA
     * DESPLAZABLE hasta el último resultado — URL directa, marcadores,
     * historial de páginas y búsquedas previas, filtrados por lo tecleado.
     * Altura con tope (420dp) y scroll interno; nunca más sin scroll.
     *
     * BUG 3 (informado por el usuario): tras una búsqueda, el campo se
     * reescribía con la URL de destino y el TextWatcher reabría este
     * panel, que tapaba el WebView. Aquí además filtramos el caso en el
     * que el texto actual coincide EXACTAMENTE con la URL de la pestaña
     * activa (carga en curso o ya cargada): en ese caso no hay nada que
     * sugerir, porque el usuario ya está ahí.
     */
    private fun showSuggestions(q: String) {
        if (suppressSuggest || q.isBlank()) { hideSuggestions(); return }
        // BUG 3: si el campo coincide con la URL activa, no mostramos
        // sugerencias (ya se está cargando o ya se cargó esa página).
        val activeTabUrl = tabs.getOrNull(cur)?.url ?: ""
        if (activeTabUrl.isNotEmpty() && q.trim() == activeTabUrl) {
            hideSuggestions()
            return
        }
        val low = q.lowercase(Locale.US)
        val rows = ArrayList<Sugg>()
        val uq = q.trim()
        if (uq.contains(".") && !uq.contains(" ")) {
            rows.add(Sugg("↗", "Ir a $uq",
                if (uq.startsWith("http")) uq else "https://$uq"))
        }
        try {
            val arr = JSONArray(eng.sp.getString("bookmarks_" + Users.active(this), "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                if (rows.size > 60) break
                val o = arr.optJSONObject(i) ?: continue
                val t = o.optString("t", "")
                val u = o.optString("u", "")
                if (t.lowercase(Locale.US).contains(low) ||
                    u.lowercase(Locale.US).contains(low))
                    rows.add(Sugg("☆", t.ifEmpty { MediaProbe.hostOf(u) }, u))
            }
        } catch (e: Exception) { }
        try {
            for (r in eng.db.history(120, Users.active(this))) {
                if (rows.size > 80) break
                if (r.url.startsWith("http") &&
                    ((r.title.ifEmpty { r.url }).lowercase(Locale.US).contains(low) ||
                        r.url.lowercase(Locale.US).contains(low)))
                    rows.add(Sugg("🕘", (r.title.ifEmpty { r.url }).take(60), r.url))
            }
        } catch (e: Exception) { }
        try {
            for (h in history()) {
                if (rows.size > 120) break
                if (h.lowercase(Locale.US).contains(low))
                    rows.add(Sugg("🔎", h, Profiles.engineUrl(eng.sp, h)))
            }
        } catch (e: Exception) { }
        if (rows.isEmpty()) { hideSuggestions(); return }
        val ad = object : BaseAdapter() {
            override fun getCount(): Int = rows.size
            override fun getItem(p: Int): Sugg = rows[p]
            override fun getItemId(p: Int): Long = p.toLong()
            override fun getView(p: Int, cv: View?, parent: ViewGroup?): View =
                suggestionRow(getItem(p))
        }
        suggestionsList.adapter = ad
        // altura: una fila por resultado con tope; la lista hace scroll
        val lp = suggestionsList.layoutParams
        lp.height = Math.min(dp(52) * rows.size + dp(12), dp(420))
        suggestionsList.layoutParams = lp
        suggestionsHost.visibility = View.VISIBLE
    }

    private fun suggestionRow(s: Sugg): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(14), dp(9), dp(6), dp(9))
        row.background = getDrawable(android.R.drawable.list_selector_background)
        val ic = TextView(this)
        ic.text = s.icon
        ic.textSize = 15f
        ic.setPadding(dp(4), 0, dp(10), 0)
        row.addView(ic)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val t1 = TextView(this)
        t1.textSize = 13.5f
        t1.setSingleLine(true)
        t1.ellipsize = android.text.TextUtils.TruncateAt.END
        t1.text = s.title
        t1.setTextColor(0xFF212121.toInt())
        col.addView(t1)
        val t2 = TextView(this)
        t2.textSize = 11f
        t2.setSingleLine(true)
        t2.ellipsize = android.text.TextUtils.TruncateAt.END
        t2.text = s.url
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
            editUrl.setText(s.url)
            suppressSuggest = false
            newTab(s.url, true)
            hideSuggestions()
            hideKb()
        }
        row.addView(go)
        row.setOnClickListener {
            suppressSuggest = true
            editUrl.setText(s.url)
            suppressSuggest = false
            load(s.url)
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

    // ================= nuevas funciones 4.0 =================
    // Notas · páginas guardadas · exportación · herramientas de desarrollador
    // · credenciales · usuarios sandbox

    private fun clipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("turbodl", text))
    }

    private fun tsName(): String =
        java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US).format(java.util.Date())

    private fun sanitizeName(t: String): String =
        t.replace(Regex("[^A-Za-z0-9ÁÉÍÓÚáéíóúñÑ _-]"), "").trim().take(50).ifEmpty { "pagina" }

    private fun getDomHtml(cb: (String) -> Unit) {
        try {
            web.evaluateJavascript("document.documentElement.outerHTML") { r ->
                val s = r ?: "null"
                val html = try {
                    if (s.startsWith("\"")) org.json.JSONTokener(s).nextValue() as String else s
                } catch (e: Exception) { s }
                runOnUiThread { cb(html) }
            }
        } catch (e: Exception) { cb("") }
    }

    // ---------------- notas (F5) ----------------

    /** Notas con CRUD completo (4.0): prompts y textos que se copian a los
     *  chatbots; copiar al portapapeles con un toque desde el navegador.
     *  4.2: arregla los problemas reportados por el usuario —
     *   · La lista NO se refrescaba tras crear/editar una nota (solo tras
     *     eliminar), así que el usuario creaba una nota y no la veía. Ahora
     *     el editor recarga la lista al cerrarse y se reabre la vista.
     *   · La búsqueda existía en la BD (NotesDb.notes(query)) pero no se
     *     exponía en la UI. Ahora hay una caja de búsqueda en la cabecera
     *     que filtra por título o cuerpo en tiempo real.
     *   · editNote usaba ndb.notes().firstOrNull { id==... } (leía toda la
     *     tabla); ahora usa ndb.getNote(id).
     *   · Eliminar pide confirmación.
     */
    private fun showNotes(initialQuery: String = "") {
        val ndb = com.turbodl.db.NotesDb.get(this)
        val rows = ndb.notes(initialQuery)
        val lay = LinearLayout(this)
        lay.orientation = LinearLayout.VERTICAL
        // 4.2: caja de búsqueda
        val searchBox = android.widget.EditText(this)
        searchBox.hint = "🔍 Buscar en notas…"
        searchBox.setText(initialQuery)
        searchBox.setSingleLine(true)
        searchBox.setPadding(40, 24, 40, 24)
        lay.addView(searchBox, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))
        val lv = ListView(this)
        val ad = object : BaseAdapter() {
            override fun getCount(): Int = rows.size
            override fun getItem(p: Int): com.turbodl.db.NotesDb.Note = rows[p]
            override fun getItemId(p: Int): Long = rows[p].id
            override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
                val v = TextView(this@BrowserActivity)
                val n = getItem(p)
                v.setPadding(40, 26, 40, 26)
                v.textSize = 13f
                v.text = n.preview() + "\n" +
                    java.text.DateFormat.getDateTimeInstance(
                        java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                        .format(java.util.Date(n.time))
                v.background = getDrawable(android.R.drawable.list_selector_background)
                return v
            }
        }
        lv.adapter = ad
        lay.addView(lv, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 620))
        if (rows.isEmpty()) {
            val empty = TextView(this)
            empty.text = if (initialQuery.isBlank())
                "Sin notas todavía. Crea una y cópiala al portapapeles con un toque."
            else "Ninguna nota coincide con «$initialQuery»."
            empty.setPadding(40, 30, 40, 30)
            lay.addView(empty, 1)
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("🗒 Notas (${rows.size})")
            .setView(lay)
            .setNegativeButton("Cerrar", null)
            .create()
        val btnNew = Button(this)
        btnNew.text = "＋ Nueva nota"
        lay.addView(btnNew)
        btnNew.setOnClickListener { dlg.dismiss(); editNote(-1L) { showNotes(searchBox.text.toString()) } }
        // 4.2: filtrado en vivo por título/cuerpo
        searchBox.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { }
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { }
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s?.toString() ?: ""
                // mantener la vista del diálogo pero reconsultar la BD
                rows.clear()
                rows.addAll(ndb.notes(q))
                // actualizar el título y el placeholder
                dlg.setTitle("🗒 Notas (${rows.size})")
                ad.notifyDataSetChanged()
                // mostrar/ocultar mensaje de vacío según corresponda
                val emptyView = if (lay.childCount >= 2) lay.getChildAt(1) as? TextView else null
                if (rows.isEmpty()) {
                    if (emptyView == null || emptyView.text?.startsWith("Sin notas") == true ||
                        emptyView.text?.startsWith("Ninguna nota") == true) {
                        if (emptyView == null) {
                            val e = TextView(this@BrowserActivity)
                            e.setPadding(40, 30, 40, 30)
                            e.text = if (q.isBlank())
                                "Sin notas todavía. Crea una y cópiala al portapapeles con un toque."
                            else "Ninguna nota coincide con «$q»."
                            lay.addView(e, 1)
                        } else {
                            emptyView.text = if (q.isBlank())
                                "Sin notas todavía. Crea una y cópiala al portapapeles con un toque."
                            else "Ninguna nota coincide con «$q»."
                        }
                    }
                } else {
                    if (emptyView != null && (emptyView.text?.startsWith("Sin notas") == true ||
                            emptyView.text?.startsWith("Ninguna nota") == true)) {
                        lay.removeView(emptyView)
                    }
                }
            }
        })
        lv.setOnItemClickListener { _, _, p, _ ->
            val n = rows[p]
            val acts = arrayOf("✏ Editar", "📋 Copiar al portapapeles", "↗ Compartir", "🗑 Eliminar")
            AlertDialog.Builder(this)
                .setTitle(n.title.ifEmpty { "(sin título)" })
                .setItems(acts) { _, w ->
                    when (w) {
                        0 -> { dlg.dismiss(); editNote(n.id) { showNotes(searchBox.text.toString()) } }
                        1 -> { clipboard(n.body); toast("Copiado: pégalo donde quieras") }
                        2 -> try {
                            val i = android.content.Intent(android.content.Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(android.content.Intent.EXTRA_TEXT, n.body)
                            startActivity(android.content.Intent.createChooser(i, "Compartir nota"))
                        } catch (e: Exception) { }
                        3 -> {
                            // 4.2: pedir confirmación antes de borrar
                            AlertDialog.Builder(this)
                                .setTitle("Eliminar nota")
                                .setMessage("¿Borrar «${n.title.ifEmpty { "(sin título)" }}»?")
                                .setPositiveButton("Eliminar") { _, _ ->
                                    ndb.deleteNote(n.id)
                                    rows.removeAt(p)
                                    ad.notifyDataSetChanged()
                                    dlg.setTitle("🗒 Notas (${rows.size})")
                                    toast("Nota eliminada")
                                }
                                .setNegativeButton("Cancelar", null)
                                .show()
                        }
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
        dlg.show()
    }

    /**
     * Editor de nota: creación y edición (misma ventana).
     * 4.2: añadido callback `onSaved` para que la vista de lista se
     * refresque al cerrar el editor (antes la lista NO se refrescaba
     * tras crear/editar — el usuario creaba una nota y no la veía).
     * Usa ndb.getNote(id) en vez de releer toda la tabla.
     */
    private fun editNote(id: Long, onSaved: () -> Unit = {}) {
        val ndb = com.turbodl.db.NotesDb.get(this)
        val existing = if (id > 0) ndb.getNote(id) else null
        val lay = LinearLayout(this)
        lay.orientation = LinearLayout.VERTICAL
        lay.setPadding(50, 30, 50, 0)
        val et = android.widget.EditText(this)
        et.hint = "Título"
        et.setText(existing?.title ?: "")
        val eb = android.widget.EditText(this)
        eb.hint = "Escribe aquí (prompt, texto…)"
        eb.setMinLines(6)
        eb.gravity = Gravity.TOP
        eb.setText(existing?.body ?: "")
        lay.addView(et); lay.addView(eb)
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Nueva nota" else "Editar nota")
            .setView(lay)
            .setPositiveButton("Guardar") { _, _ ->
                val t = et.text.toString().trim()
                val b = eb.text.toString()
                if (b.isBlank() && t.isBlank()) return@setPositiveButton
                if (existing == null)
                    ndb.addNote(t, b, tabs.getOrNull(cur)?.url ?: "")
                else ndb.updateNote(existing.id, t, b)
                toast("Nota guardada")
                onSaved()
            }
            .setNeutralButton("📋 Copiar todo") { _, _ ->
                clipboard(eb.text.toString())
                toast("Copiado al portapapeles")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------------- páginas guardadas offline (F4) ----------------

    private fun saveCurrentOffline() {
        val t = tabs.getOrNull(cur) ?: return
        if (!t.url.startsWith("http")) { toast("Abre una página web primero"); return }
        val title = (web.title ?: "").ifEmpty { MediaProbe.hostOf(t.url) }
        toast("Guardando la página con CSS y JS…")
        Exporter.saveOffline(web, t.url, title) { p ->
            toast(if (p.isEmpty())
                "No se pudo guardar (prueba de nuevo tras terminar la carga)"
            else "Guardada offline: ${java.io.File(p).name}")
        }
    }

    private fun showSavedPages() {
        val ndb = com.turbodl.db.NotesDb.get(this)
        val rows = ndb.saved()
        val lay = LinearLayout(this)
        lay.orientation = LinearLayout.VERTICAL
        val lv = ListView(this)
        val ad = object : BaseAdapter() {
            override fun getCount(): Int = rows.size
            override fun getItem(p: Int): com.turbodl.db.NotesDb.SavedPage = rows[p]
            override fun getItemId(p: Int): Long = rows[p].id
            override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
                val v = TextView(this@BrowserActivity)
                val s = getItem(p)
                v.setPadding(40, 26, 40, 26)
                v.textSize = 13f
                v.text = "${s.title.take(60)}\n${MediaProbe.hostOf(s.url)} · " +
                    Kit.fmtBytes(s.size) + " · " +
                    java.text.DateFormat.getDateTimeInstance(
                        java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                        .format(java.util.Date(s.time))
                v.background = getDrawable(android.R.drawable.list_selector_background)
                return v
            }
        }
        lv.adapter = ad
        lay.addView(lv, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 620))
        if (rows.isEmpty()) {
            val empty = TextView(this)
            empty.text = "Sin páginas guardadas. Usa «Guardar offline» en el menú (guarda CSS y JS: se ve igual que en línea, sin conexión)."
            empty.setPadding(40, 30, 40, 30)
            lay.addView(empty, 0)
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("📦 Páginas guardadas (${rows.size})")
            .setView(lay)
            .setNegativeButton("Cerrar", null)
            .create()
        lv.setOnItemClickListener { _, _, p, _ ->
            dlg.dismiss()
            hideSheets()
            load("file://" + rows[p].path)
        }
        lv.setOnItemLongClickListener { _, _, p, _ ->
            val s = rows[p]
            AlertDialog.Builder(this)
                .setTitle("Eliminar «${s.title.take(30)}»")
                .setPositiveButton("Eliminar") { _, _ ->
                    try { java.io.File(s.path).delete() } catch (e: Exception) { }
                    ndb.deleteSaved(s.id)
                    rows.removeAt(p)
                    ad.notifyDataSetChanged()
                    toast("Página guardada eliminada")
                }
                .setNegativeButton("Cancelar", null)
                .show()
            true
        }
        dlg.show()
    }

    // ---------------- exportación (F3) ----------------

    private fun showExportDialog() {
        val t = tabs.getOrNull(cur) ?: return
        if (!t.url.startsWith("http")) { toast("Abre una página web primero"); return }
        val title = (web.title ?: "").ifEmpty { MediaProbe.hostOf(t.url) }
        val base = sanitizeName(title)
        val opts = arrayOf(
            "📄 PDF (sistema: documento completo, abre el diálogo de imprimir)",
            "🖨 PDF (captura rápida del render actual)",
            "📝 Markdown (.md, listo para LLMs)",
            "📄 HTML completo (.html)",
            "📃 Texto plano (.txt)",
            "🖼 Imagen PNG (captura completa)",
            "📦 Guardar offline (.mht: CSS+JS incluidos, sin conexión)")
        AlertDialog.Builder(this)
            .setTitle("Exportar «${title.take(28)}»")
            .setItems(opts) { _, w ->
                when (w) {
                    0 -> Exporter.exportPdfSystem(this, web, title)
                    1 -> Exporter.exportPdfCapture(this, web, title) { p ->
                        toast(if (p.isEmpty()) "No se pudo generar el PDF" else "PDF en $p") }
                    2 -> getDomHtml { html ->
                        Exporter.exportMarkdown(html, t.url, title) { md ->
                            runOnUiThread {
                                if (md.isEmpty()) toast("No se pudo convertir a Markdown")
                                else {
                                    val p = Exporter.saveFile(this,
                                        "${base}_${tsName()}.md", "text/markdown",
                                        md.toByteArray(Charsets.UTF_8))
                                    toast(if (p == null) "Error al guardar" else "Markdown en $p")
                                }
                            }
                        } }
                    3 -> getDomHtml { html ->
                        val p = Exporter.saveFile(this,
                            "${base}_${tsName()}.html", "text/html",
                            html.toByteArray(Charsets.UTF_8))
                        toast(if (p == null) "Error al guardar" else "HTML en $p") }
                    4 -> try {
                        web.evaluateJavascript("document.body.innerText") { r ->
                            val txt = try {
                                if (r != null && r.startsWith("\""))
                                    org.json.JSONTokener(r).nextValue() as String else (r ?: "")
                            } catch (e: Exception) { r ?: "" }
                            val p = Exporter.saveFile(this,
                                "${base}_${tsName()}.txt", "text/plain",
                                txt.toByteArray(Charsets.UTF_8))
                            toast(if (p == null) "Error al guardar" else "Texto en $p")
                        }
                    } catch (e: Exception) { }
                    5 -> Exporter.exportPng(this, web, title) { p ->
                        toast(if (p.isEmpty()) "No se pudo capturar" else "PNG en $p") }
                    6 -> saveCurrentOffline()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------------- herramientas de desarrollador (F6) ----------------

    private fun showSource() {
        getDomHtml { html ->
            if (html.isEmpty()) { toast("No se pudo obtener el código"); return@getDomHtml }
            val tv = TextView(this)
            tv.typeface = android.graphics.Typeface.MONOSPACE
            tv.textSize = 10.5f
            tv.setPadding(40, 30, 40, 20)
            tv.text = html.take(60000) +
                if (html.length > 60000) "\n\n… (recortado; guarda el archivo para verlo completo)" else ""
            val sv = android.widget.ScrollView(this)
            sv.addView(tv)
            AlertDialog.Builder(this)
                .setTitle("🔬 HTML en bruto (DOM vivo)")
                .setView(sv)
                .setPositiveButton("Cerrar", null)
                .setNeutralButton("💾 Guardar .html") { _, _ ->
                    val p = Exporter.saveFile(this,
                        sanitizeName(tabs.getOrNull(cur)?.url ?: "pagina") +
                            "_${tsName()}.html", "text/html",
                        html.toByteArray(Charsets.UTF_8))
                    toast(if (p == null) "Error al guardar" else "HTML en $p")
                }
                .show()
        }
    }

    private fun showNetLog() {
        val lay = LinearLayout(this)
        lay.orientation = LinearLayout.VERTICAL
        val sw = android.widget.Switch(this)
        sw.text = "  Registrar peticiones de red"
        sw.isChecked = eng.sp.getBoolean("netlog", false)
        sw.setPadding(40, 20, 40, 10)
        sw.setOnCheckedChangeListener { _, c ->
            eng.sp.edit().putBoolean("netlog", c).apply()
            if (c) com.turbodl.util.DevLog.clearNet()
        }
        lay.addView(sw)
        val rows = com.turbodl.util.DevLog.netRows()
        val tv = TextView(this)
        tv.typeface = android.graphics.Typeface.MONOSPACE
        tv.textSize = 10f
        tv.setPadding(40, 20, 40, 20)
        tv.text = if (rows.isEmpty())
            "Sin peticiones registradas todavía. Activa el interruptor y recarga la página: aquí aparecen todas las peticiones (método, tipo y URL).\n\nNota: la API del WebView no entrega las cabeceras de respuesta completas; esto registra lo disponible."
        else rows.joinToString("\n") { it.line() }
        val sv = android.widget.ScrollView(this)
        sv.addView(tv)
        lay.addView(sv, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 560))
        val btnC = Button(this)
        btnC.text = "🧹 Limpiar registro"
        lay.addView(btnC)
        AlertDialog.Builder(this)
            .setTitle("📡 Registro de red (${rows.size})")
            .setView(lay)
            .setNegativeButton("Cerrar", null)
            .show()
        btnC.setOnClickListener {
            com.turbodl.util.DevLog.clearNet()
            toast("Registro limpiado")
        }
    }

    private fun showJsConsole() {
        val lay = LinearLayout(this)
        lay.orientation = LinearLayout.VERTICAL
        val lv = ListView(this)
        val ad = object : BaseAdapter() {
            override fun getCount(): Int = com.turbodl.util.DevLog.conRows().size
            override fun getItem(p: Int): com.turbodl.util.DevLog.ConRow =
                com.turbodl.util.DevLog.conRows()[p]
            override fun getItemId(p: Int): Long = p.toLong()
            override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
                val v = TextView(this@BrowserActivity)
                val c = getItem(p)
                v.setPadding(40, 18, 40, 18)
                v.textSize = 11f
                v.typeface = android.graphics.Typeface.MONOSPACE
                v.text = c.line()
                v.setTextColor(when (c.level) {
                    "error" -> 0xFFC62828.toInt()
                    "warn" -> 0xFFEF6C00.toInt()
                    else -> 0xFF212121.toInt()
                })
                return v
            }
        }
        lv.adapter = ad
        lay.addView(lv, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 500))
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(40, 0, 40, 0)
        val e = android.widget.EditText(this)
        e.hint = "javascript: document.title"
        e.setSingleLine(true)
        row.addView(e, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val bRun = Button(this)
        bRun.text = "▶"
        bRun.textSize = 12f
        row.addView(bRun)
        lay.addView(row)
        val dlg = AlertDialog.Builder(this)
            .setTitle("⌨ Consola JavaScript (${com.turbodl.util.DevLog.conRows().size})")
            .setView(lay)
            .setNeutralButton("🧹 Limpiar") { _, _ ->
                com.turbodl.util.DevLog.clearCon()
                ad.notifyDataSetChanged()
            }
            .setNegativeButton("Cerrar", null)
            .show()
        bRun.setOnClickListener {
            val code = e.text.toString().trim()
            if (code.isEmpty()) return@setOnClickListener
            try {
                web.evaluateJavascript(code) { r ->
                    val out = r?.trim() ?: "null"
                    com.turbodl.util.DevLog.logCon("log", "» $code\n← $out")
                    runOnUiThread { ad.notifyDataSetChanged() }
                }
            } catch (ex: Exception) {
                com.turbodl.util.DevLog.logCon("error", "eval: ${ex.message}")
                ad.notifyDataSetChanged()
            }
            e.setText("")
        }
    }

    // ---------------- usuarios sandbox (F2) ----------------

    private fun showUsersDialog() {
        val users = Users.list(this)
        val active = Users.active(this)
        val opts = ArrayList<String>()
        opts.add("🏠 Principal" + (if (active == "main") "   (activo)" else ""))
        users.forEach { opts.add("👤 ${it.name}" + (if (active == it.id) "   (activo)" else "")) }
        opts.add("＋ Añadir usuario…")
        opts.add("✏ Renombrar usuario…")
        opts.add("👁 Ver sandbox del usuario activo…")
        opts.add("🗑 Eliminar usuario…")
        AlertDialog.Builder(this)
            .setTitle("👥 Usuarios (sandbox por usuario)")
            .setMessage("Cada usuario tiene sus propias cookies, sesiones, credenciales, historial y accesos: los sitios web solo detectan al usuario activo. Al cambiar, la aplicación se reinicia.")
            .setItems(opts.toTypedArray()) { _, w ->
                when {
                    w == opts.size - 1 -> deleteUsersDialog()
                    w == opts.size - 2 -> showSandboxViewer()
                    w == opts.size - 3 -> renameUserDialog()
                    w == opts.size - 4 -> addAndSwitchUser()
                    w == 0 -> if (active != "main") confirmSwitchUser("main")
                    else -> {
                        val u = users[w - 1]
                        if (u.id != active) confirmSwitchUser(u.id)
                    }
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun confirmSwitchUser(id: String) {
        AlertDialog.Builder(this)
            .setTitle("Cambiar de usuario")
            .setMessage("TurboDL se reiniciará con la sandbox de «${Users.nameOf(this, id)}»: sus cookies y credenciales son independientes y el servidor creerá que solo ese usuario ha accedido.")
            .setPositiveButton("Reiniciar") { _, _ ->
                Users.setActive(this, id)
                Users.restartApp(this)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun addAndSwitchUser() {
        val e = android.widget.EditText(this)
        e.hint = "Nombre del usuario (p. ej. Trabajo)"
        AlertDialog.Builder(this)
            .setTitle("Nuevo usuario")
            .setView(e)
            .setPositiveButton("Crear") { _, _ ->
                val n = e.text.toString().trim().ifEmpty { "Usuario" }
                val u = Users.add(this, n)
                confirmSwitchUser(u.id)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** 4.2: CRUD completo — Renombrar un usuario existente. Antes solo se
     *  podía crear y eliminar. El id no cambia, así que todo el sandbox
     *  (cookies, history, creds, marcadores) se conserva al renombrar. */
    private fun renameUserDialog() {
        val users = Users.list(this)
        if (users.isEmpty()) { toast("No hay usuarios adicionales"); return }
        val names = users.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Renombrar usuario")
            .setItems(names) { _, w ->
                val u = users[w]
                val e = android.widget.EditText(this)
                e.hint = "Nuevo nombre"
                e.setText(u.name)
                AlertDialog.Builder(this)
                    .setTitle("Renombrar a «${u.name}»")
                    .setView(e)
                    .setPositiveButton("Renombrar") { _, _ ->
                        val n = e.text.toString().trim()
                        if (n.isNotEmpty() && n != u.name) {
                            Users.rename(this, u.id, n)
                            toast("Usuario renombrado a «$n»")
                        }
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun deleteUsersDialog() {
        val users = Users.list(this)
        if (users.isEmpty()) { toast("No hay usuarios adicionales"); return }
        val names = users.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Eliminar usuario")
            .setItems(names) { _, w ->
                val u = users[w]
                AlertDialog.Builder(this)
                    .setTitle("Eliminar a «${u.name}»")
                    .setMessage("Se borra SU SANDBOX COMPLETO: cookies, localStorage y " +
                        "caché del WebView, credenciales, historial de navegación, " +
                        "marcadores y accesos. Esta acción no se puede deshacer.")
                    .setPositiveButton("Eliminar") { _, _ ->
                        val eraActivo = Users.active(this) == u.id
                        // 4.2: limpiar datos del usuario en DB antes de
                        // borrarlo de la lista (Users.remove borra archivos
                        // y SharedPrefs; aquí borramos las filas de DB).
                        try { eng.db.clearHistory(u.id) } catch (e: Exception) { }
                        try { eng.db.clearCreds(u.id) } catch (e: Exception) { }
                        Users.remove(this, u.id)
                        toast("Usuario eliminado (sandbox borrado)")
                        if (eraActivo) Users.restartApp(this)
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /**
     * Visor del sandbox del usuario activo (4.2): muestra qué hay
     * almacenado en la sandbox del usuario que está usando la app ahora
     * mismo. El usuario pedía poder ver "cookies, etc." de cada sandbox.
     *
     * Recoge y muestra:
     *  · Cookies del WebView (CookieManager) — todas las del sandbox
     *    activo, agrupadas por host.
     *  · Conteo de entradas de localStorage e IndexedDB — se obtiene
     *    inyectando JS en el WebView de la pestaña activa (solo si hay
     *    una página http/https cargada; en otra caso se informa de que
     *    no están disponibles sin una página abierta).
     *  · Historial de navegación (tabla history filtrada por usuario).
     *  · Credenciales guardadas + hosts con "nunca preguntar"
     *    (tabla creds filtrada por usuario).
     *  · Marcadores y accesos (SharedPreferences namespaced por usuario).
     *  · Ruta del directorio WebView del sandbox en disco.
     *
     * Todo es de SOLO LECTURA: el visor no modifica nada. Para borrar
     * hay que usar "Limpiar datos" o eliminar/renombrar al usuario.
     */
    private fun showSandboxViewer() {
        val uid = Users.active(this)
        val uname = Users.nameOf(this, uid)
        val sb = StringBuilder()

        sb.append("Usuario activo: ").append(uname).append("  (id: ").append(uid).append(")\n\n")

        // --- cookies (CookieManager) ---
        val cm = CookieManager.getInstance()
        val allCookies = try { cm.getCookie(null)?.toString() ?: "" } catch (e: Exception) { "" }
        val cookieHosts = LinkedHashSet<String>()
        if (allCookies.isNotEmpty()) {
            // CookieManager.getCookie(null) devuelve una cadena con todas
            // las cookies del sandbox separadas por ';'. Cada cookie trae
            // su path y a veces dominio; aquí listamos los pares name=value.
            val entries = allCookies.split(';').map { it.trim() }.filter { it.isNotEmpty() }
            for (entry in entries) {
                val name = entry.substringBefore('=', entry).trim()
                // no podemos saber el host de cada cookie desde la API
                // pública; metemos las entradas en un set para desdup.
                cookieHosts.add(name)
            }
        }
        sb.append("🍪 Cookies del sandbox: ").append(cookieHosts.size).append(" entradas\n")
        // mostramos hasta 30 nombres de cookie
        cookieHosts.take(30).forEach { sb.append("    · ").append(it).append('\n') }
        if (cookieHosts.size > 30) sb.append("    … y ").append(cookieHosts.size - 30).append(" más\n")
        sb.append('\n')

        // --- localStorage / IndexedDB (vía JS en el WebView activo) ---
        // solo se puede contar si hay una página http(s) cargada: las
        // APIs localStorage/indexedDB se asocian al origen del documento.
        val w = activeWv()
        val url = w?.url ?: ""
        if (w != null && (url.startsWith("http://") || url.startsWith("https://"))) {
            sb.append("🌐 Página activa: ").append(url.take(70)).append("\n\n")
            // pedir conteo de localStorage e IndexedDB en segundo plano
            try {
                w.evaluateJavascript(
                    "(function(){try{var ls=0;try{ls=window.localStorage?window.localStorage.length:0}catch(e){}" +
                    "var idb='-';try{var r=indexedDB.databases?indexedDB.databases().length:'n/d';idb=r}catch(e){idb='n/d'}" +
                    "return JSON.stringify({ls:ls,idb:idb});}catch(e){return JSON.stringify({ls:0,idb:'n/d'});}})();"
                ) { r ->
                    val parsed = parseStorageJson(r)
                    appendStorageSummary(sb, parsed.first, parsed.second, url)
                    showSandboxViewerDialog(uid, uname, sb)
                }
            } catch (e: Exception) {
                appendStorageSummary(sb, 0, "n/d", url)
                showSandboxViewerDialog(uid, uname, sb)
            }
        } else {
            sb.append("🌐 No hay página http(s) abierta — localStorage/IndexedDB\n")
            sb.append("    solo se pueden inspeccionar con una página cargada.\n\n")
            showSandboxViewerDialog(uid, uname, sb)
        }
    }

    private fun parseStorageJson(r: String?): Pair<Int, String> {
        if (r.isNullOrBlank()) return 0 to "n/d"
        val s = r.trim().removeSurrounding("\"")
        return try {
            val o = org.json.JSONObject(s)
            (o.optInt("ls", 0)) to (o.optString("idb", "n/d"))
        } catch (e: Exception) { 0 to "n/d" }
    }

    private fun appendStorageSummary(sb: StringBuilder, ls: Int, idb: String, url: String) {
        sb.append("🗄 localStorage (de ").append(hostOf(url)).append("): ")
            .append(ls).append(" claves\n")
        sb.append("🗃 IndexedDB (de ").append(hostOf(url)).append("): ")
            .append(idb).append(" bases\n\n")
    }

    private fun showSandboxViewerDialog(uid: String, uname: String, sbSoFar: StringBuilder) {
        val sb = sbSoFar
        // --- historial de navegación (DB) ---
        val hist = try { eng.db.history(200, uid) } catch (e: Exception) { ArrayList() }
        sb.append("🕘 Historial de navegación: ").append(hist.size).append(" páginas\n")
        hist.take(15).forEach {
            val title = it.title.ifEmpty { hostOf(it.url) }
            sb.append("    · ").append(title.take(40)).append('\n')
        }
        if (hist.size > 15) sb.append("    … y ").append(hist.size - 15).append(" más\n")
        sb.append('\n')

        // --- credenciales ---
        val credCount = try { eng.db.credsCount(uid) } catch (e: Exception) { 0 }
        val neverCount = try { eng.db.neverCount(uid) } catch (e: Exception) { 0 }
        sb.append("🔑 Credenciales guardadas: ").append(credCount).append('\n')
        sb.append("🚫 Hosts con «nunca preguntar»: ").append(neverCount).append('\n')
        // lista de hosts de credenciales (sin secreto)
        try {
            val rows = eng.db.creds(uid)
            rows.filter { !it.never }.take(15).forEach {
                sb.append("    · ").append(it.host).append("  (usuario: ")
                  .append(it.username.ifEmpty { "?" }).append(")\n")
            }
            rows.filter { it.never }.take(10).forEach {
                sb.append("    🚫 ").append(it.host).append("  (nunca preguntar)\n")
            }
        } catch (e: Exception) { }
        sb.append('\n')

        // --- marcadores (SharedPrefs) ---
        val bmCount = try {
            val arr = JSONArray(eng.sp.getString("bookmarks_$uid", "[]") ?: "[]")
            arr.length()
        } catch (e: Exception) { 0 }
        sb.append("⭐ Marcadores: ").append(bmCount).append('\n')

        // --- accesos / sitios (SharedPrefs) ---
        var sitesTotal = 0
        for (p in 0..2) {
            val n = try {
                val arr = JSONArray(eng.sp.getString("sitesP${p}_$uid", "[]") ?: "[]")
                arr.length()
            } catch (e: Exception) { 0 }
            sitesTotal += n
        }
        sb.append("📌 Accesos (paneles de sitios): ").append(sitesTotal).append('\n')

        // --- historial de búsqueda (omnibox) ---
        val qCount = try {
            val arr = JSONArray(eng.sp.getString("history_$uid", "[]") ?: "[]")
            arr.length()
        } catch (e: Exception) { 0 }
        sb.append("🔎 Historial de búsquedas: ").append(qCount).append('\n')

        // --- ruta del sandbox en disco ---
        sb.append('\n').append("📂 Directorio del sandbox:\n")
        val suffix = if (uid == "main") "(predeterminado)" else "usr_$uid"
        val webviewBase = java.io.File(dataDir, "app_webview")
        val target = if (uid == "main") webviewBase else java.io.File(webviewBase, suffix)
        sb.append("    ").append(target.absolutePath).append('\n')
        val size = try { dirSize(target) } catch (e: Exception) { 0L }
        sb.append("    Tamaño en disco: ").append(Kit.fmtBytes(size)).append('\n')

        // --- mostrar ---
        val tv = TextView(this)
        tv.setPadding(50, 30, 50, 30)
        tv.textSize = 12f
        tv.typeface = android.graphics.Typeface.MONOSPACE
        tv.text = sb.toString()
        val scroll = android.widget.ScrollView(this)
        scroll.addView(tv)
        AlertDialog.Builder(this)
            .setTitle("👁 Sandbox de «$uname»")
            .setView(scroll)
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /** Tamaño total (bytes) de un directorio (recursivo). */
    private fun dirSize(f: java.io.File): Long {
        if (!f.exists()) return 0
        if (f.isFile) return f.length()
        var s = 0L
        f.listFiles()?.forEach { s += dirSize(it) }
        return s
    }

    // ---------------- credenciales guardadas (F1) ----------------

    private fun showCredsDialog() {
        val uid = Users.active(this)
        val rows = eng.db.creds(uid)
        val lay = LinearLayout(this)
        lay.orientation = LinearLayout.VERTICAL
        val lv = ListView(this)
        val ad = object : BaseAdapter() {
            override fun getCount(): Int = rows.size
            override fun getItem(p: Int): Db.CredRow = rows[p]
            override fun getItemId(p: Int): Long = rows[p].id
            override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
                val v = TextView(this@BrowserActivity)
                val c = getItem(p)
                v.setPadding(40, 26, 40, 26)
                v.textSize = 13f
                v.text = "${c.host}\n${c.username.ifEmpty { "(solo bloqueo de guardado)" }}" +
                    if (c.never) " · nunca preguntar" else ""
                v.background = getDrawable(android.R.drawable.list_selector_background)
                return v
            }
        }
        lv.adapter = ad
        lay.addView(lv, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 560))
        if (rows.isEmpty()) {
            val empty = TextView(this)
            empty.text = "Sin credenciales guardadas para el usuario activo.\n\nAl iniciar sesión en cualquier sitio, TurboDL ofrecerá guardar el usuario y la contraseña (cifrados con AES-256-GCM y la clave segura del dispositivo) y los rellenará automáticamente en tus próximas visitas."
            empty.setPadding(40, 30, 40, 30)
            lay.addView(empty, 0)
        }
        AlertDialog.Builder(this)
            .setTitle("🔑 Credenciales (${rows.size})")
            .setView(lay)
            .setNegativeButton("Cerrar", null)
            .show()
        lv.setOnItemClickListener { _, _, p, _ ->
            val c = rows[p]
            val acts = arrayOf("📋 Copiar usuario", "🗑 Eliminar")
            AlertDialog.Builder(this)
                .setTitle(c.host)
                .setItems(acts) { _, w ->
                    when (w) {
                        0 -> { clipboard(c.username); toast("Usuario copiado") }
                        1 -> {
                            eng.db.deleteCred(c.id)
                            rows.removeAt(p)
                            ad.notifyDataSetChanged()
                            toast("Credencial eliminada")
                        }
                    }
                }
                .setNegativeButton("Cerrar", null)
                .show()
        }
    }

    private var lastBack = 0L

    /**
     * Botón atrás consistente (4.0 U4): una única máquina de estados
     * sugerencias → hojas → inicio ↔ web → salida con confirmación.
     */
    override fun onBackPressed() {
        when {
            suggestionsHost.isVisible() -> {
                hideSuggestions()
                hideKb()
            }
            tabSheet.isVisible() || menuSheet.isVisible() -> hideSheets()
            homeOverlay.isVisible() -> {
                val t = tabs.getOrNull(cur)
                if (t != null && (t.url.isNotEmpty() || (t.wv?.canGoBack() == true)))
                    showHome(false)
                else {
                    val now = System.currentTimeMillis()
                    if (now - lastBack < 2200) finishAffinity()
                    else { lastBack = now; toast("Pulsa atrás otra vez para salir") }
                }
            }
            else -> {
                val w = activeWv()
                if (w != null && w.canGoBack()) w.goBack()
                else showHome(true)
            }
        }
    }

    override fun onDestroy() {
        // 4.2 (H3): cancelar callbacks diferidos que tocarían Views ya
        // destruidos (onCreateWindow, addDetected, etc.) y limpiar los
        // WebViews temporales pendientes antes de destruir las pestañas.
        uiHandler.removeCallbacksAndMessages(null)
        synchronized(pendingTempWebViews) {
            pendingTempWebViews.forEach { w ->
                try { w.destroy() } catch (e: Exception) { }
            }
            pendingTempWebViews.clear()
        }
        flushStats()
        val snapshot = snapshotTabs()
        snapshot.forEach { t ->
            t.wv?.let { w ->
                try {
                    w.removeJavascriptInterface("__tdlBridge")
                    w.destroy()
                } catch (e: Exception) { }
            }
        }
        synchronized(tabsLock) { tabs.clear() }
        super.onDestroy()
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
