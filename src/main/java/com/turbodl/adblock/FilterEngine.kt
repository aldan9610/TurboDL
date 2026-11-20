package com.turbodl.adblock

/**
 * Motor de filtrado de anuncios 100% KOTLIN (sin JNI, sin Rust, sin código
 * nativo). Sustituye al AAR adblock-rust que se eliminó en la 3.6.0.
 *
 * Implementa el subconjunto de la sintaxis EasyList que usan las listas
 * integradas (assets/adblock/easylist.txt + easyprivacy.txt, mantenidas por
 * easylist.to desde hace más de 15 años; las LISTAS son la parte madura, el
 * motor compacto es nuestro y se prueba con tests JUnit contra las listas
 * reales).
 *
 * Sintaxis soportada (red):
 *  - ||host^        ancla de dominio (host o subdominio de host)
 *  - |inicio / fin| anclas absolutas de la URL
 *  - *              comodín          ^  separador (cualquier carácter que no
 *                   sea letra, dígito, _ - . %)
 *  - Opciones tras $: script, stylesheet, image, media, font,
 *    xmlhttprequest, object, subdocument, popup, other, document,
 *    third-party / first-party (y sus negaciones ~), important,
 *    domain=a|~b, elemhide, generichide, all, match-case (se ignora:
 *    todo se compara en minúsculas), denyallow/to/method/csp/redirect
 *    (se ignoran: sin efecto, la regla queda más amplia = dirección
 *    segura).
 *  - Excepciones @@ (desbloquean) salvo que la regla de bloqueo lleve
 *    $important, que gana siempre (semántica de EasyList).
 * Sintaxis soportada (ocultación de elementos):
 *  - dominio1,dominio2##selector   oculta en esos dominios (y subdominios)
 *  - ##selector                    oculta en todas las páginas (genérica,
 *                                  con límite de tamaño en el CSS final)
 *  - #@#selector                   excepción (no ocultar)
 *  - #?# y #@$#                    igual que ## / #@# pero solo se aceptan
 *                                  selectores CSS planos (se descartan los
 *                                  que usan :has(, :has-text(, :style(,
 *                                  :remove( o pseudo-clases -abp-)
 *
 * Rendimiento (medido en tests): las reglas ||host se indexan en un
 * HashMap por dominio del patrón (la gran mayoría en EasyList); las
 * genéricas se agrupan por su token literal más largo y solo se evalúan si
 * el token está en la URL. Sin regex en el camino caliente; todo el
 * parseo usa bucles de caracteres. RAM típica: ~10 MB con EasyList,
 * ~20 MB con ambas listas (el motor Rust ocupaba 90-165 MB nativos).
 *
 * Publicación segura entre hilos: el parseo ocurre UNA vez en un hilo de
 * fondo; después las estructuras son de solo lectura y el motor se
 * publica como campo volátil de la fachada.
 */
class FilterEngine {

    // ---------------------------------------------------------------- tipos
    private val TYPE_BITS = HashMap<String, Int>(16)

    init {
        TYPE_BITS["script"] = 1
        TYPE_BITS["stylesheet"] = 2
        TYPE_BITS["image"] = 4
        TYPE_BITS["media"] = 8
        TYPE_BITS["font"] = 16
        TYPE_BITS["xmlhttprequest"] = 32
        TYPE_BITS["object"] = 64
        TYPE_BITS["subdocument"] = 128
        TYPE_BITS["popup"] = 256
        TYPE_BITS["other"] = 512
        TYPE_BITS["document"] = 1024
    }

    /** Bit para tipos conocidos de EasyList que la app nunca emite (ping,
     *  websocket…). UNA regla cuyo único tipo es desconocido NO debe aplicarse
     *  a nada: si se ignorara la opción, `*$ping,third-party` se convertiría
     *  en "bloquear todo lo third-party" y reventaría la web. */
    private val BIT_NEVER = 1 shl 20

