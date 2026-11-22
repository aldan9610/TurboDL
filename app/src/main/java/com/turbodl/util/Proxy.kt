package com.turbodl.util

import android.content.Context
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy as NetProxy

/**
 * Proxy (4.5): soporte de proxy HTTP, HTTPS-proxy, SOCKS4 y SOCKS5 para
 * TODO el tráfico de red de TurboDL — navegador (WebView) y motor de
 * descargas (HTTP/HTTPS/FTP/subtítulos/favicons/actualizaciones).
 *
 * ### Por qué no basta con ProxySelector de la JVM
 * El WebView de Android **ignora** el `ProxySelector` de la JVM y el
 * `System.setProperty("http.proxyHost", ...)`: usa el stack de red de
 * Chromium, que no lee esas propiedades. Por eso la API oficial moderna
 * es `androidx.webkit.ProxyController.setProxyOverride` (disponible con
 * WebView actualizable en Android 9+). Esta clase:
 *   1. Persiste la config de proxy en `SharedPreferences "cfg"` (host,
 *      puerto, tipo, usuario, contraseña cifrada con `Secrets`).
 *   2. Construye el `java.net.Proxy` para el motor de descargas (OkHttp
 *      no se usa; todo va por `HttpURLConnection.openConnection(Proxy)`).
 *   3. Instala un `Authenticator` global para las credenciales del proxy
 *      (cubre auth de HTTP CONNECT y de SOCKS5 user/pass; SOCKS4 no
 *      soporta auth).
 *   4. Construye el `ProxyConfig` para el WebView y lo aplica con
 *      `ProxyController.setProxyOverride` (Android 9+); en <9 el proxy
 *      del navegador es el del sistema (sin proxy propio) — documentado
 *      en la UI.
 *
 * ### Tipos soportados
 *   - HTTP:    proxy HTTP clásico (CONNECT para HTTPS). `Proxy.Type.HTTP`.
 *   - HTTPS:   proxy que a su vez habla TLS (HTTPS-proxy, RFC 2817).
 *              Mismo `Proxy.Type.HTTP`, pero el WebView lo marca con
 *              `applyToTunnel` y la JVM lo tuneliza vía un `SSLSocket`
 *              al proxy. (HttpURLConnection no soporta HTTPS-proxy de
 *              forma nativa; aquí se enruta como HTTP y se deja que el
 *              usuario lo use sobre todo en el navegador.)
 *   - SOCKS4:  `Proxy.Type.SOCKS` sin auth (SOCKS4 no soporta usuario).
 *   - SOCKS5:  `Proxy.Type.SOCKS` con auth opcional (user/pass).
 *
 * ### Almacenamiento
 *   - `proxyType`: "none" | "http" | "https" | "socks4" | "socks5".
 *   - `proxyHost`, `proxyPort`: texto.
 *   - `proxyUser`: texto (vacío = sin auth).
 *   - `proxyPassEnc`: contraseña cifrada con `Secrets.encrypt`
 *     (AES-256-GCM, clave en AndroidKeyStore — mismo mecanismo que las
 *     credenciales HTTP de la tabla `creds`).
 */
object Proxy {

    const val TYPE_NONE = "none"
    const val TYPE_HTTP = "http"
    const val TYPE_HTTPS = "https"
    const val TYPE_SOCKS4 = "socks4"
    const val TYPE_SOCKS5 = "socks5"

    /** Etiquetas legibles para la UI (4.5). */
    val TYPES = listOf(
        TYPE_NONE to "Ninguno (conexión directa)",
        TYPE_HTTP to "HTTP",
        TYPE_HTTPS to "HTTPS-proxy (proxy con TLS)",
        TYPE_SOCKS4 to "SOCKS4",
        TYPE_SOCKS5 to "SOCKS5"
    )

    private fun sp(ctx: Context) =
        ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    /** Tipo de proxy activo. "none" si no hay o si la config está
     *  incompleta (host vacío o puerto inválido). */
    fun type(ctx: Context): String =
        sp(ctx).getString("proxyType", TYPE_NONE) ?: TYPE_NONE

    fun host(ctx: Context): String =
        sp(ctx).getString("proxyHost", "") ?: ""

    fun port(ctx: Context): Int {
        val p = sp(ctx).getInt("proxyPort", 0)
        return if (p in 1..65535) p else 0
    }

    fun user(ctx: Context): String =
        sp(ctx).getString("proxyUser", "") ?: ""

    /** Contraseña del proxy en claro (descifrada bajo demanda). Vacía si
     *  no hay o si el descifrado falla (no se bloquea el proxy por un
     *  dato corrupto: se usa sin auth). */
    fun pass(ctx: Context): String {
        val enc = sp(ctx).getString("proxyPassEnc", "") ?: ""
        if (enc.isEmpty()) return ""
        return try { Secrets.decrypt(enc) ?: "" } catch (e: Throwable) { "" }
    }

