package com.grka.xray.ui

import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.compose.runtime.LaunchedEffect
import com.grka.xray.AppConfig
import com.grka.xray.R
import com.grka.xray.data.Store
import com.grka.xray.ui.theme.GrKaXTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PerAppActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val theme = Store.theme
            GrKaXTheme(theme) {
                PerAppScreen(onBack = { finish() })
            }
        }
    }
}

private data class AppEntry(val label: String, val pkg: String, val icon: Bitmap?)

@Composable
private fun PerAppScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme

    var mode by remember { mutableStateOf(Store.perAppMode) }
    val selected: SnapshotStateList<String> = remember { mutableStateListOf<String>().apply { addAll(Store.perAppSet) } }
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    val apps = remember { mutableStateListOf<AppEntry>() }

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) { loadApps(context) }
        apps.clear()
        apps.addAll(loaded)
        loading = false
    }

    // Persist on any change
    fun persist() {
        Store.perAppMode = mode
        Store.perAppSet = selected.toSet()
    }

    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).statusBarsPadding()
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.per_app_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold, color = cs.onBackground,
                )
            }

            // Mode chips
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ModeChip(stringResource(R.string.per_app_off), mode == AppConfig.PER_APP_OFF) { mode = AppConfig.PER_APP_OFF; persist() }
                ModeChip(stringResource(R.string.per_app_bypass), mode == AppConfig.PER_APP_BYPASS) { mode = AppConfig.PER_APP_BYPASS; persist() }
                ModeChip(stringResource(R.string.per_app_only), mode == AppConfig.PER_APP_ALLOW) { mode = AppConfig.PER_APP_ALLOW; persist() }
            }

            Text(
                text = when (mode) {
                    AppConfig.PER_APP_BYPASS -> stringResource(R.string.per_app_bypass_hint)
                    AppConfig.PER_APP_ALLOW -> stringResource(R.string.per_app_only_hint)
                    else -> stringResource(R.string.per_app_off_hint)
                },
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )

            OutlinedTextField(
                value = query, onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.per_app_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )

            Spacer(Modifier.height(8.dp))

            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                val enabled = mode != AppConfig.PER_APP_OFF
                val filtered = remember(query, apps.size) {
                    if (query.isBlank()) apps.toList()
                    else apps.filter { it.label.contains(query, true) || it.pkg.contains(query, true) }
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(filtered, key = { it.pkg }) { app ->
                        AppRow(
                            app = app,
                            checked = selected.contains(app.pkg),
                            enabled = enabled,
                            onToggle = {
                                if (selected.contains(app.pkg)) selected.remove(app.pkg) else selected.add(app.pkg)
                                persist()
                            },
                        )
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@Composable
private fun AppRow(app: AppEntry, checked: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth()
            .selectable(selected = checked, enabled = enabled, onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (app.icon != null) {
            Image(
                painter = BitmapPainter(app.icon.asImageBitmap()),
                contentDescription = null,
                modifier = Modifier.size(40.dp),
            )
        } else {
            Box(Modifier.size(40.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.bodyLarge, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(app.pkg, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Checkbox(checked = checked, onCheckedChange = { onToggle() }, enabled = enabled)
    }
}

private fun loadApps(context: android.content.Context): List<AppEntry> {
    val pm = context.packageManager
    val self = context.packageName
    val infos = try {
        pm.getInstalledApplications(0)
    } catch (e: Exception) {
        return emptyList()
    }
    return infos.asSequence()
        .filter { it.packageName != self }
        .filter { info ->
            // User-facing apps: non-system, or anything with a launcher entry
            (info.flags and ApplicationInfo.FLAG_SYSTEM) == 0 ||
                pm.getLaunchIntentForPackage(info.packageName) != null
        }
        .map { info ->
            val label = runCatching { pm.getApplicationLabel(info).toString() }.getOrDefault(info.packageName)
            val icon = runCatching { drawableToBitmap(pm.getApplicationIcon(info)) }.getOrNull()
            AppEntry(label, info.packageName, icon)
        }
        .sortedBy { it.label.lowercase() }
        .toList()
}

private fun drawableToBitmap(drawable: Drawable): Bitmap {
    if (drawable is BitmapDrawable && drawable.bitmap != null) {
        return drawable.bitmap
    }
    val size = 96
    val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else size
    val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else size
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bitmap
}
