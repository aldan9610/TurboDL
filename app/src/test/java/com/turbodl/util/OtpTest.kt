package com.turbodl.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de Otp (4.6): validan la implementación TOTP/HOTP contra los
 * vectores oficiales del RFC 6238 (Apéndice B) y del RFC 4226 (Apéndice D),
 * más Base32 y parsing de otpauth URIs.
 *
 * ### Vector RFC 6238 (TOTP-SHA1, 8 dígitos, 30s, secret "12345678901234567890" en ASCII)
 *   T=59       → 94287082
 *   T=1111111109 → 07081804
 *   T=1111111111 → 14050471
 *   T=1234567890 → 89005924
 *   T=2000000000 → 69279037
 *   T=20000000000 → 65353130
 *
 * El secret del RFC está en texto plano (no Base32), así que lo
 * pasamos con el prefijo "plain:".
 */
class OtpTest {

    private val rfcSecret = "plain:12345678901234567890"  // ASCII, 20 bytes

    @Test
    fun `TOTP RFC 6238 vector T=59 da 94287082`() {
        // Forzamos el tiempo a T=59s (counter = 59/30 = 1).
        val cfg = Otp.Cfg(kind = Otp.Kind.TOTP, algo = Otp.Algo.SHA1,
            digits = 8, period = 30)
        val code = computeAtTime(59_000L, rfcSecret, cfg)
        assertEquals("94287082", code)
    }

    @Test
    fun `TOTP RFC 6238 vector T=1111111109 da 07081804`() {
        val cfg = Otp.Cfg(kind = Otp.Kind.TOTP, algo = Otp.Algo.SHA1,
            digits = 8, period = 30)
        assertEquals("07081804", computeAtTime(1_111_111_109_000L, rfcSecret, cfg))
    }

    @Test
    fun `TOTP RFC 6238 vector T=1234567890 da 89005924`() {
        val cfg = Otp.Cfg(kind = Otp.Kind.TOTP, algo = Otp.Algo.SHA1,
            digits = 8, period = 30)
        assertEquals("89005924", computeAtTime(1_234_567_890_000L, rfcSecret, cfg))
    }

    @Test
    fun `TOTP RFC 6238 con SHA256 da valor estable por step`() {
        // El RFC 6238 Appendix B usa "12345678901234567890123456789012" para SHA256.
        val s = "plain:12345678901234567890123456789012"
        val cfg = Otp.Cfg(kind = Otp.Kind.TOTP, algo = Otp.Algo.SHA256,
            digits = 8, period = 30)
        // T=59 con SHA256 → 46119246 (vector oficial)
        assertEquals("46119246", computeAtTime(59_000L, s, cfg))
    }

    @Test
    fun `HOTP RFC 4226 vector contador 0 da 755224`() {
        // RFC 4226 Apéndice D: secret "12345678901234567890" (ASCII),
        // contador 0 → 755224, contador 1 → 287082, etc.
        val cfg = Otp.Cfg(kind = Otp.Kind.HOTP, algo = Otp.Algo.SHA1,
            digits = 6, period = 30, counter = 0)
        assertEquals("755224", Otp.computeOtp(rfcSecret, cfg))
    }

    @Test
    fun `HOTP RFC 4226 vector contador 1 da 287082`() {
        val cfg = Otp.Cfg(kind = Otp.Kind.HOTP, algo = Otp.Algo.SHA1,
            digits = 6, period = 30, counter = 1)
        assertEquals("287082", Otp.computeOtp(rfcSecret, cfg))
    }

    @Test
    fun `Base32 decodifica y recodifica correctamente`() {
        val raw = "JBSWY3DPEHPK3PXP"  // "Hello!\xde\xad\xbe\xef" en Base32
        val bytes = Otp.base32Decode(raw)
        assertNotNull(bytes)
        assertEquals(raw, Otp.base32Encode(bytes!!))
    }

    @Test
    fun `Base32 tolera espacios minisculas y padding`() {
        val a = Otp.base32Decode("JBSWY3DP")
        val b = Otp.base32Decode("jbsw y3dp==")
        assertNotNull(a); assertNotNull(b)
        assertEquals(a!!.toList(), b!!.toList())
    }

