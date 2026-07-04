package com.grka.xray.ui.screens

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.grka.xray.R
import com.grka.xray.data.Store
import com.grka.xray.data.Subscription
import com.grka.xray.net.SubscriptionManager
import com.grka.xray.ui.toast
import com.grka.xray.util.Utils
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SubscriptionsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme

    val subs by Store.subsFlow.collectAsState()
    val profiles by Store.profilesFlow.collectAsState()

    var editSub by remember { mutableStateOf<Subscription?>(null) }
    var addDialog by remember { mutableStateOf(false) }
    var deleteSub by remember { mutableStateOf<Subscription?>(null) }
    var updating by remember { mutableStateOf(setOf<String>()) }

    fun runUpdate(sub: Subscription) {
        if (updating.contains(sub.id)) return
        updating = updating + sub.id
        scope.launch {
            val result = SubscriptionManager.update(context, sub)
            updating = updating - sub.id
            if (result.ok) {
                context.toast(context.getString(R.string.sub_updated, result.count))
            } else {
                context.toast(context.getString(R.string.sub_update_failed, result.error ?: ""))
            }
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
                text = stringResource(R.string.tab_subs),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = cs.onBackground,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { subs.forEach { runUpdate(it) } }) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.update_all), tint = cs.secondary)
            }
            IconButton(onClick = { addDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add), tint = cs.primary)
            }
        }

        if (subs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.subs_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(32.dp),
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(subs, key = { it.id }) { sub ->
                    val count = profiles.count { it.subId == sub.id }
                    SubscriptionCard(
                        sub = sub,
                        serverCount = count,
                        isUpdating = updating.contains(sub.id),
                        onUpdate = { runUpdate(sub) },
                        onEdit = { editSub = sub },
                        onDelete = { deleteSub = sub },
                    )
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }

    if (addDialog) {
        SubscriptionDialog(
            title = stringResource(R.string.sub_add_title),
            initial = Subscription(),
            onDismiss = { addDialog = false },
            onSave = { sub ->
                addDialog = false
                Store.saveSubscription(sub)
                runUpdate(sub)
            },
        )
    }

    editSub?.let { sub ->
        SubscriptionDialog(
            title = stringResource(R.string.sub_edit_title),
            initial = sub,
            onDismiss = { editSub = null },
            onSave = { edited ->
                editSub = null
                Store.saveSubscription(edited)
            },
        )
    }

    deleteSub?.let { sub ->
        AlertDialog(
            onDismissRequest = { deleteSub = null },
            title = { Text(stringResource(R.string.delete_sub_title)) },
            text = { Text(stringResource(R.string.delete_sub_text, sub.name)) },
            confirmButton = {
                TextButton(onClick = {
                    Store.deleteSubscription(sub.id)
                    deleteSub = null
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteSub = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
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
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.sub_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.sub_url)) },
                    placeholder = { Text("https://…") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (url.isBlank() || !url.trim().startsWith("http")) {
                    context.toast(context.getString(R.string.sub_url_invalid))
                } else {
                    onSave(
                        initial.copy(
                            name = name.trim().ifEmpty { "Sub" },
                            url = url.trim(),
                        )
                    )
                }
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun SubscriptionCard(
    sub: Subscription,
    serverCount: Int,
    isUpdating: Boolean,
    onUpdate: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = sub.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = cs.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = sub.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (isUpdating) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = onUpdate) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, tint = cs.secondary)
                    }
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = null, tint = cs.onSurfaceVariant)
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = null, tint = cs.error)
                }
            }

            Spacer(Modifier.height(6.dp))

            // Traffic usage from subscription-userinfo (if the panel provides it)
            if (sub.total > 0) {
                val used = (sub.upload.coerceAtLeast(0)) + (sub.download.coerceAtLeast(0))
                val fraction = (used.toFloat() / sub.total.toFloat()).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        R.string.sub_traffic,
                        Utils.formatBytes(used),
                        Utils.formatBytes(sub.total)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
            }

            val dateFormat = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
            val info = buildString {
                append(stringResource(R.string.sub_servers_count, serverCount))
                if (sub.expire > 0) {
                    append("  ·  ")
                    append(stringResource(R.string.sub_expires, dateFormat.format(Date(sub.expire * 1000))))
                }
                if (sub.lastUpdate > 0) {
                    append("  ·  ")
                    append(stringResource(R.string.sub_last_update, dateFormat.format(Date(sub.lastUpdate))))
                }
            }
            Text(
                text = info,
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
        }
    }
}
