package com.grka.xray.core

/**
 * JNI bridge to hev-socks5-tunnel. The native library registers its methods
 * on this exact class (com/grka/xray/core/TProxyService — see the PKGNAME
 * define in scripts/compile-hevtun.sh), so the package, class and method
 * names must not change.
 */
object TProxyService {
    init {
        System.loadLibrary("hev-socks5-tunnel")
    }

    @JvmStatic
    @Suppress("FunctionName")
    external fun TProxyStartService(configPath: String, fd: Int)

    @JvmStatic
    @Suppress("FunctionName")
    external fun TProxyStopService()

    @JvmStatic
    @Suppress("FunctionName")
    external fun TProxyGetStats(): LongArray?
}