    /** Regla de red ya parseada. Todas las cadenas en minúsculas. */
    private class Rule(
        val pattern: String,        // parte de URL (sin @@ ni $opciones)
        val host: String?,          // host del patrón si empieza por || (sin comodines)
        val hostAnchor: Boolean,    // el patrón empezaba por ||
        val leftAnchor: Boolean,    // empezaba por |
        val rightAnchor: Boolean,   // acababa por |
        val token: String,          // literal más largo (índice de genéricas)
        val types: Int,             // bits requeridos; 0 = cualquier tipo
        val notTypes: Int,          // bits excluidos
        val party: Int,             // 0 indiferente, 1 third-party, 2 first-party
        val domains: List<String>?, // $domain= incluidas (null si no hay)
        val notDomains: List<String>?, // $domain=~ excluidas
        val denyHosts: List<String>?, // $denyallow= el host pedido NO puede ser ninguno
        val toHosts: List<String>?,   // $to= el host pedido DEBE ser alguno
        val important: Boolean,
        val elemhide: Boolean,      // $elemhide (solo útil en @@)
        val generichide: Boolean    // $generichide (solo útil en @@)
    )

    // ------------------------------------------------------------- índices
    private val hostRules = HashMap<String, MutableList<Rule>>(4096)
    private val genericRules = ArrayList<Rule>(1024)
    private val genericTokens = HashMap<String, MutableList<Rule>>(2048)
    private val hostExc = HashMap<String, MutableList<Rule>>(256)
    private val genericExc = ArrayList<Rule>(64)
    private val genericExcTokens = HashMap<String, MutableList<Rule>>(64)

    // ocultación de elementos
    private val domHide = HashMap<String, MutableList<String>>(1024)
    private val genHide = ArrayList<String>(1024)
    private val domHideExc = HashMap<String, MutableList<String>>(64)
    private val genHideExc = ArrayList<String>(64)

    var networkRules: Long = 0
        private set
    var cosmeticRules: Long = 0
        private set

    // ------------------------------------------------------------ parseo
    /** Añade una lista de filtros (se puede llamar varias veces, p. ej.
     *  EasyList y luego EasyPrivacy). Devuelve el número de líneas útiles. */
    fun parse(text: String): Int {
        var n = 0
        var i = 0
        val len = text.length
        while (i < len) {
            var j = text.indexOf('\n', i)
            if (j < 0) j = len
            val line = text.substring(i, j).trim()
            i = j + 1
            if (line.isEmpty()) continue
            val c0 = line[0]
            if (c0 == '!' || c0 == '[') continue
            if (parseLine(line)) n++
        }
        return n
    }

    /** true si la línea produjo regla(s). */
    private fun parseLine(line: String): Boolean {
        val low = line.lowercase()
        // ocultación de elementos: #@#, #?#, #@$#, #?# (emulación), ##
        // (los selectores genéricos empiezan por ##, así que la posición 0 es válida)
        val idxExc = low.indexOf("#@#")
        if (idxExc >= 0) { addCosmetic(low, idxExc, 3, true); return true }
        val idxEmul = low.indexOf("#?#")
        if (idxEmul >= 0) { addCosmetic(low, idxEmul, 3, false); return true }
        val idx = low.indexOf("##")
        if (idx >= 0) { addCosmetic(low, idx, 2, false); return true }
        if (low.contains("#$#")) return false  // CSS inline: no soportado
        if (low.startsWith("#")) return false  // comentario de la lista
        return parseNetwork(low)
    }

