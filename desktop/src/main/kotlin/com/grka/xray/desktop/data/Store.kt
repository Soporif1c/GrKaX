package com.grka.xray.desktop.data

import com.grka.xray.desktop.AppConfig
import com.grka.xray.desktop.util.Platform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Plain-JSON replacement for the Android client's MMKV store. Three files in
 * the app data dir: profiles (ordered), subscriptions, settings.
 */
object Store {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    private val profilesFile = File(Platform.dataDir, "profiles.json")
    private val subsFile = File(Platform.dataDir, "subscriptions.json")
    private val settingsFile = File(Platform.dataDir, "settings.json")

    val profilesFlow = MutableStateFlow<List<Profile>>(emptyList())
    val subsFlow = MutableStateFlow<List<Subscription>>(emptyList())
    val selectedIdFlow = MutableStateFlow<String?>(null)
    val themeFlow = MutableStateFlow(AppConfig.THEME_AURORA)
    /** Rule / Global / Direct — bound to the switch beside the power button. */
    val modeFlow = MutableStateFlow(AppConfig.MODE_RULE)
    /** System proxy vs TUN — shown on the home screen, changed in settings. */
    val networkModeFlow = MutableStateFlow(AppConfig.NET_SYSTEM_PROXY)

    private val settings = LinkedHashMap<String, JsonPrimitive>()

    fun init() {
        loadSettings()
        profilesFlow.value = readList(profilesFile)
        subsFlow.value = readList(subsFile)
        selectedIdFlow.value = str("selected_profile", "").takeIf { it.isNotBlank() }
        themeFlow.value = theme
        modeFlow.value = mode
        networkModeFlow.value = networkMode
    }

    // ---------------- persistence primitives ----------------

    private inline fun <reified T> readList(file: File): List<T> {
        if (!file.exists()) return emptyList()
        return runCatching { json.decodeFromString<List<T>>(file.readText()) }.getOrDefault(emptyList())
    }

