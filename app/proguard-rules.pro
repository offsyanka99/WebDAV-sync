# No custom ProGuard rules yet.

# Sardine-android (WebDAV client) and the XML pull-parser it/we use for PROPFIND responses don't
# ship their own consumer ProGuard rules, and rely on reflection for model (de)serialization.
-keep class com.thegrizzlylabs.sardineandroid.** { *; }
-keep class org.xmlpull.v1.** { *; }
-dontwarn com.thegrizzlylabs.sardineandroid.**
-dontwarn org.xmlpull.v1.**

# okhttp-digest builds requests reflectively; keep it intact.
-keep class org.aarnott.okhttpdigest.** { *; }
-keep class com.burgstaller.okhttp.** { *; }
-dontwarn org.aarnott.okhttpdigest.**
-dontwarn com.burgstaller.okhttp.**
