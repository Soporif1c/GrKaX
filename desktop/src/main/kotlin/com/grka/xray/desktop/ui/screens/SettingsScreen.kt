package com.grka.xray.desktop.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.grka.xray.desktop.AppConfig
import com.grka.xray.desktop.AppVersion
import com.grka.xray.desktop.core.ConnState
import com.grka.xray.desktop.core.CoreRuntime
import com.grka.xray.desktop.core.XrayProcess
import com.grka.xray.desktop.data.Store
import com.grka.xray.desktop.net.UpdateChecker
import com.grka.xray.desktop.ui.SectionCard
import com.grka.xray.desktop.ui.SegmentedSwitch
import com.grka.xray.desktop.ui.SettingRow
import com.grka.xray.desktop.util.Shell
import com.grka.xray.desktop.util.Utils
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val state by CoreRuntime.state.collectAsState()

    var networkMode by remember { mutableStateOf(Store.networkMode) }
    var routingPreset by remember { mutableStateOf(Store.routingPreset) }
    var theme by remember { mutableStateOf(Store.theme) }
    var sniffing by remember { mutableStateOf(Store.sniffing) }
    var routeOnly by remember { mutableStateOf(Store.routeOnly) }
    var blockQuic by remember { mutableStateOf(Store.blockQuic) }
    var bypassTorrent by remember { mutableStateOf(Store.bypassTorrent) }
    var mux by remember { mutableStateOf(Store.mux) }
    var useSubRouting by remember { mutableStateOf(Store.useSubscriptionRouting) }
    var hwidEnabled by remember { mutableStateOf(Store.hwidEnabled) }
    var remoteDns by remember { mutableStateOf(Store.remoteDns) }
    var directDns by remember { mutableStateOf(Store.directDns) }
    var socksPort by remember { mutableStateOf(Store.socksPort.toString()) }
    var httpPort by remember { mutableStateOf(Store.httpPort.toString()) }
    var configTemplate by remember { mutableStateOf(Store.configTemplate) }
    var updateStatus by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Настройки",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = cs.onBackground,
        )

        SectionCard("Как трафик попадает в приложение") {
            SegmentedSwitch(
                options = listOf(
                    AppConfig.NET_SYSTEM_PROXY to "Системный прокси",
                    AppConfig.NET_TUN to "TUN",
                ),
                selected = networkMode,
                onSelect = { networkMode = it; Store.networkMode = it },
            )
            Text(
                text = if (networkMode == AppConfig.NET_TUN) {
                    "TUN перехватывает весь трафик, включая приложения, которые игнорируют системный прокси. " +
                        "Требует пароль администратора при подключении: создание utun-устройства и правка таблицы " +
                        "маршрутов возможны только с правами root."
                } else {
                    "Системный прокси прописывается в активную сеть (Wi-Fi / Ethernet). Никаких прав не требуется, " +
                        "но трафик увидят только приложения, уважающие настройки прокси."
                },
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
            if (state != ConnState.DISCONNECTED) {
                Text(
                    "Смена режима применится при следующем подключении.",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.secondary,
                )
            }
        }

        SectionCard("Маршрутизация") {
            SettingRow(
                title = "Правила из подписки",
                subtitle = "Использовать routing/dns, которые прислала панель (режим «Правила»)",
                checked = useSubRouting,
                onCheckedChange = { useSubRouting = it; Store.useSubscriptionRouting = it },
            )
            Text("Встроенный набор правил", style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
            SegmentedSwitch(
                options = listOf(
                    AppConfig.ROUTE_BYPASS_LAN to "Обход локальной сети",
                    AppConfig.ROUTE_BYPASS_RU to "Обход RU",
                ),
                selected = routingPreset,
                onSelect = { routingPreset = it; Store.routingPreset = it },
            )
            Text(
                "Применяется, только когда подписка и шаблон не принесли своих правил.",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
            SettingRow(
                title = "Блокировать QUIC",
                subtitle = "UDP 443 — иначе браузеры уходят мимо правил",
                checked = blockQuic,
                onCheckedChange = { blockQuic = it; Store.blockQuic = it },
            )
            SettingRow(
                title = "Торренты напрямую",
                subtitle = "BitTorrent не пойдёт через сервер",
                checked = bypassTorrent,
                onCheckedChange = { bypassTorrent = it; Store.bypassTorrent = it },
            )
        }

        SectionCard("Шаблон конфига (JSON)") {
            Text(
                "Вставьте xray-json целиком или только блок routing/dns — он получает высший приоритет. " +
                    "Нужен, когда панель отдаёт обычные ссылки без правил маршрутизации.",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
            OutlinedTextField(
                value = configTemplate,
                onValueChange = { configTemplate = it },
                modifier = Modifier.fillMaxWidth().height(180.dp),
                label = { Text("routing / dns") },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { Store.configTemplate = configTemplate }) { Text("Сохранить") }
                OutlinedButton(onClick = { configTemplate = ""; Store.configTemplate = "" }) { Text("Очистить") }
            }
        }

        SectionCard("Ядро и сеть") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = remoteDns,
                    onValueChange = { remoteDns = it; Store.remoteDns = it },
                    label = { Text("DNS через прокси") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = directDns,
                    onValueChange = { directDns = it; Store.directDns = it },
                    label = { Text("DNS напрямую") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = socksPort,
                    onValueChange = { value ->
                        socksPort = value
                        value.toIntOrNull()?.let { Store.socksPort = it }
                    },
                    label = { Text("Порт SOCKS") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = httpPort,
                    onValueChange = { value ->
                        httpPort = value
                        value.toIntOrNull()?.let { Store.httpPort = it }
                    },
                    label = { Text("Порт HTTP") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            SettingRow(
                title = "Sniffing",
                subtitle = "Определять домен по трафику — нужно для доменных правил",
                checked = sniffing,
                onCheckedChange = { sniffing = it; Store.sniffing = it },
            )
            SettingRow(
                title = "routeOnly",
                subtitle = "Использовать домен только для маршрутизации, не подменять адрес",
                checked = routeOnly,
                onCheckedChange = { routeOnly = it; Store.routeOnly = it },
            )
            SettingRow(
                title = "Mux",
                subtitle = "Мультиплексирование; автоматически отключается для XHTTP",
                checked = mux,
                onCheckedChange = { mux = it; Store.mux = it },
            )
        }

        SectionCard("Подписки и приватность") {
            SettingRow(
                title = "Отправлять x-hwid",
                subtitle = "Нужен панелям с лимитом устройств (Remnawave)",
                checked = hwidEnabled,
                onCheckedChange = { hwidEnabled = it; Store.hwidEnabled = it },
            )
            Text(
                "HWID этого компьютера: ${Utils.hwid}",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
        }

        SectionCard("Оформление") {
            SegmentedSwitch(
                options = listOf(
                    AppConfig.THEME_AURORA to "Aurora",
                    AppConfig.THEME_OCEAN to "Ocean",
                    AppConfig.THEME_PEARL to "Pearl",
                ),
                selected = theme,
                onSelect = { theme = it; Store.theme = it },
            )
        }

        SectionCard("О приложении") {
            Text("GrKa X для macOS · версия ${AppVersion.name}", color = cs.onSurface)
            // Spawns the core binary — resolve it once, not on every recomposition.
            val coreVersion = remember { XrayProcess.version() }
            Text(
                "Ядро: $coreVersion",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch {
                        updateStatus = "Проверяю…"
                        val release = UpdateChecker.latest()
                        updateStatus = when {
                            release == null -> "Не удалось проверить обновления"
                            UpdateChecker.isNewer(release.version) -> "Доступна версия ${release.version}"
                            else -> "Установлена последняя версия"
                        }
                        Store.lastUpdateCheck = System.currentTimeMillis()
                    }
                }) { Text("Проверить обновления") }
                OutlinedButton(onClick = { Shell.openUrl(AppConfig.RELEASES_PAGE) }) { Text("Страница релизов") }
            }
            if (updateStatus != null) {
                Text(updateStatus.orEmpty(), style = MaterialTheme.typography.bodySmall, color = cs.secondary)
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}