    private fun writeAtomically(file: File, text: String) {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun persistProfiles() {
        writeAtomically(profilesFile, json.encodeToString(profilesFlow.value))
    }

    private fun persistSubs() {
        writeAtomically(subsFile, json.encodeToString(subsFlow.value))
    }

    private fun loadSettings() {
        settings.clear()
        if (!settingsFile.exists()) return
        runCatching {
            val obj = json.parseToJsonElement(settingsFile.readText()) as? JsonObject ?: return
            for ((k, v) in obj) settings[k] = v.jsonPrimitive
        }
    }

    private fun persistSettings() {
        writeAtomically(settingsFile, json.encodeToString(JsonObject(settings)))
    }

    private fun str(key: String, def: String): String =
        settings[key]?.contentOrNull ?: def

    private fun bool(key: String, def: Boolean): Boolean =
        settings[key]?.booleanOrNull ?: def

    private fun int(key: String, def: Int): Int =
        settings[key]?.intOrNull ?: def

    private fun long(key: String, def: Long): Long =
        settings[key]?.longOrNull ?: def

    private fun put(key: String, value: String) {
        settings[key] = JsonPrimitive(value); persistSettings()
    }

    private fun put(key: String, value: Boolean) {
        settings[key] = JsonPrimitive(value); persistSettings()
    }

    private fun put(key: String, value: Int) {
        settings[key] = JsonPrimitive(value); persistSettings()
    }

    private fun put(key: String, value: Long) {
        settings[key] = JsonPrimitive(value); persistSettings()
    }

    // ---------------- profiles ----------------

    fun saveProfile(p: Profile) {
        val list = profilesFlow.value.toMutableList()
        val idx = list.indexOfFirst { it.id == p.id }
        if (idx >= 0) list[idx] = p else list.add(p)
        profilesFlow.value = list
        persistProfiles()
        if (selectedIdFlow.value == null) selectProfile(p.id)
    }

    fun deleteProfile(id: String) {
        profilesFlow.value = profilesFlow.value.filterNot { it.id == id }
        persistProfiles()
        if (selectedIdFlow.value == id) {
            val next = profilesFlow.value.firstOrNull()
            if (next != null) selectProfile(next.id) else clearSelection()
        }
    }

    fun selectProfile(id: String) {
        put("selected_profile", id)
        selectedIdFlow.value = id
    }

    private fun clearSelection() {
        settings.remove("selected_profile")
        persistSettings()
        selectedIdFlow.value = null
    }

    fun selectedProfile(): Profile? {
        val id = selectedIdFlow.value ?: return null
        return profilesFlow.value.firstOrNull { it.id == id }
    }

    /** Routing template of the subscription this profile belongs to, if any. */
    fun routingTemplateFor(profile: Profile): String? {
        val subId = profile.subId ?: return null
        if (!useSubscriptionRouting) return null
        val sub = subsFlow.value.firstOrNull { it.id == subId } ?: return null
        return sub.routingJson?.takeIf { it.isNotBlank() }
    }

    /** Replaces all profiles of a subscription, keeping the selection when possible. */
    fun replaceSubscriptionProfiles(subId: String, newProfiles: List<Profile>) {
        val selectedId = selectedIdFlow.value
        val old = profilesFlow.value.filter { it.subId == subId }
        val selectedOld = old.firstOrNull { it.id == selectedId }

        for (p in newProfiles) p.subId = subId
        profilesFlow.value = profilesFlow.value.filterNot { it.subId == subId } + newProfiles
        persistProfiles()

        if (selectedOld != null) {
            val match = newProfiles.firstOrNull { it.identityKey() == selectedOld.identityKey() }
                ?: newProfiles.firstOrNull { it.name == selectedOld.name }
                ?: newProfiles.firstOrNull()
            if (match != null) selectProfile(match.id) else clearSelection()
        } else if (selectedIdFlow.value == null && newProfiles.isNotEmpty()) {
            selectProfile(newProfiles.first().id)
        }
    }

    // ---------------- subscriptions ----------------

    fun saveSubscription(sub: Subscription) {
        val list = subsFlow.value.toMutableList()
        val idx = list.indexOfFirst { it.id == sub.id }
        if (idx >= 0) list[idx] = sub else list.add(sub)
        subsFlow.value = list.sortedBy { it.name }
        persistSubs()
    }

    fun deleteSubscription(id: String) {
        subsFlow.value = subsFlow.value.filterNot { it.id == id }
        persistSubs()
        val orphans = profilesFlow.value.filter { it.subId == id }.map { it.id }
        for (pid in orphans) deleteProfile(pid)
    }

    // ---------------- settings ----------------

    var theme: String
        get() = str("theme", AppConfig.THEME_AURORA)
        set(v) { put("theme", v); themeFlow.value = v }

    /** Rule / Global / Direct. */
    var mode: String
        get() = str("mode", AppConfig.MODE_RULE)
        set(v) { put("mode", v); modeFlow.value = v }

    /** System proxy or TUN. */
    var networkMode: String
        get() = str("network_mode", AppConfig.NET_SYSTEM_PROXY)
        set(v) { put("network_mode", v); networkModeFlow.value = v }

    var routingPreset: String
        get() = str("routing_preset", AppConfig.ROUTE_BYPASS_LAN)
        set(v) { put("routing_preset", v) }

    var blockQuic: Boolean
        get() = bool("block_quic", true)
        set(v) { put("block_quic", v) }

    var bypassTorrent: Boolean
        get() = bool("bypass_torrent", true)
        set(v) { put("bypass_torrent", v) }

    var sniffing: Boolean
        get() = bool("sniffing", true)
        set(v) { put("sniffing", v) }

    var routeOnly: Boolean
        get() = bool("route_only", false)
        set(v) { put("route_only", v) }

    var mux: Boolean
        get() = bool("mux", false)
        set(v) { put("mux", v) }

    var logLevel: String
        get() = str("log_level", "warning")
        set(v) { put("log_level", v) }

    var remoteDns: String
        get() = str("remote_dns", AppConfig.DEFAULT_REMOTE_DNS)
        set(v) { put("remote_dns", v) }

    var directDns: String
        get() = str("direct_dns", AppConfig.DEFAULT_DIRECT_DNS)
        set(v) { put("direct_dns", v) }

    var socksPort: Int
        get() = int("socks_port", AppConfig.DEFAULT_SOCKS_PORT)
        set(v) { put("socks_port", v) }

    var httpPort: Int
        get() = int("http_port", AppConfig.DEFAULT_HTTP_PORT)
        set(v) { put("http_port", v) }

    var apiPort: Int
        get() = int("api_port", AppConfig.DEFAULT_API_PORT)
        set(v) { put("api_port", v) }

    var hwidEnabled: Boolean
        get() = bool("hwid_enabled", true)
        set(v) { put("hwid_enabled", v) }

    var useSubscriptionRouting: Boolean
        get() = bool("use_sub_routing", true)
        set(v) { put("use_sub_routing", v) }

    /** User-pasted xray-json (or a bare routing/dns object) whose routing and
     *  dns override the preset — the escape hatch for panels that serve plain
     *  share links with no routing attached. */
    var configTemplate: String
        get() = str("config_template", "")
        set(v) { put("config_template", v) }

    var launchAtLogin: Boolean
        get() = bool("launch_at_login", false)
        set(v) { put("launch_at_login", v) }

    var autoConnect: Boolean
        get() = bool("auto_connect", false)
        set(v) { put("auto_connect", v) }

    var autoCheckUpdates: Boolean
        get() = bool("auto_check_updates", true)
        set(v) { put("auto_check_updates", v) }

    var lastUpdateCheck: Long
        get() = long("last_update_check", 0L)
        set(v) { put("last_update_check", v) }

    fun settingsSnapshot(): SettingsSnapshot = SettingsSnapshot(
        socksPort = socksPort,
        httpPort = httpPort,
        apiPort = apiPort,
        remoteDns = remoteDns,
        directDns = directDns,
        mode = mode,
        routingPreset = routingPreset,
        blockQuic = blockQuic,
        bypassTorrent = bypassTorrent,
        sniffing = sniffing,
        routeOnly = routeOnly,
        mux = mux,
        logLevel = logLevel,
        bindInterface = if (networkMode == AppConfig.NET_TUN) {
            com.grka.xray.desktop.platform.MacNet.defaultInterface()
        } else {
            null
        },
    )
}
