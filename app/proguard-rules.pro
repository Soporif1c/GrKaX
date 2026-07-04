# Keep gomobile / libv2ray bindings
-keep class go.** { *; }
-keep class libv2ray.** { *; }
# Keep JNI bridge for hev-socks5-tunnel
-keep class com.grka.xray.core.TProxyService { *; }
