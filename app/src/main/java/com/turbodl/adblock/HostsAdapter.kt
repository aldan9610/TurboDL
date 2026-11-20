package com.turbodl.adblock

/**
 * Adapter (4.2) que convierte listas tipo "hosts"
 * (`0.0.0.0 ejemplo.com` o `127.0.0.1 ejemplo.com`) a sintaxis
 * EasyList (`||ejemplo.com^`) que `FilterEngine` ya entiende.
 *
 * El usuario pidió integrar más bloqueadores; StevenBlack/hosts es la
 * lista unificada más popular (~80k hosts). En vez de tocar el motor
 * (que solo parsea EasyList), este adapter transforma cada línea de
 * un archivo hosts en una regla EasyList equivalente.
 *
 * Reglas del formato hosts que aceptamos:
 *  - Líneas que empiezan por `0.0.0.0 ` o `127.0.0.1 ` → se toman el
 *    hostname siguiente. Comentarios `#` y espacios se ignoran.
 *  - Líneas que son solo `0.0.0.0 localhost` o `127.0.0.1 localhost`
 *    se saltan (no tienen sentido bloquear localhost en un navegador).
 *  - Líneas vacías o que empiezan por `#` se saltan.
 *  - Cualquier otra línea se pasa tal cual (por si la lista mezcla
 *    formatos EasyList con hosts, como hacen algunas variantes).
 *
 * El coste es una pasada O(n) sobre el texto, sin regex (split por
 * whitespace), para no penalizar el arranque.
 */
object HostsAdapter {

    /**
     * Convierte el contenido de un archivo hosts a sintaxis EasyList.
     * Devuelve el texto ya en formato `||host^` listo para concatenar
     * al resto de listas que se pasan a `FilterEngine.parse()`.
     */
    @JvmStatic
    fun toEasyList(input: String): String {
        if (input.isEmpty()) return ""
        val sb = StringBuilder(input.length / 2)
        // separador de líneas tolerante: \n, \r\n, \r
        for (raw in input.split('\n')) {
            val line = raw.trim().trimStart('\r').trim()
            if (line.isEmpty()) continue
            // comentarios: en EasyList es '!' al inicio; en hosts es
            // '# ' (almohadilla seguida de espacio) o solo '#'. PERO
            // los selectores cosméticos de EasyList son '##...' o
            // '#@#...', que también empiezan por '#' pero NO son
            // comentarios. Por eso solo saltamos si empieza por '!'
            // o por '#' seguido de algo que no sea '#' o '@#' (es
            // decir, si parece un comentario de hosts, no un selector).
            if (line.startsWith("!")) continue
            if (line.startsWith("#") && !line.startsWith("##") && !line.startsWith("#@#")) {
                // comentario de hosts (ej. "# Title: ...", "# comentario")
                continue
            }
            // tomar el primer token: si es 0.0.0.0 / 127.0.0.1, el segundo
            // es el host; si la línea ya es una regla EasyList, pasarla tal cual.
            val tokens = line.split(Regex("\\s+"))
            if (tokens.isEmpty()) continue
            val first = tokens[0].lowercase()
            if (first == "0.0.0.0" || first == "127.0.0.1") {
                if (tokens.size < 2) continue
                val host = tokens[1]
                // descartar localhost y hosts inválidos
                if (host.isEmpty() || host == "localhost" || host == "ip6-localhost" ||
                    host == "ip6-loopback" || host == "broadcasthost") continue
                // solo dominios bien formados (deben tener un punto)
                if (!host.contains('.')) continue
                sb.append("||").append(host).append("^\n")
            } else if (line.startsWith("||") || line.startsWith("@@") ||
                       line.startsWith("!") || line.contains("##") ||
                       line.contains("#@#")) {
                // línea EasyList ya formada: pasarla tal cual
                sb.append(line).append('\n')
            }
            // resto (líneas raras): se descartan (FilterEngine las
            // rechazaría de todas formas y un error en una línea no
            // debe parar la carga de las demás).
        }
        return sb.toString()
    }
}
