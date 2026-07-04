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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.grka.xray.net.PingService
import com.grka.xray.ui.toast
import com.grka.xray.util.Utils

@Composable
fun ServersScreen(
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit,
) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme

    val profiles by Store.profilesFlow.collectAsState()
    val selectedId by Store.selectedIdFlow.collectAsState()
    val pings by PingService.results.collectAsState()
    val testing by PingService.testing.collectAsState()

    var addMenuOpen by remember { mutableStateOf(false) }
    var manualDialog by remember { mutableStateOf(false) }
    var qrProfile by remember { mutableStateOf<Profile?>(null) }
    var deleteProfile by remember { mutableStateOf<Profile?>(null) }

    fun importText(text: String?) {
        if (text.isNullOrBlank()) {
            context.toast(context.getString(R.string.import_empty))
            return
        }
        val parsed = LinkParser.parseBatch(text)
        if (parsed.isEmpty()) {
            context.toast(context.getString(R.string.import_none))
        } else {
            parsed.forEach { Store.saveProfile(it) }
            context.toast(context.getString(R.string.import_ok, parsed.size))
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
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
                        text = { Text(stringResource(R.string.import_clipboard)) },
                        onClick = {
                            addMenuOpen = false
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                            importText(text)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.import_manual)) },
                        onClick = {
                            addMenuOpen = false
                            manualDialog = true
                        },
                    )
                }
            }
        }

        if (profiles.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.servers_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
            ) {
                items(profiles, key = { it.id }) { profile ->
                    ServerCard(
                        profile = profile,
                        selected = profile.id == selectedId,
                        ping = pings[profile.id],
                        onClick = { onSelect(profile.id) },
                        onPing = { PingService.testOne(context, profile) },
                        onQr = { qrProfile = profile },
                        onCopy = {
                            val link = profile.rawLink
                            if (link.isNullOrBlank()) {
                                context.toast(context.getString(R.string.no_link))
                            } else {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("link", link))
                                context.toast(context.getString(R.string.copied))
                            }
                        },
                        onDelete = { deleteProfile = profile },
                    )
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }

    if (manualDialog) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { manualDialog = false },
            title = { Text(stringResource(R.string.import_manual)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("vless:// …") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    manualDialog = false
                    importText(text)
                }) { Text(stringResource(R.string.add)) }
            },
            dismissButton = {
                TextButton(onClick = { manualDialog = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    qrProfile?.let { profile ->
        Dialog(onDismissRequest = { qrProfile = null }) {
            Card(shape = RoundedCornerShape(24.dp)) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = profile.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(12.dp))
                    val bitmap = remember(profile.id) { Utils.qrBitmap(profile.rawLink ?: "") }
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.size(260.dp),
                        )
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
            confirmButton = {
                TextButton(onClick = {
                    Store.deleteProfile(profile.id)
                    deleteProfile = null
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteProfile = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) cs.primaryContainer else cs.surfaceVariant
        ),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (selected) cs.primary else cs.outline)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = profile.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (selected) cs.onPrimaryContainer else cs.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${profile.protoLabel()} · ${profile.transportLabel()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
            Text(
                text = pingText,
                style = MaterialTheme.typography.labelLarge,
                color = pingColor,
                modifier = Modifier.clickable { onPing() },
            )
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = null, tint = cs.onSurfaceVariant)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.show_qr)) },
                        onClick = {
                            menuOpen = false
                            onQr()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.copy_link)) },
                        onClick = {
                            menuOpen = false
                            onCopy()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete)) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}
