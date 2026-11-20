# Reglas R8/ProGuard de TurboDL.
# Las librerias vendidas usan reflexion intensa (registro de cajas MP4 por
# nombre, clases AspectJ, binding nativo de jlibtorrent): se conservan enteras.
# Se aplica shrink+optimize (elimina codigo muerto) pero NO ofuscacion: los
# nombres de clase se conservan para trazas legibles y cero riesgo de reflexion.

-dontobfuscate

# --- mp4parser (isoparser + muxer): registro de boxes por reflexion/AspectJ ---
-keep class org.mp4parser.** { *; }
-dontwarn org.mp4parser.**

# --- AspectJ runtime (necesario por isoparser) ---
-keep class org.aspectj.** { *; }
-dontwarn org.aspectj.**

# --- jlibtorrent (binding nativo swig) ---
-keep class org.jlibtorrent.** { *; }
-keep class com.frostwire.jlibtorrent.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
-dontwarn org.jlibtorrent.**
-dontwarn com.frostwire.**

# --- slf4j ---
-dontwarn org.slf4j.**

# --- modelos: guardados en SQLite y reconstruidos por nombre ---
-keep class com.turbodl.model.** { *; }

# --- interfaz JS del navegador ---
-keepclassmembers class com.turbodl.ui.BrowserActivity$Bridge {
    public *;
}

# WebView: metodos anotados con @JavascriptInterface (regla por defecto ya la
# incluye; se reafirma por claridad)
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# El parseo XML de Mpd.java usa javax.xml (framework, no se toca)
-dontwarn javax.xml.**

# (el motor de bloqueo es 100% Kotlin desde la 3.6.0: sin JNI, sin keeps)

# --- jsoup (exportacion Markdown, vendido en app/libs) ---
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**

# --- libadblockplus-android (Adblock Plus oficial, 4.1) ---
# La libreria expone objetos Java al codigo nativo (libadblockplus + V8) por
# nombre de metodo JNI. R8 no debe renombrar ni eliminar ni una sola clase
# del paquete raiz org.adblockplus.libadblockplus: un NoSuchMethodError en
# runtime significaria que la pagina se queda en blanco al cargar. Las
# reglas provistas por la propia AAR (consumer-rules) ya cubren esto, pero
# se reafirman aqui por si la AAR no se resolvio (modo fallback) y alguien
# la construye manualmente desde el codigo fuente.
-keep class org.adblockplus.libadblockplus.** { *; }
-keep class org.adblockplus.libadblockplus.android.** { *; }
-keep class org.adblockplus.libadblockplus.android.webview.** { *; }
-keep class org.adblockplus.libadblockplus.android.settings.** { *; }
# Mantener metodos nativos de cualquier clase (JNI de V8 + libadblockplus).
-keepclasseswithmembernames class * {
    native <methods>;
}
# Mantener miembros publicos de AdblockHelper (entry point singleton).
-keepclassmembers class org.adblockplus.libadblockplus.android.AdblockHelper {
    public *;
}
-dontwarn org.adblockplus.**
# Timber: la AAR la usa para logging; se incluye via dependencia transitiva.
-keep class timber.log.** { *; }
-dontwarn timber.log.**