    @Test
    fun `Base32 rechaza caracteres invalidos`() {
        assertNull(Otp.base32Decode("INVALID!@#"))
    }

    @Test
    fun `otpauth URI TOTP se parsea correctamente`() {
        val uri = "otpauth://totp/GitHub:aldan9610?secret=JBSWY3DPEHPK3PXP&issuer=GitHub&digits=6&period=30&algorithm=SHA1"
        val r = Otp.parseOtpAuthUri(uri)
        assertNotNull(r)
        val (cfg, secret, label) = r!!
        assertEquals(Otp.Kind.TOTP, cfg.kind)
        assertEquals(Otp.Algo.SHA1, cfg.algo)
        assertEquals(6, cfg.digits)
        assertEquals(30, cfg.period)
        assertEquals("JBSWY3DPEHPK3PXP", secret)
        assertTrue(label.contains("GitHub"))
    }

    @Test
    fun `otpauth URI HOTP respeta el contador`() {
        val uri = "otpauth://hotp/MiCuenta?secret=JBSWY3DP&counter=42&digits=6"
        val r = Otp.parseOtpAuthUri(uri)
        assertNotNull(r)
        val (cfg, secret, _) = r!!
        assertEquals(Otp.Kind.HOTP, cfg.kind)
        assertEquals(42L, cfg.counter)
        assertEquals("JBSWY3DP", secret)
    }

    @Test
    fun `Cfg encode y parse son inversos`() {
        val cfg = Otp.Cfg(kind = Otp.Kind.HOTP, algo = Otp.Algo.SHA256,
            digits = 8, period = 60, counter = 123, steam = true)
        val s = cfg.encode()
        val back = Otp.Cfg.parse(s)
        assertNotNull(back)
        assertEquals(cfg.kind, back!!.kind)
        assertEquals(cfg.algo, back.algo)
        assertEquals(cfg.digits, back.digits)
        assertEquals(cfg.period, back.period)
        assertEquals(cfg.counter, back.counter)
        assertEquals(cfg.steam, back.steam)
    }

    @Test
    fun `parse de cfg vacia o null devuelve null`() {
        assertNull(Otp.Cfg.parse(null))
        assertNull(Otp.Cfg.parse(""))
        assertNull(Otp.Cfg.parse("   "))
    }

    @Test
    fun `isValidSecret valida Base32 y plain`() {
        assertTrue(Otp.isValidSecret("JBSWY3DPEHPK3PXP"))
        assertTrue(Otp.isValidSecret("plain:hola"))
        assertTrue(Otp.isValidSecret("hex:deadbeef"))
        assertFalse(Otp.isValidSecret(""))
        assertFalse(Otp.isValidSecret("base32:!!!invalid"))
    }

    @Test
    fun `nextStepIn devuelve entre 1 y period`() {
        val cfg = Otp.Cfg(kind = Otp.Kind.TOTP, period = 30)
        val s = Otp.nextStepIn(cfg)
        assertTrue(s in 1..30)
    }

    @Test
    fun `HOTP nextStepIn devuelve 0`() {
        val cfg = Otp.Cfg(kind = Otp.Kind.HOTP, period = 30)
        assertEquals(0, Otp.nextStepIn(cfg))
    }

    // --- helper: fija el reloj del sistema a un momento dado ---
    // Otp usa System.currentTimeMillis(), así que para los vectores
    // del RFC inyectamos el momento y comprobamos. Como no podemos
    // mockear System.currentTimeMillis sin librerías, validamos que
    // para un counter fijo (vía HOTP) el resultado sea el esperado,
    // y para TOTP usamos el valor en el segundo justo del step.
    private fun computeAtTime(unixMillis: Long, secret: String, cfg: Otp.Cfg): String {
        // El counter TOTP es floor(now/period). Reutilizamos el motor
        // HOTP forzando el counter = floor(unixMillis/1000)/period.
        val counter = (unixMillis / 1000L) / cfg.period.coerceAtLeast(1)
        val hotpCfg = Otp.Cfg(kind = Otp.Kind.HOTP, algo = cfg.algo,
            digits = cfg.digits, period = cfg.period, counter = counter,
            steam = cfg.steam)
        return Otp.computeOtp(secret, hotpCfg) ?: ""
    }
}
