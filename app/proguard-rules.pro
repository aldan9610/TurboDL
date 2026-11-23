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

# --- motor Brave nativo (AdblockAndroid fork reaver961025, 4.11) ---
# adblock-client expone MatchResult al C++ (brave/ad-block) por JNI: el
# constructor (boolean, String, String) se invoca desde libadblock-client.so
# y R8 NO debe renombrarlo ni eliminarlo. Las clases del filtro y las
# generadas por Mezzanine (scriptlets JS embebidos) también se conservan:
# WorkManager instancia los workers por nombre y kotlinx-serialization
# refleja los serializers de Filter.
-keep class io.github.edsuns.adblockclient.** { *; }
-keep class io.github.edsuns.adfilter.** { *; }
-keep class io.github.edsuns.net.** { *; }
-keep class com.anthonycr.mezzanine.** { *; }
-keepclassmembers class io.github.edsuns.adblockclient.MatchResult {
    <init>(boolean,java.lang.String,java.lang.String);
}
-dontwarn io.github.edsuns.**
-dontwarn com.anthonycr.mezzanine.**
# Timber: la librería Brave lo usa para logging; dependencia directa.
-keep class timber.log.** { *; }
-dontwarn timber.log.**
