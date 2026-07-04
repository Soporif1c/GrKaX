package com.grka.xray.net

import android.content.Context
import com.grka.xray.AppConfig
import com.grka.xray.config.ConfigBuilder
import com.grka.xray.core.CoreRuntime
import com.grka.xray.data.Profile
import com.grka.xray.data.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import libv2ray.Libv2ray

/** Real-delay tests through a throwaway core instance per profile. */
object PingService {

    /** profileId -> latency ms; -1 means failed, absence means not tested. */
    val results = MutableStateFlow<Map<String, Long>>(emptyMap())
    val testing = MutableStateFlow(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun testAll(context: Context, profiles: List<Profile>) {
        if (testing.value || profiles.isEmpty()) return
        testing.value = true
        scope.launch {
            CoreRuntime.init(context)
            val semaphore = Semaphore(6)
            val settings = Store.settingsSnapshot()
            val jobs = profiles.map { profile ->
                launch {
                    semaphore.withPermit {
                        val ms = measure(profile, settings)
                        results.value = results.value + (profile.id to ms)
                    }
                }
            }
            jobs.joinAll()
            testing.value = false
        }
    }

    fun testOne(context: Context, profile: Profile) {
        scope.launch {
            CoreRuntime.init(context)
            val ms = measure(profile, Store.settingsSnapshot())
            results.value = results.value + (profile.id to ms)
        }
    }

    private fun measure(profile: Profile, settings: com.grka.xray.data.SettingsSnapshot): Long = try {
        val config = ConfigBuilder.build(profile, settings, forTest = true)
        Libv2ray.measureOutboundDelay(config, AppConfig.DELAY_TEST_URL)
    } catch (e: Exception) {
        -1L
    }
}