    private fun addCosmetic(line: String, sepIdx: Int, sepLen: Int, exception: Boolean) {
        val domPart = line.substring(0, sepIdx)
        var sel = line.substring(sepIdx + sepLen).trim()
        if (sel.isEmpty()) return
        // selectores no estándar: no se pueden traducir a CSS seguro
        if (sel.contains(":has(") || sel.contains(":has-text(") ||
            sel.contains(":style(") || sel.contains(":remove(") ||
            sel.contains("-abp-") || sel.contains(":matches(") ||
            sel.contains(":matches-attr(") || sel.contains(":nth-ancestor(") ||
            sel.contains(":upward(") || sel.contains(":watch-attr(")) return
        // el selector puede llevar una opción $... al final (##sel$...)
        val dollar = sel.indexOf('$')
        if (dollar > 0 && sel.indexOf('{') < 0) sel = sel.substring(0, dollar).trim()
        if (sel.isEmpty()) return
        cosmeticRules++
        if (domPart.isEmpty()) {
            if (exception) genHideExc.add(sel) else genHide.add(sel)
            return
        }
        // dominios: a,b,~c — los ~ (excluidos) se ignoran: solo ocultamos si
        // algún dominio positivo casa (dirección conservadora)
        var k = 0
        while (k < domPart.length) {
            var e = domPart.indexOf(',', k)
            if (e < 0) e = domPart.length
            var d = domPart.substring(k, e).trim()
            k = e + 1
            if (d.isEmpty() || d.startsWith("~")) continue
            // un dominio puede llevar ancla de host de URL (d=host): quítala
            val eq = d.indexOf('=')
            if (eq >= 0) d = d.substring(eq + 1)
            if (exception) domHideExc.getOrPut(d) { ArrayList(2) }.add(sel)
            else domHide.getOrPut(d) { ArrayList(4) }.add(sel)
        }
    }

