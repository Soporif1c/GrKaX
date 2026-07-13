package com.grka.xray.data

import com.grka.xray.AppConfig
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json

data class SettingsSnapshot(
    val socksPort: Int,
    val mtu: Int,
    val ipv6: Boolean,
    val remoteDns: String,
    val directDns: String,
    val routingPreset: String,
    val blockQuic: Boolean,
    val bypassTorrent: Boolean,
    val sniffing: Boolean,
    val routeOnly: Boolean,
    val mux: Boolean,
    val logLevel: String,
    val perAppMode: String,
    val perAppSet: Set<String>,
)

object Store {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val profilesKv by lazy { MMKV.mmkvWithID("profiles") }
    private val subsKv by lazy { MMKV.mmkvWithID("subs") }
    private val settingsKv by lazy { MMKV.mmkvWithID("settings") }

    private const val KEY_ORDER = "profile_order"
    private const val KEY_SELECTED = "selected_profile"

    val profilesFlow = MutableStateFlow<List<Profile>>(emptyList())
    val subsFlow = MutableStateFlow<List<Subscription>>(emptyList())
    val selectedIdFlow = MutableStateFlow<String?>(null)
    val themeFlow = MutableStateFlow(AppConfig.THEME_AURORA)

    fun init() {
        reloadProfiles()
        reloadSubs()
        selectedIdFlow.value = settingsKv.decodeString(KEY_SELECTED)
        themeFlow.value = theme
    }

    // ---------------- profiles ----------------

    private fun orderList(): MutableList<String> {
        val raw = settingsKv.decodeString(KEY_ORDER) ?: return mutableListOf()
        return runCatching { json.decodeFromString<MutableList<String>>(raw) }.getOrDefault(mutableListOf())
    }

    private fun saveOrder(order: List<String>) {
        settingsKv.encode(KEY_ORDER, json.encodeToString(order))
    }

    private fun decodeProfile(id: String): Profile? {
        val raw = profilesKv.decodeString(id) ?: return null
        return runCatching { json.decodeFromString<Profile>(raw) }.getOrNull()
    }

    fun reloadProfiles() {
        val order = orderList()
        val all = profilesKv.allKeys()?.toMutableSet() ?: mutableSetOf()
        val list = mutableListOf<Profile>()
        for (id in order) {
            decodeProfile(id)?.let { list.add(it) }
            all.remove(id)
        }
        for (id in all) {
            decodeProfile(id)?.let { list.add(it) }
        }
        profilesFlow.value = list
    }

    fun saveProfile(p: Profile) {
        profilesKv.encode(p.id, json.encodeToString(p))
        val order = orderList()
        if (!order.contains(p.id)) {
            order.add(p.id)
            saveOrder(order)
        }
        reloadProfiles()
        if (settingsKv.decodeString(KEY_SELECTED) == null) selectProfile(p.id)
    }

    fun deleteProfile(id: String) {
        profilesKv.removeValueForKey(id)
        val order = orderList()
        order.remove(id)
        saveOrder(order)
        if (settingsKv.decodeString(KEY_SELECTED) == id) {
            val next = order.firstOrNull()
            if (next != null) selectProfile(next) else clearSelection()
        }
        reloadProfiles()
    }

    fun selectProfile(id: String) {
        settingsKv.encode(KEY_SELECTED, id)
        selectedIdFlow.value = id
    }

    private fun clearSelection() {
        settingsKv.removeValueForKey(KEY_SELECTED)
        selectedIdFlow.value = null
    }

    fun selectedProfile(): Profile? {
        val id = settingsKv.decodeString(KEY_SELECTED) ?: return null
        return decodeProfile(id)
    }

    /** Routing template of the subscription this profile belongs to, if any. */
    fun routingTemplateFor(profile: Profile): String? {
        val subId = profile.subId ?: return null
        if (!useSubscriptionRouting) return null
        val raw = subsKv.decodeString(subId) ?: return null
        val sub = runCatching { json.decodeFromString<Subscription>(raw) }.getOrNull() ?: return null
        return sub.routingJson?.takeIf { it.isNotBlank() }
    }

    /** Replaces all profiles belonging to a subscription, keeping selection when possible. */
    fun replaceSubscriptionProfiles(subId: String, newProfiles: List<Profile>) {
        val selectedId = settingsKv.decodeString(KEY_SELECTED)
        val old = profilesFlow.value.filter { it.subId == subId }
        val selectedOld = old.firstOrNull { it.id == selectedId }

        val order = orderList()
        for (p in old) {
            profilesKv.removeValueForKey(p.id)
            order.remove(p.id)
        }
        for (p in newProfiles) {
            p.subId = subId
            profilesKv.encode(p.id, json.encodeToString(p))
            order.add(p.id)
        }
        saveOrder(order)

        if (selectedOld != null) {
            val match = newProfiles.firstOrNull { it.identityKey() == selectedOld.identityKey() }
                ?: newProfiles.firstOrNull { it.name == selectedOld.name }
                ?: newProfiles.firstOrNull()
            if (match != null) selectProfile(match.id) else clearSelection()
        } else if (settingsKv.decodeString(KEY_SELECTED) == null && newProfiles.isNotEmpty()) {
            selectProfile(newProfiles.first().id)
        }
        reloadProfiles()
    }

