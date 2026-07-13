package com.grka.xray

import android.app.Application
import com.grka.xray.data.Store
import com.tencent.mmkv.MMKV
import java.io.File

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        MMKV.initialize(this)
        Store.init()
        cleanupUpdateCache()
    }

    /** Removes any update APK left in the cache after an install so it doesn't
     *  linger. A fresh download recreates it on demand. */
    private fun cleanupUpdateCache() {
        runCatching {
            File(cacheDir, "updates").deleteRecursively()
        }
    }
}