    /** Separa patrón y opciones por el último '$' plausible. */
    private fun parseNetwork(low: String): Boolean {
        var body = low
        var exception = false
        if (body.startsWith("@@")) { exception = true; body = body.substring(2) }
        if (body.isEmpty() || body.length < 3) return false

        var types = 0; var notTypes = 0; var party = 0
        var important = false; var elemhide = false; var generichide = false
        var domains: MutableList<String>? = null
        var notDomains: MutableList<String>? = null
        var denyHosts: MutableList<String>? = null
        var toHosts: MutableList<String>? = null

        // localizar opciones: último '$' seguido solo de caracteres de opción
        var dollar = body.lastIndexOf('$')
        if (dollar >= 0 && dollar < body.length - 1) {
            var ok = true
            for (x in dollar + 1 until body.length) {
                val ch = body[x]
                if (!(ch in 'a'..'z' || ch in '0'..'9' || ch == ',' ||
                        ch == '~' || ch == '=' || ch == '|' || ch == '-' ||
                        ch == '.' || ch == '_')) { ok = false; break }
            }
            if (ok) {
                val opts = body.substring(dollar + 1)
                body = body.substring(0, dollar)
                var k = 0
                while (k < opts.length) {
                    var e = opts.indexOf(',', k)
                    if (e < 0) e = opts.length
                    val o = opts.substring(k, e)
                    k = e + 1
                    if (o.isEmpty()) continue
                    if (o == "third-party") { party = 1; continue }
                    if (o == "~third-party") { party = 2; continue }
                    if (o == "first-party") { party = 2; continue }
                    if (o == "~first-party") { party = 1; continue }
                    if (o == "important") { important = true; continue }
                    if (o == "elemhide" || o == "ehide") { elemhide = true; continue }
                    if (o == "generichide" || o == "ghide") { generichide = true; continue }
                    if (o == "match-case" || o == "all") {
                        // match-case se ignora (todo en minúsculas), all = todo
                        continue
                    }
                    if (o.startsWith("domain=")) {
                        var m = 7
                        while (m < o.length) {
                            var f = o.indexOf('|', m)
                            if (f < 0) f = o.length
                            val d = o.substring(m, f)
                            m = f + 1
                            if (d.isEmpty()) continue
                            if (d.startsWith("~")) {
                                val dd = d.substring(1)
                                if (dd.isNotEmpty())
                                    (notDomains ?: ArrayList<String>(2).also { notDomains = it }).add(dd)
                            } else {
                                (domains ?: ArrayList<String>(2).also { domains = it }).add(d)
                            }
                        }
                        continue
                    }
                    // $denyallow=a|b: la regla NO aplica si el host pedido es a o b
                    if (o.startsWith("denyallow=")) {
                        var m = 10
                        while (m < o.length) {
                            var f = o.indexOf('|', m)
                            if (f < 0) f = o.length
                            val d = o.substring(m, f)
                            m = f + 1
                            if (d.isNotEmpty())
                                (denyHosts ?: ArrayList<String>(4).also { denyHosts = it }).add(d)
                        }
                        continue
                    }
                    // $to=a|b: la regla SOLO aplica si el host pedido es a o b
                    if (o.startsWith("to=")) {
                        var m = 3
                        while (m < o.length) {
                            var f = o.indexOf('|', m)
                            if (f < 0) f = o.length
                            val d = o.substring(m, f)
                            m = f + 1
                            if (d.isNotEmpty())
                                (toHosts ?: ArrayList<String>(4).also { toHosts = it }).add(d)
                        }
                        continue
                    }
                    if (o.startsWith("method=") || o.startsWith("csp=") ||
                        o.startsWith("redirect=") || o.startsWith("cookie=") ||
                        o.startsWith("header=") || o.startsWith("referrer=") ||
                        o.startsWith("replace=") || o.startsWith("removeparam=")) continue
                    if (o == "strict-third-party") { party = 1; continue }
                    if (o == "strict-first-party") { party = 2; continue }
                    if (o.startsWith("~")) {
                        val t = TYPE_BITS[o.substring(1)]
                        if (t != null) notTypes = notTypes or t
                        continue
                    }
                    val t = TYPE_BITS[o]
                    if (t != null) {
                        types = types or t
                    } else {
                        // tipo desconocido (ping, websocket…): la regla solo
                        // podrá aplicar a los tipos reconocidos que también
                        // liste; con SOLO desconocidos no aplica a nada
                        types = types or BIT_NEVER
                    }
                }
            }
        }

        // anclas y host
        var leftAnchor = false
        var hostAnchor = false
        var host: String? = null
        if (body.startsWith("||")) {
            hostAnchor = true
            body = body.substring(2)
            // host del patrón: hasta separador o comodín
            var e = 0
            while (e < body.length) {
                val ch = body[e]
                if (ch == '/' || ch == '^' || ch == '*' || ch == '?' ||
                    ch == '|' || ch == '$') break
                e++
            }
            val h = body.substring(0, e)
            if (h.contains('*') || h.isEmpty()) {
                hostAnchor = true; host = null  // comodines en el host: genérica
            } else {
                host = h
            }
        } else if (body.startsWith("|")) {
            leftAnchor = true
            body = body.substring(1)
        }
        var rightAnchor = false
        if (body.endsWith("|")) { rightAnchor = true; body = body.substring(0, body.length - 1) }
        if (body.isEmpty()) return false
        if (hostAnchor && host == null) {
            // recuperamos el || para que el matcher aplique la frontera de dominio
            body = "||$body"
        }

        val token = longestLiteral(body)
        val rule = Rule(body, host, hostAnchor, leftAnchor, rightAnchor, token,
            types, notTypes, party, domains, notDomains, denyHosts, toHosts,
            important, elemhide, generichide)
        networkRules++
        if (exception) {
            if (host != null) hostExc.getOrPut(host) { ArrayList(2) }.add(rule)
            else indexGeneric(genericExcTokens, genericExc, rule)
        } else {
            if (host != null) hostRules.getOrPut(host) { ArrayList(2) }.add(rule)
            else indexGeneric(genericTokens, genericRules, rule)
        }
        return true
    }

    /** Genéricas indexadas por token literal (si lo hay); sin token, a la lista plana. */
    private fun indexGeneric(tokens: HashMap<String, MutableList<Rule>>, plain: MutableList<Rule>, r: Rule) {
        if (r.token.length >= 3) tokens.getOrPut(r.token) { ArrayList(2) }.add(r)
        else plain.add(r)
    }

    /** Literal más largo entre comodines/separadores/anclas (para indexar). */
    private fun longestLiteral(p: String): String {
        var best = ""
        var i = 0
        while (i < p.length) {
            var j = i
            while (j < p.length) {
                val ch = p[j]
                if (ch == '*' || ch == '^' || ch == '|') break
                j++
            }
            if (j - i > best.length) best = p.substring(i, j)
            i = j + 1
        }
        return best
    }

    // ------------------------------------------------------------ matching
    private fun isSep(ch: Char): Boolean =
        !(ch in 'a'..'z' || ch in '0'..'9' || ch == '_' || ch == '-' ||
            ch == '.' || ch == '%')

