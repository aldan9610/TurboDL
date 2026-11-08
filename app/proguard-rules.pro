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
