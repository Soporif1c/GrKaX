package com.grka.xray.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.grka.xray.desktop.config.LinkParser
import com.grka.xray.desktop.data.Profile
import com.grka.xray.desktop.data.Store
import com.grka.xray.desktop.data.Subscription
import com.grka.xray.desktop.net.PingService
import com.grka.xray.desktop.net.SubscriptionManager
import com.grka.xray.desktop.util.Utils
import kotlinx.coroutines.launch

@Composable
fun ServersScreen() {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    val profiles by Store.profilesFlow.collectAsState()
    val subs by Store.subsFlow.collectAsState()
    val selectedId by Store.selectedIdFlow.collectAsState()

    var pings by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var showSubDialog by remember { mutableStateOf(false) }
    var showLinkDialog by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Серверы и подписки",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = cs.onBackground,
                modifier = Modifier.weight(1f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showLinkDialog = true }) { Text("Вставить ссылку") }
                Button(onClick = { showSubDialog = true }) { Text("＋ Подписка") }
            }
        }

        if (busy != null) {
            Spacer(Modifier.height(8.dp))
            Text(busy.orEmpty(), style = MaterialTheme.typography.bodySmall, color = cs.secondary)
        }

        Spacer(Modifier.height(18.dp))

        if (subs.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Подписки",
                    style = MaterialTheme.typography.titleSmall,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    scope.launch {
                        busy = "Обновляю подписки…"
                        val results = SubscriptionManager.updateAll()
                        val added = results.sumOf { it.second.count }
                        val failed = results.count { !it.second.ok }
                        busy = if (failed == 0) "Обновлено серверов: $added" else "Готово, ошибок: $failed"
                    }
                }) { Text("Обновить все") }
            }
            Spacer(Modifier.height(6.dp))
            for (sub in subs) {
                SubscriptionRow(
                    sub = sub,
                    onUpdate = {
                        scope.launch {
                            busy = "Обновляю «${sub.name.ifBlank { sub.url }}»…"
                            val result = SubscriptionManager.update(sub)
                            busy = if (result.ok) "Серверов получено: ${result.count}" else "Ошибка: ${result.error}"
                        }
                    },
                    onDelete = { Store.deleteSubscription(sub.id) },
                )
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(14.dp))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Серверы (${profiles.size})",
                style = MaterialTheme.typography.titleSmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    scope.launch {
                        busy = "Измеряю задержку…"
                        pings = PingService.tcpPingAll(profiles)
                        busy = null
                    }
                },
                enabled = profiles.isNotEmpty(),
            ) { Text("↻ Пинг всех") }
        }

        Spacer(Modifier.height(6.dp))

        if (profiles.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Пока пусто — добавьте подписку или вставьте ссылку vless:// / vmess:// / trojan:// / ss://",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(profiles, key = { it.id }) { profile ->
                    ProfileRow(
                        profile = profile,
                        selected = profile.id == selectedId,
                        ping = pings[profile.id],
                        onSelect = { Store.selectProfile(profile.id) },
                        onDelete = { Store.deleteProfile(profile.id) },
                    )
                }
            }
        }
    }

    if (showSubDialog) {
        SubscriptionDialog(
            onDismiss = { showSubDialog = false },
            onConfirm = { url, name, userAgent ->
                showSubDialog = false
                val sub = Subscription(name = name, url = url, userAgent = userAgent.ifBlank { null })
                Store.saveSubscription(sub)
                scope.launch {
                    busy = "Загружаю подписку…"
                    val result = SubscriptionManager.update(sub)
                    busy = if (result.ok) "Серверов получено: ${result.count}" else "Ошибка: ${result.error}"
                }
            },
        )
    }

    if (showLinkDialog) {
        LinkDialog(
            onDismiss = { showLinkDialog = false },
            onConfirm = { text ->
                showLinkDialog = false
                val parsed = LinkParser.parseBatch(text)
                parsed.forEach { Store.saveProfile(it) }
                busy = if (parsed.isEmpty()) "Не удалось разобрать ссылку" else "Добавлено серверов: ${parsed.size}"
            },
        )
    }
}

@Composable
private fun SubscriptionRow(sub: Subscription, onUpdate: () -> Unit, onDelete: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(cs.surface)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                sub.name.ifBlank { sub.url },
                style = MaterialTheme.typography.bodyLarge,
                color = cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val used = if (sub.total > 0) {
                "${Utils.formatBytes(sub.upload + sub.download)} из ${Utils.formatBytes(sub.total)}"
            } else {
                "объём не указан"
            }
            val expires = if (sub.expire > 0) " · до ${Utils.formatDate(sub.expire)}" else ""
            Text(
                used + expires,
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
        }
        TextButton(onClick = onUpdate) { Text("Обновить") }
        TextButton(onClick = onDelete) { Text("Удалить", color = cs.error) }
    }
}

@Composable
private fun ProfileRow(
    profile: Profile,
    selected: Boolean,
    ping: Long?,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) cs.primaryContainer else cs.surface)
            .clickable { onSelect() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(if (selected) cs.primary else cs.outline),
        )
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                profile.name.ifBlank { "${profile.server}:${profile.port}" },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) cs.onPrimaryContainer else cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${profile.protoLabel()} · ${profile.transportLabel()} · ${profile.server}:${profile.port}",
                style = MaterialTheme.typography.bodySmall,
                color = if (selected) cs.onPrimaryContainer else cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (ping != null) {
            Text(
                if (ping >= 0) "$ping мс" else "—",
                style = MaterialTheme.typography.bodySmall,
                color = if (ping in 0..300) cs.secondary else cs.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp),
            )
        }
        TextButton(onClick = onDelete) { Text("✕", color = cs.onSurfaceVariant) }
    }
}

@Composable
private fun SubscriptionDialog(onDismiss: () -> Unit, onConfirm: (String, String, String) -> Unit) {
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var userAgent by remember { mutableStateOf("Happ") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новая подписка") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Ссылка на подписку") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Название (необязательно)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = userAgent,
                    onValueChange = { userAgent = it },
                    label = { Text("User-Agent") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Панель может отдавать разный формат в зависимости от User-Agent. " +
                        "«Happ» обычно включает xray-json с маршрутизацией и обфускацией XHTTP.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(url.trim(), name.trim(), userAgent.trim()) }, enabled = url.isNotBlank()) {
                Text("Добавить")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun LinkDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Добавить по ссылке") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("vless:// vmess:// trojan:// ss:// или base64-список") },
                modifier = Modifier.fillMaxWidth().height(160.dp),
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text("Добавить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