    /** host de una URL http(s) en minúsculas, sin puerto. */
    fun hostOf(url: String): String {
        var s = url.indexOf("://")
        if (s < 0) s = 0 else s += 3
        var e = s
        val len = url.length
        while (e < len) {
            val ch = url[e]
            if (ch == '/' || ch == ':' || ch == '?' || ch == '#') break
            e++
        }
        if (e <= s) return ""
        return url.substring(s, e).lowercase()
    }

    /** true si es tercera parte (hosts de dominios distintos). Aproximación
     *  sin lista de sufijos públicos: igualdad de dominio base (últimos dos
     *  etiquetas) o relación de subdominio directo = primera parte. Así,
     *  cdn.self.com y www.self.com son la misma sitio. */
    private fun firstParty(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        if (a.endsWith(".$b") || b.endsWith(".$a")) return true
        return baseHost(a) == baseHost(b)
    }

    /** Últimas dos etiquetas del host (aproximación de eTLD+1). */
    private fun baseHost(h: String): String {
        val first = h.indexOf('.')
        if (first < 0) return h
        val second = h.indexOf('.', first + 1)
        return if (second < 0) h else h.substring(first + 1)
    }

    /** Claves de dominio candidatas para un host: el host y sus dominios padre. */
    private fun candidates(host: String): ArrayList<String> {
        val out = ArrayList<String>(6)
        var h = host
        out.add(h)
        while (true) {
            val dot = h.indexOf('.')
            if (dot <= 0) break
            h = h.substring(dot + 1)
            if (h.contains('.')) out.add(h) else { if (h.isNotEmpty()) out.add(h); break }
        }
        return out
    }

    /** Coincidencia de patrón con comodines *, separador ^ y ancla izquierda.
     *  Sin regex: backtracking clásico de dos punteros. */
    private fun matchPat(p: String, url: String, leftAnchor: Boolean): Boolean {
        var start = 0
        val plen = p.length
        val ulen = url.length
        while (start <= ulen) {
            if (matchCore(p, 0, url, start, false)) return true
            if (leftAnchor) return false
            start++
            if (start > ulen - plen && plen > 0) break
        }
        return false
    }

    /**
     * Núcleo de coincidencia: patrón desde pi contra url desde ui, con
     * comodín * y separador ^ (que además casa con el FINAL de la URL, como
     * en EasyList). full=false casa como prefijo (patrón consumido basta);
     * full=true exige consumir también el final de la URL (ancla derecha).
     */
    private fun matchCore(p: String, pi0: Int, url: String, ui0: Int, full: Boolean): Boolean {
        var pi = pi0
        var ui = ui0
        var starP = -1
        var starU = -1
        val plen = p.length
        val ulen = url.length
        while (ui < ulen) {
            if (pi < plen) {
                val pc = p[pi]
                if (pc == '*') {
                    starP = pi; starU = ui; pi++; continue
                }
                val ok = if (pc == '^') isSep(url[ui]) else pc == url[ui]
                if (ok) { pi++; ui++; continue }
            } else {
                // patrón consumido
                if (!full) return true
                if (starP == pi - 1) return true   // '*' final absorbe el resto
                return false
            }
            if (starP >= 0) {
                starU++
                ui = starU
                pi = starP + 1
                continue
            }
            return false
        }
        // URL consumida: los ^ y * restantes casan con el final de la URL
        while (pi < plen && (p[pi] == '^' || p[pi] == '*')) pi++
        return pi == plen
    }

