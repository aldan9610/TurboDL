package com.turbodl.util

import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.experimental.and

/**
 * OTP (4.6): generación de códigos TOTP (RFC 6238) y HOTP (RFC 4226) en
 * Kotlin puro, sin dependencias externas. Compatible con Google
 * Authenticator, KeePassXC, Aegis, 1Password, Authy, etc.
 *
 * ### Algoritmos
 * - **TOTP** (RFC 6238): código basado en el tiempo. `counter = floor(unixTime / period)`.
 *   Por defecto 30s, 6 dígitos, HMAC-SHA1 (lo estándar de Google Auth).
 * - **HOTP** (RFC 4226): código basado en contador. Cada uso incrementa
 *   el contador; no dependiente del tiempo.
 *
 * ### Codificación del secret
 * La convención universal (RFC 6238 §4.2 y la práctica de facto de
 * Google Authenticator) es **Base32** (sin padding, case-insensitive),
 * ya que la mayoría de sitios muestran el secret en Base32. También
 * soportamos:
 *   - Hex (uso de KeePassXC opcional).
 *   - Texto plano (RFC 6238 original; poco común).
 * Se detecta automáticamente por prefijo (`base32:`, `hex:`, `plain:`) o
 * se asume Base32 si no hay prefijo (lo más habitual).
 *
 * ### Optimización de recursos (tip del usuario)
 * La generación es barata (un HMAC de 64 bytes + un truncado), pero el
 * usuario pidió explícitamente NO recalcular OTP continuamente cuando
 * hay muchas credenciales. Por eso esta clase es **stateless**: no
 * programa timers ni recorre la bóveda. La UI ([VaultActivity]) pide el
 * código solo cuando lo muestra, y re-programa el recálculo solo en el
 * límite del step (cada 30s por defecto) — no cada tick. Ver
 * [computeOtp] y [nextStepIn].
 *
 * ### Ejemplo
 * ```
 * val secret = "JBSWY3DPEHPK3PXP"  // Base32
 * val code = Otp.computeOtp(secret)  // "123456" (cambia cada 30s)
 * val secs = Otp.nextStepIn(secret)  // 18 (segundos hasta el siguiente código)
 * ```
 */
object Otp {

    /** Algoritmo HMAC soportados. SHA1 es el estándar de facto. */
    enum class Algo(val hmacName: String, val jceName: String) {
        SHA1("HmacSHA1", "SHA1"),
        SHA256("HmacSHA256", "SHA256"),
        SHA512("HmacSHA512", "SHA512");

        companion object {
            /** "SHA1"/"SHA256"/"SHA512" → [Algo]. SHA1 por defecto. */
            fun of(s: String?): Algo = when (s?.trim()?.uppercase()) {
                "SHA256" -> SHA256
                "SHA512" -> SHA512
                else -> SHA1
            }
        }
    }

    /** Tipo de código. */
    enum class Kind { TOTP, HOTP }

    /**
     * Configuración OTP de una entrada de la bóveda. No guarda el secret
     * (el secret viaja aparte, descifrado bajo demanda desde la BD).
     *
     * @param kind      TOTP o HOTP.
     * @param algo       HMAC a usar.
     * @param digits     6 u 8 (6 por defecto; Steam Guard usa 5).
     * @param period     Segundos por step TOTP (30 por defecto).
     * @param counter    Contador HOTP (0 al añadir; se incrementa al usar).
     * @param steam      true para formato Steam Guard (5 dígitos alfanuméricos).
     */
    data class Cfg(
        val kind: Kind = Kind.TOTP,
        val algo: Algo = Algo.SHA1,
        val digits: Int = 6,
        val period: Int = 30,
        val counter: Long = 0L,
        val steam: Boolean = false
    ) {
        companion object {
            /** Serializa a texto compacto "kind|algo|digits|period|counter|steam".
             *  Las entradas sin OTP guardan "" (vacío) en la BD. */
            fun parse(s: String?): Cfg? {
                if (s.isNullOrBlank()) return null
                val p = s.split("|")
                if (p.size != 6) return Cfg()  // tolerante con formatos antiguos
                return try {
                    Cfg(
                        kind = if (p[0] == "hotp") Kind.HOTP else Kind.TOTP,
                        algo = Algo.of(p[1]),
                        digits = p[2].toInt(),
                        period = p[3].toInt(),
                        counter = p[4].toLong(),
                        steam = p[5] == "1"
                    )
                } catch (e: Throwable) { Cfg() }
            }
        }

        /** Serializa a texto compacto "kind|algo|digits|period|counter|steam".
         *  Las entradas sin OTP guardan "" (vacío) en la BD. */
        fun encode(): String =
            "${if (kind == Kind.HOTP) "hotp" else "totp"}|${algo.jceName}|" +
            "$digits|$period|$counter|${if (steam) "1" else "0"}"
    }

