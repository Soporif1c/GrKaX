package com.grka.xray.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.grka.xray.AppConfig
import com.grka.xray.BuildConfig
import com.grka.xray.R
import com.grka.xray.core.CoreRuntime
import com.grka.xray.data.Store
import com.grka.xray.net.UpdateChecker
import com.grka.xray.ui.PerAppActivity
import com.grka.xray.ui.toast
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val scroll = rememberScrollState()

    // Local mutable mirrors of persisted settings
    var theme by remember { mutableStateOf(Store.theme) }
    var routing by remember { mutableStateOf(Store.routingPreset) }
    var blockQuic by remember { mutableStateOf(Store.blockQuic) }
    var bypassTorrent by remember { mutableStateOf(Store.bypassTorrent) }
    var ipv6 by remember { mutableStateOf(Store.ipv6) }
    var mux by remember { mutableStateOf(Store.mux) }
    var hwid by remember { mutableStateOf(Store.hwidEnabled) }
    var autoStart by remember { mutableStateOf(Store.autoStart) }
    var subRouting by remember { mutableStateOf(Store.useSubscriptionRouting) }
    var perAppMode by remember { mutableStateOf(Store.perAppMode) }
    var remoteDns by remember { mutableStateOf(Store.remoteDns) }
    var dnsDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checkingUpdate by remember { mutableStateOf(false) }
    var updateStatus by remember { mutableStateOf<String?>(null) }
    var updateUrl by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(scroll)
            .padding(horizontal = 16.dp),
    ) {
        Text(
            text = stringResource(R.string.tab_settings),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = cs.onBackground,
            modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 8.dp),
        )

        // ---- Appearance ----
        SectionTitle(stringResource(R.string.section_appearance))
        SettingsCard {
            Text(
                text = stringResource(R.string.theme),
                style = MaterialTheme.typography.titleSmall,
                color = cs.onSurface,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                ThemeSwatch(
                    name = stringResource(R.string.theme_aurora),
                    colors = listOf(Color(0xFF8B7CFF), Color(0xFF22D3EE)),
                    selected = theme == AppConfig.THEME_AURORA,
                    modifier = Modifier.weight(1f),
                ) { theme = AppConfig.THEME_AURORA; Store.theme = theme }
                Spacer(Modifier.width(10.dp))
                ThemeSwatch(
                    name = stringResource(R.string.theme_ocean),
                    colors = listOf(Color(0xFF2DD4BF), Color(0xFF38BDF8)),
                    selected = theme == AppConfig.THEME_OCEAN,
                    modifier = Modifier.weight(1f),
                ) { theme = AppConfig.THEME_OCEAN; Store.theme = theme }
                Spacer(Modifier.width(10.dp))
                ThemeSwatch(
                    name = stringResource(R.string.theme_pearl),
                    colors = listOf(Color(0xFF4F46E5), Color(0xFFDB2777)),
                    selected = theme == AppConfig.THEME_PEARL,
                    modifier = Modifier.weight(1f),
                ) { theme = AppConfig.THEME_PEARL; Store.theme = theme }
            }
        }

        // ---- Routing ----
        SectionTitle(stringResource(R.string.section_routing))
        SettingsCard {
            RadioRow(stringResource(R.string.route_global), routing == AppConfig.ROUTE_GLOBAL) {
                routing = AppConfig.ROUTE_GLOBAL; Store.routingPreset = routing
            }
            RadioRow(stringResource(R.string.route_bypass_lan), routing == AppConfig.ROUTE_BYPASS_LAN) {
                routing = AppConfig.ROUTE_BYPASS_LAN; Store.routingPreset = routing
            }
            RadioRow(stringResource(R.string.route_bypass_ru), routing == AppConfig.ROUTE_BYPASS_RU) {
                routing = AppConfig.ROUTE_BYPASS_RU; Store.routingPreset = routing
            }
        }

        // ---- Connection ----
        SectionTitle(stringResource(R.string.section_connection))
        SettingsCard {
            SwitchRow(
                title = stringResource(R.string.block_quic),
                subtitle = stringResource(R.string.block_quic_hint),
                checked = blockQuic,
            ) { blockQuic = it; Store.blockQuic = it }
            SwitchRow(
                title = stringResource(R.string.bypass_torrent),
                subtitle = stringResource(R.string.bypass_torrent_hint),
                checked = bypassTorrent,
            ) { bypassTorrent = it; Store.bypassTorrent = it }
            SwitchRow(
                title = stringResource(R.string.ipv6),
                subtitle = stringResource(R.string.ipv6_hint),
                checked = ipv6,
            ) { ipv6 = it; Store.ipv6 = it }
            SwitchRow(
                title = stringResource(R.string.mux),
                subtitle = stringResource(R.string.mux_hint),
                checked = mux,
            ) { mux = it; Store.mux = it }
            ClickableRow(
                title = stringResource(R.string.remote_dns),
                subtitle = remoteDns,
            ) { dnsDialog = true }
            ClickableRow(
                title = stringResource(R.string.per_app_title),
                subtitle = when (perAppMode) {
                    AppConfig.PER_APP_BYPASS -> stringResource(R.string.per_app_bypass)
                    AppConfig.PER_APP_ALLOW -> stringResource(R.string.per_app_only)
                    else -> stringResource(R.string.per_app_off)
                },
            ) {
                context.startActivity(Intent(context, PerAppActivity::class.java))
            }
        }

        // ---- Subscription ----
        SectionTitle(stringResource(R.string.section_subscription))
        SettingsCard {
            SwitchRow(
                title = stringResource(R.string.hwid),
                subtitle = stringResource(R.string.hwid_hint),
                checked = hwid,
            ) { hwid = it; Store.hwidEnabled = it }
            SwitchRow(
                title = stringResource(R.string.auto_start),
                subtitle = stringResource(R.string.auto_start_hint),
                checked = autoStart,
            ) { autoStart = it; Store.autoStart = it }
            SwitchRow(
                title = stringResource(R.string.sub_routing),
                subtitle = stringResource(R.string.sub_routing_hint),
                checked = subRouting,
            ) { subRouting = it; Store.useSubscriptionRouting = it }
        }

        // ---- About ----
        SectionTitle(stringResource(R.string.section_about))
        SettingsCard {
            InfoRow(stringResource(R.string.version), "v" + BuildConfig.VERSION_NAME)
            InfoRow(stringResource(R.string.core_version), remember { CoreRuntime.coreVersion() })

            val statusText = updateStatus
            ClickableRow(
                title = stringResource(R.string.check_update),
                subtitle = statusText ?: stringResource(R.string.check_update_hint),
            ) {
                val url = updateUrl
                if (url != null) {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    return@ClickableRow
                }
                if (checkingUpdate) return@ClickableRow
                checkingUpdate = true
                updateStatus = context.getString(R.string.check_update_checking)
                scope.launch {
                    when (val r = UpdateChecker.check()) {
                        is UpdateChecker.Result.Success -> {
                            if (r.info.isNewer) {
                                updateStatus = context.getString(R.string.update_available, r.info.latestVersion)
                                updateUrl = r.info.htmlUrl
                            } else {
                                updateStatus = context.getString(R.string.update_latest)
                            }
                        }

                        is UpdateChecker.Result.Error -> {
                            updateStatus = context.getString(R.string.update_check_failed, r.message)
                        }
                    }
                    checkingUpdate = false
                }
            }
            if (checkingUpdate) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    if (dnsDialog) {
        var value by remember { mutableStateOf(remoteDns) }
        AlertDialog(
            onDismissRequest = { dnsDialog = false },
            title = { Text(stringResource(R.string.remote_dns)) },
            text = {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    placeholder = { Text("1.1.1.1") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    remoteDns = value.trim().ifEmpty { AppConfig.DEFAULT_REMOTE_DNS }
                    Store.remoteDns = remoteDns
                    dnsDialog = false
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { dnsDialog = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.secondary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 6.dp),
    )
}

@Composable
private fun SettingsCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = cs.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun RadioRow(title: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun ClickableRow(title: String, subtitle: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = cs.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun InfoRow(title: String, value: String) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = cs.onSurface, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
    }
}

@Composable
private fun ThemeSwatch(
    name: String,
    colors: List<Color>,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = modifier.clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(colors))
        ) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .align(Alignment.Center),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.9f))
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            name,
            style = MaterialTheme.typography.bodySmall,
            color = if (selected) cs.primary else cs.onSurfaceVariant,
        )
    }
}
