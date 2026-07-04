package com.grka.xray

import android.app.Application
import com.grka.xray.data.Store
import com.tencent.mmkv.MMKV

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        MMKV.initialize(this)
        Store.init()
    }
}
