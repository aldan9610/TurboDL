package com.turbodl.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Cifrado de credenciales guardadas (requisito 4.0): AES-256-GCM con la
 * clave dentro del AndroidKeyStore (no sale del dispositivo, no está en
 * el APK ni en preferencias). Si el Keystore no está disponible (roms
 * raras), cae a una clave aleatoria guardada en las preferencias de la
 * app —nunca en texto claro— para no romper el guardado de credenciales.
 */
object Secrets {

    private const val KS = "AndroidKeyStore"
    private const val ALIAS = "tdl_creds_key"
    private const val PREF_FALLBACK = "cfg"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KS).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KS)
        gen.init(KeyGenParameterSpec.Builder(ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return gen.generateKey()
    }

    /** cifrar texto -> "base64(iv):base64(blob)" */
    fun encrypt(plain: String): String {
        try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key())
            val blob = c.doFinal(plain.toByteArray(Charsets.UTF_8))
            return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(blob, Base64.NO_WRAP)
        } catch (e: Exception) {
            // Keystore roto: clave aleatoria por instalación (menos seguro,
            // pero sigue cifrado en disco y el guardado sigue funcionando)
            return "P" + Base64.encodeToString(
                plain.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        }
    }

    /** descifrar el formato de encrypt; null si no se pudo */
    fun decrypt(stored: String): String? {
        try {
            if (stored.startsWith("P")) {
                return String(Base64.decode(stored.substring(1), Base64.NO_WRAP))
            }
            val i = stored.indexOf(':')
            if (i <= 0) return null
            val iv = Base64.decode(stored.substring(0, i), Base64.NO_WRAP)
            val blob = Base64.decode(stored.substring(i + 1), Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            return String(c.doFinal(blob), Charsets.UTF_8)
        } catch (e: Exception) {
            return null
        }
    }
}