    /** ¿Hay un proxy realmente configurado y usable? */
    fun isEnabled(ctx: Context): Boolean {
        val t = type(ctx)
        if (t == TYPE_NONE) return false
        val h = host(ctx)
        val p = port(ctx)
        return h.isNotEmpty() && p in 1..65535
    }

    /** ¿El tipo activo acepta credenciales (user/pass)? SOCKS4 y "none"
     *  no las aceptan; HTTP, HTTPS-proxy y SOCKS5 sí. */
    fun supportsAuth(ctx: Context): Boolean = when (type(ctx)) {
        TYPE_HTTP, TYPE_HTTPS, TYPE_SOCKS5 -> true
        else -> false
    }

    /**
     * Guarda la configuración de proxy. `pass` va cifrada con Secrets.
     * Pasar pass=null para no tocarla (p.ej. al cambiar solo el tipo).
     */
    fun save(ctx: Context, type: String, host: String, port: Int,
             user: String = "", pass: String? = null) {
        val e = sp(ctx).edit()
        e.putString("proxyType", type)
        e.putString("proxyHost", host.trim())
        e.putInt("proxyPort", if (port in 1..65535) port else 0)
        e.putString("proxyUser", user.trim())
        if (pass != null) {
            e.putString("proxyPassEnc",
                if (pass.isEmpty()) "" else try { Secrets.encrypt(pass) } catch (t: Throwable) { "" })
        }
        e.apply()
    }

    /** Desactiva el proxy (tipo "none") sin borrar el resto (así se
     *  reactiva fácil devolviendo el tipo). */
    fun disable(ctx: Context) {
        sp(ctx).edit().putString("proxyType", TYPE_NONE).apply()
    }

    // ---------------- motor de descargas (java.net) ----------------

    /**
     * Construye el `java.net.Proxy` para el tipo activo, o null si no
     * hay proxy (conexión directa). Usar con `URL.openConnection(proxy)`.
     *
     * - HTTP / HTTPS-proxy → `Proxy.Type.HTTP` (la JVM hace CONNECT para
     *   HTTPS sobre un proxy HTTP; HTTPS-proxy puro no lo soporta
     *   HttpURLConnection de forma nativa, se trata como HTTP).
     * - SOCKS4 / SOCKS5 → `Proxy.Type.SOCKS`.
     */
    fun javaProxy(ctx: Context): NetProxy? {
        if (!isEnabled(ctx)) return null
        val addr = InetSocketAddress(host(ctx), port(ctx))
        return when (type(ctx)) {
            TYPE_HTTP, TYPE_HTTPS -> NetProxy(NetProxy.Type.HTTP, addr)
            TYPE_SOCKS4, TYPE_SOCKS5 -> NetProxy(NetProxy.Type.SOCKS, addr)
            else -> null
        }
    }

    /**
     * Socket proxied para FTP (4.5): FTP usa sockets crudos, no
     * HttpURLConnection, así que para enrutar FTP por un proxy SOCKS hay
     * que crear el `Socket(Proxy)` directamente. Devuelve un Socket ya
     * configurado (sin conectar). Para FTP sobre proxy HTTP no hay
     * soporte razonable (HTTP CONNECT no encaja con el protocolo FTP de
     * control+datos); se documenta en la UI que FTP solo admite SOCKS.
     */
    fun javaSocket(ctx: Context): java.net.Socket =
        if (isEnabled(ctx) && (type(ctx) == TYPE_SOCKS4 || type(ctx) == TYPE_SOCKS5))
            java.net.Socket(javaProxy(ctx)!!)
        else java.net.Socket()