    /** Coincidencia de una regla (sin el chequeo de tipo/party, que hace el llamador). */
    private fun ruleMatches(r: Rule, url: String, urlHost: String, pageHost: String): Boolean {
        // $domain= : el host de la PÁGINA debe estar incluido y no excluido
        if (r.domains != null) {
            var ok = false
            for (d in r.domains) {
                if (pageHost == d || pageHost.endsWith(".$d")) { ok = true; break }
            }
            if (!ok) return false
        }
        if (r.notDomains != null) {
            for (d in r.notDomains) {
                if (pageHost == d || pageHost.endsWith(".$d")) return false
            }
        }
        // $denyallow=: la regla no aplica si el host del RECURSO está excluido
        if (r.denyHosts != null) {
            for (d in r.denyHosts) {
                if (urlHost == d || urlHost.endsWith(".$d")) return false
            }
        }
        // $to=: la regla solo aplica si el host del RECURSO está en la lista
        if (r.toHosts != null) {
            var ok = false
            for (d in r.toHosts) {
                if (urlHost == d || urlHost.endsWith(".$d")) { ok = true; break }
            }
            if (!ok) return false
        }
        if (r.host != null) {
            // ||host: el host de la URL debe ser host o subdominio, y el
            // patrón completo casa a partir de esa posición
            if (!(urlHost == r.host || urlHost.endsWith("." + r.host))) return false
            val schemeEnd = url.indexOf("://")
            val hStart = if (schemeEnd >= 0) schemeEnd + 3 else 0
            // posición donde empieza r.host dentro de urlHost
            val hPos = hStart + (urlHost.length - r.host.length)
            if (hPos < 0 || hPos > url.length) return false
            return matchCore(r.pattern, 0, url, hPos, false)
        }
        // genérica
        if (r.leftAnchor) {
            return matchCore(r.pattern, 0, url, 0, r.rightAnchor)
        }
        return if (r.rightAnchor) {
            matchToEnd(r.pattern, url)
        } else {
            matchPat(r.pattern, url, false)
        }
    }

    /** patrón con ancla derecha: debe casar consumiendo hasta el final. */
    private fun matchToEnd(p: String, url: String): Boolean {
        val ulen = url.length
        var s = 0
        while (s <= ulen) {
            if (matchCore(p, 0, url, s, true)) return true
            s++
        }
        return false
    }

    // --------------------------------------------------------- API pública
    /** typeOf de la app a bits. */
    fun typeBit(type: String): Int = TYPE_BITS[type] ?: 0

    /**
     * true si la URL debe bloquearse. Orden de decisión (EasyList):
     * $important de bloqueo > excepción @@ > regla de bloqueo.
     */
    fun shouldBlock(url: String, pageUrl: String, type: String): Boolean = decide(url, pageUrl, type) != null

    /** null = no bloquear; si bloquea, la regla responsable (uso interno). */
    private fun decide(url: String, pageUrl: String, type: String): Rule? {
        // contrato de la app: solo http/https en página y recurso
        if (!pageUrl.startsWith("http://") && !pageUrl.startsWith("https://")) return null
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        val u = url.lowercase()
        val urlHost = hostOf(u)
        val pageHost = hostOf(pageUrl)
        val tb = TYPE_BITS[type] ?: 0
        val party = if (firstParty(urlHost, pageHost)) 2 else 1

        var block: Rule? = null
        var exc: Rule? = null

        // reglas indexadas por host
        for (key in candidates(urlHost)) {
            val lst = hostRules[key]
            if (lst != null) for (r in lst) {
                if (ruleFits(r, tb, party, pageHost) && ruleMatches(r, u, urlHost, pageHost)) {
                    if (r.important) return r
                    block = r
                }
            }
            val ex = hostExc[key]
            if (ex != null) for (r in ex) {
                if (ruleFits(r, tb, party, pageHost) && ruleMatches(r, u, urlHost, pageHost)) {
                    exc = r
                }
            }
        }
        // genéricas (por token) — solo si aún no hay decisión de desbloqueo
        if (block == null || exc == null) {
            scanGeneric(genericExcTokens, genericExc, u, tb, party, pageHost, urlHost) {
                if (exc == null) exc = it
            }
            scanGeneric(genericTokens, genericRules, u, tb, party, pageHost, urlHost) {
                if (it.important) return it
                if (block == null) block = it
            }
        }
        val blk = block
        if (blk != null && blk.important) return blk
        if (exc != null) return null
        return blk
    }

    /** Diagnóstico: patrón de la regla que bloquearía la URL (o "excepcion"/"-"). */
    fun debugMatch(url: String, pageUrl: String, type: String): String {
        val r = decide(url, pageUrl, type)
        return if (r == null) "-" else r.pattern
    }

