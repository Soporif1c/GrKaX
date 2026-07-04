package com.grka.xray.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.grka.xray.R
import com.grka.xray.config.LinkParser
import com.grka.xray.data.Profile
import com.grka.xray.data.Store
import com.grka.xray.data.Subscription
import com.grka.xray.net.PingService
import com.grka.xray.net.SubscriptionManager
import com.grka.xray.ui.toast
import com.grka.xray.util.Utils
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Universal screen combining subscriptions and their servers (plus manually
 * added servers) into one list.
 */
@Composable
fun ServersScreen(
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme

    val profiles by Store.profilesFlow.collectAsState()
    val subs by Store.subsFlow.collectAsState()
    val selectedId by Store.selectedIdFlow.collectAsState()
    val pings by PingService.results.collectAsState()
    val testing by PingService.testing.collectAsState()

    var addMenuOpen by remember { mutableStateOf(false) }
    var manualDialog by remember { mutableStateOf(false) }
    var subDialog by remember { mutableStateOf<Subscription?>(null) }
    var qrProfile by remember { mutableStateOf<Profile?>(null) }
    var deleteProfile by remember { mutableStateOf<Profile?>(null) }
    var deleteSub by remember { mutableStateOf<Subscription?>(null) }
    var updating by remember { mutableStateOf(setOf<String>()) }

    fun importText(text: String?) {
        if (text.isNullOrBlank()) {
            context.toast(context.getString(R.string.import_empty)); return
        }
        val parsed = LinkParser.parseBatch(text)
        if (parsed.isEmpty()) {
            context.toast(context.getString(R.string.import_none))
        } else {
            parsed.forEach { Store.saveProfile(it) }
            context.toast(context.getString(R.string.import_ok, parsed.size))
        }
    }

    fun runUpdate(sub: Subscription) {
        if (updating.contains(sub.id)) return
        updating = updating + sub.id
        scope.launch {
            val result = SubscriptionManager.update(context, sub)
            updating = updating - sub.id
            if (result.ok) context.toast(context.getString(R.string.sub_updated, result.count))
            else context.toast(context.getString(R.string.sub_update_failed, result.error ?: ""))
        }
    }

    // Group servers under their subscription, with manual/orphan servers last.
    val groups: List<Triple<String, Subscription?, List<Profile>>> = remember(profiles, subs) {
        buildList {
            for (sub in subs) {
                val list = profiles.filter { it.subId == sub.id }
                add(Triple(sub.name, sub, list))
            }
            val manual = profiles.filter { p -> subs.none { it.id == p.subId } }
            if (manual.isNotEmpty()) add(Triple("", null, manual))
        }
    }

    Column(modifier = modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.tab_servers),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = cs.onBackground,
                modifier = Modifier.weight(1f),
            )
            if (testing) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
            } else {
                IconButton(onClick = { PingService.testAll(context, profiles) }) {
                    Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.ping_all), tint = cs.secondary)
                }
            }
            Box {
                IconButton(onClick = { addMenuOpen = true }) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add), tint = cs.primary)
                }
                DropdownMenu(expanded = addMenuOpen, onDismissRequest = { addMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.sub_add_title)) },
                        onClick = { addMenuOpen = false; subDialog = Subscription() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.import_clipboard)) },
                        onClick = {
                            addMenuOpen = false
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            importText(clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString())
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.import_manual)) },
                        onClick = { addMenuOpen = false; manualDialog = true },
                    )
                }
            }
        }

        if (profiles.isEmpty() && subs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.servers_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(32.dp),
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                for (group in groups) {
                    val (name, sub, list) = group
                    if (sub != null) {
                        item(key = "sub_${sub.id}") {
                            SubscriptionHeader(
                                sub = sub,
                                serverCount = list.size,
                                isUpdating = updating.contains(sub.id),
                                onUpdate = { runUpdate(sub) },
                                onEdit = { subDialog = sub },
                                onDelete = { deleteSub = sub },
                            )
                        }
                    } else if (list.isNotEmpty()) {
                        item(key = "manual_header") { GroupLabel(stringResource(R.string.manual_group)) }
                    }
                    items(list, key = { it.id }) { profile ->
                        ServerCard(
                            profile = profile,
                            selected = profile.id == selectedId,
                            ping = pings[profile.id],
                            onClick = { onSelect(profile.id) },
                            onPing = { PingService.testOne(context, profile) },
                            onQr = { qrProfile = profile },
                            onCopy = {
                                val link = profile.rawLink
                                if (link.isNullOrBlank()) context.toast(context.getString(R.string.no_link))
                                else {
                                    val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cb.setPrimaryClip(ClipData.newPlainText("link", link))
                                    context.toast(context.getString(R.string.copied))
                                }
                            },
                            onDelete = { deleteProfile = profile },
                        )
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }

    // ---- dialogs ----

    if (manualDialog) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { manualDialog = false },
            title = { Text(stringResource(R.string.import_manual)) },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    placeholder = { Text("vless:// …") }, minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = { TextButton(onClick = { manualDialog = false; importText(text) }) { Text(stringResource(R.string.add)) } },
            dismissButton = { TextButton(onClick = { manualDialog = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    subDialog?.let { sub ->
        val isNew = subs.none { it.id == sub.id }
        SubscriptionDialog(
            title = stringResource(if (isNew) R.string.sub_add_title else R.string.sub_edit_title),
            initial = sub,
            onDismiss = { subDialog = null },
            onSave = { edited ->
                subDialog = null
                Store.saveSubscription(edited)
                if (isNew) runUpdate(edited)
            },
        )
    }

    qrProfile?.let { profile ->
        Dialog(onDismissRequest = { qrProfile = null }) {
            Card(shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(profile.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(12.dp))
                    val bitmap = remember(profile.id) { Utils.qrBitmap(profile.rawLink ?: "") }
                    if (bitmap != null) {
                        Image(bitmap = bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.size(260.dp))
                    } else {
                        Text(stringResource(R.string.no_link))
                    }
                }
            }
        }
    }

    deleteProfile?.let { profile ->
        AlertDialog(
            onDismissRequest = { deleteProfile = null },
            title = { Text(stringResource(R.string.delete_server_title)) },
            text = { Text(profile.name) },
            confirmButton = { TextButton(onClick = { Store.deleteProfile(profile.id); deleteProfile = null }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { deleteProfile = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    deleteSub?.let { sub ->
        AlertDialog(
            onDismissRequest = { deleteSub = null },
            title = { Text(stringResource(R.string.delete_sub_title)) },
            text = { Text(stringResource(R.string.delete_sub_text, sub.name)) },
            confirmButton = { TextButton(onClick = { Store.deleteSubscription(sub.id); deleteSub = null }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { deleteSub = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp),
    )
}

@Composable
private fun SubscriptionHeader(
    sub: Subscription,
    serverCount: Int,
    isUpdating: Boolean,
    onUpdate: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, top = 14.dp, bottom = 4.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surfaceVariant),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(sub.name, style = MaterialTheme.typography.titleSmall, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        stringResource(R.string.sub_servers_count, serverCount),
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                    )
                }
                if (isUpdating) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = onUpdate) { Icon(Icons.Filled.Refresh, contentDescription = null, tint = cs.secondary) }
                }
                IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = null, tint = cs.onSurfaceVariant) }
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = null, tint = cs.error) }
            }

            if (sub.total > 0) {
                val used = sub.upload.coerceAtLeast(0) + sub.download.coerceAtLeast(0)
                val fraction = (used.toFloat() / sub.total.toFloat()).coerceIn(0f, 1f)
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                val df = remember { SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()) }
                val extra = buildString {
                    append(stringResource(R.string.sub_traffic, Utils.formatBytes(used), Utils.formatBytes(sub.total)))
                    if (sub.expire > 0) append("  ·  " + stringResource(R.string.sub_expires, df.format(Date(sub.expire * 1000))))
                }
                Text(extra, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ServerCard(
    profile: Profile,
    selected: Boolean,
    ping: Long?,
    onClick: () -> Unit,
    onPing: () -> Unit,
    onQr: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var menuOpen by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp).clickable { onClick() },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = if (selected) cs.primaryContainer else cs.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(10.dp).clip(CircleShape)
                    .background(if (selected) cs.primary else cs.outline)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    profile.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (selected) cs.onPrimaryContainer else cs.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${profile.protoLabel()} · ${profile.transportLabel()}",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            val pingText = when {
                ping == null -> "—"
                ping < 0 -> "×"
                else -> "$ping ms"
            }
            val pingColor = when {
                ping == null -> cs.onSurfaceVariant
                ping < 0 -> cs.error
                ping < 400 -> cs.primary
                else -> cs.tertiary
            }
            Text(pingText, style = MaterialTheme.typography.labelLarge, color = pingColor, modifier = Modifier.clickable { onPing() })
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = null, tint = cs.onSurfaceVariant) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.show_qr)) }, onClick = { menuOpen = false; onQr() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.copy_link)) }, onClick = { menuOpen = false; onCopy() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = { menuOpen = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun SubscriptionDialog(
    title: String,
    initial: Subscription,
    onDismiss: () -> Unit,
    onSave: (Subscription) -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var url by remember { mutableStateOf(initial.url) }
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text(stringResource(R.string.sub_name)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = url, onValueChange = { url = it },
                    label = { Text(stringResource(R.string.sub_url)) }, placeholder = { Text("https://…") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (url.isBlank() || !url.trim().startsWith("http")) {
                    context.toast(context.getString(R.string.sub_url_invalid))
                } else {
                    onSave(initial.copy(name = name.trim().ifEmpty { "Sub" }, url = url.trim()))
                }
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
