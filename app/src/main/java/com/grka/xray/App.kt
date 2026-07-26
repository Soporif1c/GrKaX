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

    /**
     * Removes stale update APKs so the cache doesn't grow. Only files older than
     * a day are deleted: a just-downloaded APK may still be being read by the
     * package installer while we restart, and deleting it would break the install.
     */
    private fun cleanupUpdateCache() {
        runCatching {
            val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
            File(cacheDir, "updates").listFiles()?.forEach { file ->
                if (file.lastModified() < cutoff) file.delete()
            }
        }
    }
}