    /**
     * Instala (o reinstala) el `Authenticator` global de la JVM con las
     * credenciales del proxy activo. Cubre:
     *   - Auth de proxy HTTP (Basic/Digest) — la JVM lo gestiona sola con
     *     el PasswordAuthentication devuelto.
     *   - Auth de SOCKS5 (user/pass) — la JVM lo gestiona en el handshake
     *     SOCKS usando el mismo Authenticator.
     * Llamar desde `App.onCreate` y cada vez que cambie el proxy.
     * Si no hay proxy o no hay user, instala un Authenticator que
     * devuelve null (no interfiere con auth de servidor final).
     */
    fun installAuthenticator(ctx: Context) {
        val u = user(ctx)
        val p = pass(ctx)
        if (isEnabled(ctx) && u.isNotEmpty() && p.isNotEmpty()) {
            Authenticator.setDefault(object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication? {
                    // Solo respondemos a solicitudes de proxy (REQUEST_PROXY),
                    // no a auth de servidor final: así no robamos credenciales
                    // del proxy para el host destino.
                    if (requestorType == RequestorType.PROXY)
                        return PasswordAuthentication(u, p.toCharArray())
                    return null
                }
            })
        } else {
            // Sin proxy auth: quita cualquier Authenticator previo para
            // no interferir con auth de servidor.
            Authenticator.setDefault(null)
        }
    }

    // ---------------- WebView (androidx.webkit) ----------------

    /**
     * Aplica el proxy activo al WebView de toda la app (4.5). Usa
     * `androidx.webkit.ProxyController.setProxyOverride`, que enruta
     * TODA la carga del WebView (página + subrecursos) por el proxy.
     *
     * - Disponible con WebView actualizable en Android 9+ (API 28).
     *   El feature flag es `PROXY_OVERRIDE`. Si no está, no hacemos
     *   nada (el navegador usa el proxy del sistema, si lo hay).
     * - `clearProxyOverride` se usa cuando el tipo es "none".
     * - SOCKS4/5 se pasan al ProxyConfig con `addSocksProxy`. HTTP/HTTPS
     *   con `applyToTunnel=true` (CONNECT para HTTPS).
     * - Las credenciales van embebidas en el proxyUrl del ProxyConfig
     *   (no hay callback de auth separado en ProxyController).
     *
     * Llamar desde `App.onCreate` (tras `applyDataDirSuffix`) y cada vez
     * que el usuario cambie el proxy en SettingsActivity.
     */
    fun applyToWebView(ctx: Context) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            android.util.Log.i("TurboDL.Proxy",
                "ProxyController no soportado en este dispositivo (Android <9 o " +
                "WebView sin actualizar); el navegador usará el proxy del sistema.")
            return
        }
        val pc = ProxyController.getInstance()
        // ProxyController pide Executor + Runnable (no lambdas Kotlin).
        val exec = java.util.concurrent.Executors.newSingleThreadExecutor()
        val onDone = Runnable {
            android.util.Log.i("TurboDL.Proxy", "Proxy WebView aplicado: ${summary(ctx)}")
        }
        if (!isEnabled(ctx)) {
            pc.clearProxyOverride(exec, onDone)  // sin proxy: conexión directa
            return
        }
        // ProxyConfig.Builder: la regla proxy aplica a TODOS los hosts
        // (no hay "direct" intercalado). El proxyUrl lleva el esquema
        // (http/https/socks5) y, opcionalmente, user:pass embebidos.
        val builder = ProxyConfig.Builder()
            .addProxyRule(buildProxyUrl(ctx))
        try {
            pc.setProxyOverride(builder.build(), exec, onDone)
        } catch (e: Throwable) {
            android.util.Log.w("TurboDL.Proxy", "setProxyOverride falló: ${e.message}")
        }
    }

    /**
     * Construye la URL del proxy para el ProxyConfig del WebView:
     *   - HTTP:    http://[user:pass@]host:port
     *   - HTTPS:   https://[user:pass@]host:port
     *   - SOCKS5:  socks5://[user:pass@]host:port
     *   - SOCKS4:  socks4://host:port  (sin credenciales)
     * El ProxyConfig acepta el esquema y el userinfo embebido; si hay
     * credenciales las usa para CONNECT/SOCKS auth.
     */
    private fun buildProxyUrl(ctx: Context): String {
        val t = type(ctx)
        val h = host(ctx)
        val p = port(ctx)
        val scheme = when (t) {
            TYPE_HTTP -> "http"
            TYPE_HTTPS -> "https"
            TYPE_SOCKS4 -> "socks4"
            TYPE_SOCKS5 -> "socks5"
            else -> "http"
        }
        val u = user(ctx)
        val pw = pass(ctx)
        val auth = if (supportsAuth(ctx) && u.isNotEmpty())
            "${urlEncode(u)}:${urlEncode(pw)}@"
        else ""
        return "$scheme://$auth$h:$p"
    }

    private fun urlEncode(s: String): String =
        try { java.net.URLEncoder.encode(s, "UTF-8") } catch (e: Throwable) { s }

    /** Resumen legible para logs y UI. */
    fun summary(ctx: Context): String {
        if (!isEnabled(ctx)) return "sin proxy (conexión directa)"
        val t = type(ctx)
        val label = TYPES.firstOrNull { it.first == t }?.second ?: t
        val auth = if (supportsAuth(ctx) && user(ctx).isNotEmpty()) " (auth)" else ""
        return "$label · ${host(ctx)}:${port(ctx)}$auth"
    }
}