    /** Diagnóstico: nº de dominios indexados de reglas ||host. */
    fun hostRulesCount(): Int = hostRules.size

    /** Diagnóstico: nº de genéricas (planas + por token). */
    fun genericCount(): Int = genericRules.size + genericTokens.size

    private inline fun scanGeneric(
        tokens: HashMap<String, MutableList<Rule>>, plain: List<Rule>,
        u: String, tb: Int, party: Int, pageHost: String, urlHost: String,
        onMatch: (Rule) -> Unit
    ) {
        for ((tok, lst) in tokens) {
            if (tok.length > u.length) continue
            if (!u.contains(tok)) continue
            for (r in lst) {
                if (ruleFits(r, tb, party, pageHost) && ruleMatches(r, u, urlHost, pageHost)) onMatch(r)
            }
        }
        for (r in plain) {
            if (ruleFits(r, tb, party, pageHost) && ruleMatches(r, u, urlHost, pageHost)) onMatch(r)
        }
    }

    /** chequeos baratos (tipo/party/dominios) antes del matching caro.
     *  tb = bit del tipo de recurso; tb < 0 = indiferente (hiding flags). */
    private fun ruleFits(r: Rule, tb: Int, party: Int, pageHost: String): Boolean {
        if (tb >= 0) {
            if (r.types != 0 && (tb == 0 || (r.types and tb) == 0)) return false
            if (tb != 0 && (r.notTypes and tb) != 0) return false
        }
        if (r.party != 0 && r.party != party) return false
        return true
    }

    /** Excepciones de ocultación activas para una página ($elemhide/$generichide). */
    private fun hidingFlags(pageUrl: String): BooleanArray {
        val u = pageUrl.lowercase()
        val urlHost = hostOf(u)
        val pageHost = urlHost
        var eh = false; var gh = false
        for (key in candidates(urlHost)) {
            val ex = hostExc[key]
            if (ex != null) for (r in ex) {
                if ((r.elemhide || r.generichide) && ruleMatches(r, u, urlHost, pageHost)) {
                    if (r.elemhide) eh = true
                    if (r.generichide) gh = true
                }
            }
        }
        scanGeneric(genericExcTokens, genericExc, u, -1, 0, pageHost, urlHost) {
            if (it.elemhide) eh = true
            if (it.generichide) gh = true
        }
        return booleanArrayOf(eh, gh)
    }

    /** Excepciones #@# para una página (selectores que NO se ocultan). */
    private fun hideExceptionsFor(pageHost: String, out: HashSet<String>) {
        var h = pageHost
        while (true) {
            domHideExc[h]?.let { out.addAll(it) }
            val dot = h.indexOf('.')
            if (dot <= 0) break
            h = h.substring(dot + 1)
        }
        out.addAll(genHideExc)
    }

    /**
     * CSS de ocultación para la página. Una regla por selector:
     * `selector{display:none!important}`. Siempre respeta #@# y las
     * excepciones $elemhide/$generichide; los selectores genéricos se
     * añaden solo si caben dentro de maxCss.
     */
    fun cssFor(pageUrl: String, maxCss: Int): String {
        val flags = hidingFlags(pageUrl)
        if (flags[0]) return "" // $elemhide: nada de ocultación en esta página
        val pageHost = hostOf(pageUrl)
        val exc = HashSet<String>(16)
        hideExceptionsFor(pageHost, exc)
        val seen = HashSet<String>(256)
        val sb = StringBuilder(4096)
        fun add(sel: String): Boolean {
            if (sel in seen || sel in exc) return true
            seen.add(sel)
            if (sb.length + sel.length + 24 > maxCss) return false
            sb.append(sel).append("{display:none!important}\n")
            return true
        }
        var h = pageHost
        while (true) {
            domHide[h]?.let { lst -> for (s in lst) if (!add(s)) return sb.toString() }
            val dot = h.indexOf('.')
            if (dot <= 0) break
            h = h.substring(dot + 1)
        }
        if (!flags[1]) { // $generichide: solo el cosmético de dominio
            for (s in genHide) if (!add(s)) break
        }
        return sb.toString()
    }
}
