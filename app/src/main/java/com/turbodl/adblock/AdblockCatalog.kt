package com.turbodl.adblock

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Catálogo de listas de bloqueo disponibles en TurboDL (4.2).
 *
 * Antes (4.0–4.1) el motor solo cargaba EasyList + EasyPrivacy desde
 * easylist.to, hardcoded en `AdBlockEngine.java` (arrays `LISTAS` y
 * `URL_LISTAS`). El usuario pedía poder ELEGIR entre bloqueadores y
 * integrar más. Esta clase expone un catálogo de listas que el motor
 * puede cargar: el motor 100% Kotlin (`FilterEngine`) ya entiende
 * sintaxis EasyList estándar, así que cualquier lista en ese formato
 * funciona sin tocar el motor. Para las listas tipo "hosts"
 * (`0.0.0.0 ejemplo.com`) hay un adapter (`HostsAdapter.kt`) que las
 * convierte a reglas `||ejemplo.com^` antes de parsearlas.
 *
 * Las listas se descargan a `filesDir/adblock/<id>.txt` y se cargan
 * desde ahí en `AdBlockEngine.init()`. La elección del usuario se
 * persiste en SharedPreferences("cfg") clave `adblockLists` como JSON
 * array de ids; si está vacío, se usa el set por defecto
 * (`DEFAULT = {easylist, easyprivacy}`) para no romper instalaciones
 * existentes.
 *
 * No se incluye código nativo: todo es 100% Kotlin. Las listas tipo
 * hosts se convierten a sintaxis EasyList en tiempo de carga (vía
 * `HostsAdapter`), así que el motor no necesita cambios.
 */
object AdblockCatalog {

    enum class Format { EASYLIST, HOSTS }

    /**
     * Una entrada del catálogo. Los campos son @JvmField para que
     * AdBlockEngine.java los pueda leer directamente como campos
     * públicos (Kotlin expone los `val` como getters privados por
     * defecto, lo que rompe el acceso desde Java).
     */
    class Entry(
        @JvmField val id: String,
        @JvmField val name: String,
        @JvmField val url: String,
        @JvmField val format: Format,
        @JvmField val desc: String,
        /** Tamaño aproximado en disco al descargar (para mostrar al usuario). */
        @JvmField val approxBytes: Long
    )

    /**
     * Catálogo completo. Se añaden entradas aquí para que aparezcan
     * automáticamente en el selector de Ajustes y en el motor.
     *
     * Fuentes reales y verificadas:
     *  - EasyList / EasyPrivacy: https://easylist.to/ (canónico).
     *  - AdGuard Base / Tracking / Annoyances: https://filters.adtidy.org/
     *    (formato EasyList; el repo oficial los publica también como
     *    ublock filters: /extension/ublock/filters/<n>.txt).
     *  - Fanboy's Enhanced/Annoyances: https://fanboy.co.nz/ (formato
     *    EasyList; los mantiene Fanboy/ryanbr, usados por uBO y Brave).
     *  - StevenBlack/hosts: https://raw.githubusercontent.com/StevenBlack/hosts
     *    (formato `0.0.0.0 host`; ~80k hosts; el adapter lo convierte).
     *  - EasyList Spanish: regional de easylist.to.
     *
     * Los IDs son estables: si renombramos una entrada, el id se
     * mantiene para que la preferencia del usuario no se pierda.
     */
    val ALL: List<Entry> = listOf(
        Entry("easylist", "EasyList",
            "https://easylist.to/easylist/easylist.txt", Format.EASYLIST,
            "Lista principal de anuncios (la base de casi todas las demás).",
            2_100_000),
        Entry("easyprivacy", "EasyPrivacy",
            "https://easylist.to/easylist/easyprivacy.txt", Format.EASYLIST,
            "Trackers y telemetría. Complementa a EasyList.",
            1_550_000),
        Entry("adguard-base", "AdGuard Base",
            "https://filters.adtidy.org/extension/ublock/filters/2.txt",
            Format.EASYLIST,
            "Filtro base de AdGuard — alternativa a EasyList, con cobertura distinta.",
            2_400_000),
        Entry("adguard-tracking", "AdGuard Tracking",
            "https://filters.adtidy.org/extension/ublock/filters/3.txt",
            Format.EASYLIST,
            "AdGuard Tracking Protection — rastreadores y análisis.",
            1_100_000),
        Entry("adguard-annoyances", "AdGuard Annoyances",
            "https://filters.adtidy.org/extension/ublock/filters/14.txt",
            Format.EASYLIST,
            "Ventanas emergentes, banners de cookies, notificaciones web…",
            900_000),
        Entry("fanboy-annoyances", "Fanboy's Annoyances",
            "https://secure.fanboy.co.nz/fanboy-annoyance.txt",
            Format.EASYLIST,
            "Fanboy's Annoyances — banners de cookies, popups, widgets.",
            1_500_000),
        Entry("easylist-es", "EasyList Spanish",
            "https://easylist.to/easylistspanish/easylistspanish.txt",
            Format.EASYLIST,
            "Reglas regionales para sitios en español.",
            150_000),
        Entry("stevenblack-hosts", "StevenBlack/hosts",
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
            Format.HOSTS,
            "Lista unificada de hosts (~80k) en formato 0.0.0.0 host. " +
                "Se convierte a sintaxis EasyList al cargar.",
            4_500_000)
    )

    /** Set activo por defecto (instalación nueva y/o sin preferencia). */
    val DEFAULT_IDS: Set<String> = setOf("easylist", "easyprivacy")

    /** Mapa id -> Entry para lookup rápido. */
    val BY_ID: Map<String, Entry> = ALL.associateBy { it.id }

    /** Lista de ids seleccionados por el usuario (persistido en cfg). */
    @JvmStatic
    fun selectedIds(ctx: Context): Set<String> {
        val sp = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val raw = sp.getString("adblockLists", null)
        if (raw.isNullOrBlank()) return DEFAULT_IDS
        return try {
            val arr = JSONArray(raw)
            val out = LinkedHashSet<String>()
            for (i in 0 until arr.length()) {
                val id = arr.optString(i)
                if (BY_ID.containsKey(id)) out.add(id)
            }
            // si el usuario desactivó todo, volvemos al default
            if (out.isEmpty()) DEFAULT_IDS else out
        } catch (e: Exception) { DEFAULT_IDS }
    }

    /** Persiste la selección. Llamar desde el hilo UI. */
    @JvmStatic
    fun setSelectedIds(ctx: Context, ids: Set<String>) {
        val arr = JSONArray()
        // mantener el orden del catálogo (estable en disco)
        ALL.forEach { if (ids.contains(it.id)) arr.put(it.id) }
        ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
            .edit().putString("adblockLists", arr.toString()).apply()
    }

    /** Entradas seleccionadas (orden del catálogo). */
    @JvmStatic
    fun selected(ctx: Context): List<Entry> {
        val ids = selectedIds(ctx)
        return ALL.filter { it.id in ids }
    }

    /** Nombre corto de la selección para mostrar en Ajustes. */
    fun summary(ctx: Context): String {
        val sel = selected(ctx)
        if (sel.isEmpty()) return "ninguna (motor desactivado)"
        if (sel.size == 1) return sel[0].name
        // 3+ listas: nombre del primer motor + conteo
        return "${sel.size} listas: ${sel.joinToString(", ") { it.name }.take(60)}"
    }
}