    // ---------------- subscriptions ----------------

    fun reloadSubs() {
        val list = subsKv.allKeys()?.mapNotNull { key ->
            subsKv.decodeString(key)?.let { raw ->
                runCatching { json.decodeFromString<Subscription>(raw) }.getOrNull()
            }
        }?.sortedBy { it.name } ?: emptyList()
        subsFlow.value = list
    }

    fun saveSubscription(sub: Subscription) {
        subsKv.encode(sub.id, json.encodeToString(sub))
        reloadSubs()
    }

    fun deleteSubscription(id: String) {
        subsKv.removeValueForKey(id)
        // delete its profiles too
        val toDelete = profilesFlow.value.filter { it.subId == id }.map { it.id }
        for (pid in toDelete) deleteProfile(pid)
        reloadSubs()
    }

    // ---------------- settings ----------------

    private fun str(key: String, def: String): String = settingsKv.decodeString(key) ?: def
    private fun bool(key: String, def: Boolean): Boolean = settingsKv.decodeBool(key, def)
    private fun int(key: String, def: Int): Int = settingsKv.decodeInt(key, def)

    var theme: String
        get() = str("theme", AppConfig.THEME_AURORA)
        set(v) {
            settingsKv.encode("theme", v)
            themeFlow.value = v
        }

    var routingPreset: String
        get() = str("routing_preset", AppConfig.ROUTE_BYPASS_LAN)
        set(v) { settingsKv.encode("routing_preset", v) }

    var blockQuic: Boolean
        get() = bool("block_quic", true)
        set(v) { settingsKv.encode("block_quic", v) }

    var bypassTorrent: Boolean
        get() = bool("bypass_torrent", true)
        set(v) { settingsKv.encode("bypass_torrent", v) }

    var sniffing: Boolean
        get() = bool("sniffing", true)
        set(v) { settingsKv.encode("sniffing", v) }

    var routeOnly: Boolean
        get() = bool("route_only", false)
        set(v) { settingsKv.encode("route_only", v) }

    var mux: Boolean
        get() = bool("mux", false)
        set(v) { settingsKv.encode("mux", v) }

    var logLevel: String
        get() = str("log_level", "warning")
        set(v) { settingsKv.encode("log_level", v) }

    var remoteDns: String
        get() = str("remote_dns", AppConfig.DEFAULT_REMOTE_DNS)
        set(v) { settingsKv.encode("remote_dns", v) }

    var directDns: String
        get() = str("direct_dns", AppConfig.DEFAULT_DIRECT_DNS)
        set(v) { settingsKv.encode("direct_dns", v) }

    var socksPort: Int
        get() = int("socks_port", AppConfig.DEFAULT_SOCKS_PORT)
        set(v) { settingsKv.encode("socks_port", v) }

    var mtu: Int
        get() = int("mtu", AppConfig.DEFAULT_MTU)
        set(v) { settingsKv.encode("mtu", v) }

    var ipv6: Boolean
        get() = bool("ipv6", false)
        set(v) { settingsKv.encode("ipv6", v) }

    var perAppMode: String
        get() = str("per_app_mode", AppConfig.PER_APP_OFF)
        set(v) { settingsKv.encode("per_app_mode", v) }

    var perAppSet: Set<String>
        get() = settingsKv.decodeStringSet("per_app_set") ?: emptySet()
        set(v) { settingsKv.encode("per_app_set", v) }

    var autoStart: Boolean
        get() = bool("auto_start", false)
        set(v) { settingsKv.encode("auto_start", v) }

    var hwidEnabled: Boolean
        get() = bool("hwid_enabled", true)
        set(v) { settingsKv.encode("hwid_enabled", v) }

    var useSubscriptionRouting: Boolean
        get() = bool("use_sub_routing", true)
        set(v) { settingsKv.encode("use_sub_routing", v) }

    /** User-pasted xray-json (or a bare routing/dns object) used as a config
     *  template — its routing and dns override the preset. Lets users apply a
     *  panel's routing even when the subscription is delivered as plain links. */
    var configTemplate: String
        get() = str("config_template", "")
        set(v) { settingsKv.encode("config_template", v) }

    var hideNotification: Boolean
        get() = bool("hide_notification", true)
        set(v) { settingsKv.encode("hide_notification", v) }

    var geoAssetsVersion: Int
        get() = int("geo_assets_version", 0)
        set(v) { settingsKv.encode("geo_assets_version", v) }

    fun settingsSnapshot(): SettingsSnapshot = SettingsSnapshot(
        socksPort = socksPort,
        mtu = mtu,
        ipv6 = ipv6,
        remoteDns = remoteDns,
        directDns = directDns,
        routingPreset = routingPreset,
        blockQuic = blockQuic,
        bypassTorrent = bypassTorrent,
        sniffing = sniffing,
        routeOnly = routeOnly,
        mux = mux,
        logLevel = logLevel,
        perAppMode = perAppMode,
        perAppSet = perAppSet,
    )
}