    /**
     * Calcula el código OTP actual.
     *
     * - TOTP: counter = `floor(now / period)`.
     * - HOTP: counter = `cfg.counter`.
     *
     * @param secretRaw el secret tal cual lo introdujo el usuario
     *   (base32 por defecto, o prefijado `base32:`/`hex:`/`plain:`).
     * @param cfg configuración del código.
     * @return el código como String (dígitos, o alfanumérico si Steam),
     *   o null si el secret no se pudo decodificar.
     */
    fun computeOtp(secretRaw: String, cfg: Cfg): String? {
        val key = decodeSecret(secretRaw) ?: return null
        val counter = if (cfg.kind == Kind.TOTP)
            (System.currentTimeMillis() / 1000L) / cfg.period.coerceAtLeast(1)
        else cfg.counter
        val hmac = hmac(key, counter, cfg.algo) ?: return null
        val code = if (cfg.steam) steamCode(hmac, cfg.digits)
                   else truncCode(hmac, cfg.digits)
        return code
    }

    /** Conveniencia TOTP con defaults (6 dígitos, 30s, SHA1). */
    fun computeOtp(secretRaw: String): String? = computeOtp(secretRaw, Cfg())

    /**
     * Segundos restantes del step TOTP actual (0..period-1). Útil para
     * la barra de progreso y para programar el recálculo justo en el
     * límite del step (no cada tick). 0 si es HOTP.
     */
    fun nextStepIn(cfg: Cfg): Int {
        if (cfg.kind == Kind.HOTP) return 0
        val p = cfg.period.coerceAtLeast(1)
        return p - ((System.currentTimeMillis() / 1000L) % p).toInt()
    }

    /** Segundos restantes del step para un secret con config por defecto. */
    fun nextStepIn(secretRaw: String, cfg: Cfg = Cfg()): Int = nextStepIn(cfg)

    // ---------------- bajo nivel ----------------

