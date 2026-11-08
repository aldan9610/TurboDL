# jlibtorrent: mantener clases JNI
-keep class com.frostwire.jlibtorrent.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
-dontwarn com.frostwire.**