    /**
     * Decodifica el secret a bytes. Convenciones:
     *   - sin prefijo → Base32 (lo estándar; tolera espacios y minúsculas).
     *   - `base32:` → Base32 explícito.
     *   - `hex:` → hexadecimal.
     *   - `plain:` → texto plano UTF-8 (RFC 6238 original).
     */
    private fun decodeSecret(raw: String): ByteArray? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        return when {
            s.startsWith("base32:", true) -> base32Decode(s.substring(7))
            s.startsWith("hex:", true) -> hexDecode(s.substring(4))
            s.startsWith("plain:", true) -> s.substring(6).toByteArray(Charsets.UTF_8)
            else -> base32Decode(s)  // por defecto: Base32
        }
    }

    /** HMAC del counter (big-endian 8 bytes) con el key. */
    private fun hmac(key: ByteArray, counter: Long, algo: Algo): ByteArray? {
        return try {
            val mac = Mac.getInstance(algo.hmacName)
            mac.init(SecretKeySpec(key, algo.hmacName))
            val buf = ByteArray(8)
            var c = counter
            for (i in 7 downTo 0) { buf[i] = (c and 0xFF).toByte(); c = c ushr 8 }
            mac.doFinal(buf)
        } catch (e: NoSuchAlgorithmException) { null }
        catch (e: Throwable) { null }
    }

    /** Truncado dinámico RFC 4226 §5.3 + módulo 10^digits. */
    private fun truncCode(hmac: ByteArray, digits: Int): String {
        val offset = (hmac[hmac.size - 1] and 0x0F).toInt()
        val bin = (((hmac[offset].toInt() and 0x7F) shl 24) or
                   ((hmac[offset + 1].toInt() and 0xFF) shl 16) or
                   ((hmac[offset + 2].toInt() and 0xFF) shl 8) or
                    (hmac[offset + 3].toInt() and 0xFF)) and 0x7FFFFFFF
        val mod = pow10(digits.coerceIn(4, 10))
        val n = bin % mod
        return n.toString().padStart(digits.coerceIn(4, 10), '0')
    }

    /** Código Steam Guard: 5 dígitos alfanuméricos (base26 sobre un
     *  alfabeto propio). Compatible con Aegis/keepassxc. */
    private fun steamCode(hmac: ByteArray, digits: Int): String {
        val chars = "23456789BCDFGHJKMNPQRTVWXY"
        val offset = (hmac[hmac.size - 1] and 0x0F).toInt()
        var fc = (((hmac[offset].toInt() and 0x7F) shl 24) or
                  ((hmac[offset + 1].toInt() and 0xFF) shl 16) or
                  ((hmac[offset + 2].toInt() and 0xFF) shl 8) or
                   (hmac[offset + 3].toInt() and 0xFF)) and 0x7FFFFFFF
        val sb = StringBuilder()
        val n = digits.coerceAtLeast(5)
        for (i in 0 until n) {
            sb.append(chars[fc % chars.length])
            fc /= chars.length
        }
        return sb.toString()
    }

    private fun pow10(n: Int): Int {
        var r = 1
        repeat(n) { r *= 10 }
        return r
    }

    // ---------------- Base32 ----------------

    private const val B32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    /** Decodificación Base32 (RFC 4648) sin padding; tolera espacios,
     *  minúsculas y `=` de padding. Devuelve null si hay caracteres
     *  inválidos. */
    fun base32Decode(s: String): ByteArray? {
        val cleaned = s.trim().replace(" ", "").replace("-", "").uppercase()
        if (cleaned.isEmpty()) return null
        val out = java.io.ByteArrayOutputStream()
        var bits = 0
        var value = 0
        for (ch in cleaned) {
            if (ch == '=') continue
            val idx = B32_ALPHABET.indexOf(ch)
            if (idx < 0) return null
            value = (value shl 5) or idx
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.write((value ushr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }

    /** Codificación Base32 (sin padding). Útil para mostrar/validar. */
    fun base32Encode(b: ByteArray): String {
        if (b.isEmpty()) return ""
        val sb = StringBuilder()
        var bits = 0
        var value = 0
        for (byte in b) {
            value = (value shl 8) or (byte.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                sb.append(B32_ALPHABET[(value ushr bits) and 0x1F])
            }
        }
        if (bits > 0) sb.append(B32_ALPHABET[(value shl (5 - bits)) and 0x1F])
        return sb.toString()
    }

    /** Hex → bytes. Tolerante a mayúsculas/minúsculas. */
    private fun hexDecode(s: String): ByteArray? {
        val cleaned = s.trim().replace(" ", "").replace(":", "")
        if (cleaned.length % 2 != 0) return null
        val out = ByteArray(cleaned.length / 2)
        for (i in out.indices) {
            val hi = hexVal(cleaned[i * 2]) ?: return null
            val lo = hexVal(cleaned[i * 2 + 1]) ?: return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun hexVal(c: Char): Int? = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> null
    }

    /**
     * Valida que un secret sea decodificable (para dar feedback al
     * usuario en el diálogo de la bóveda antes de guardar).
     */
    fun isValidSecret(raw: String): Boolean = decodeSecret(raw) != null

    /**
     * Detecta si una cadena parece un otpauth URI (RFC 6238 §5) y lo
     * descompone en (cfg, secret, label). Soporta:
     *   otpauth://totp/Label?secret=XXX&digits=6&period=30&algorithm=SHA1
     *   otpauth://hotp/Label?secret=XXX&counter=0&digits=6&algorithm=SHA1
     * Devuelve null si no es un otpauth URI válido. Esto permite al
     * usuario pegar el QR/URI que le da cualquier sitio.
     */
    fun parseOtpAuthUri(uri: String): Triple<Cfg, String, String>? {
        if (!uri.startsWith("otpauth://", true)) return null
        try {
            val after = uri.substring("otpauth://".length)
            val slash = after.indexOf('/')
            val q = after.indexOf('?')
            if (slash < 0 || q < 0) return null
            val type = after.substring(0, slash).lowercase()
            val label = java.net.URLDecoder.decode(after.substring(slash + 1, q), "UTF-8")
            val params = after.substring(q + 1).split("&")
                .associate {
                    val kv = it.split("=", limit = 2)
                    (kv[0].lowercase() to (if (kv.size > 1) kv[1] else ""))
                }
            val secret = params["secret"] ?: return null
            val digits = params["digits"]?.toIntOrNull() ?: 6
            val period = params["period"]?.toIntOrNull() ?: 30
            val counter = params["counter"]?.toLongOrNull() ?: 0L
            val algo = Algo.of(params["algorithm"])
            val issuer = params["issuer"]
            val steam = issuer.equals("steam", true) ||
                label.contains("steam", true)
            val kind = if (type == "hotp") Kind.HOTP else Kind.TOTP
            return Triple(Cfg(kind, algo, digits, period, counter, steam), secret,
                if (issuer != null && label.isNotBlank()) "$issuer: $label" else label)
        } catch (e: Throwable) { return null }
    }
}
