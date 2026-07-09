package com.grka.xray.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grka.xray.R
import com.grka.xray.ui.theme.GrKaXTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LogActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GrKaXTheme(com.grka.xray.data.Store.theme) {
                LogScreen()
            }
        }
    }
}

@Composable
private fun LogScreen() {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(true) }
    var logs by remember { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }

    androidx.compose.runtime.LaunchedEffect(reload) {
        loading = true
        logs = withContext(Dispatchers.IO) { readLogcat() }
        loading = false
    }

    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    stringResource(R.string.logs_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = cs.onBackground,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                TextButton(onClick = { reload++ }) { Text(stringResource(R.string.logs_refresh)) }
                TextButton(onClick = {
                    val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cb.setPrimaryClip(ClipData.newPlainText("logs", logs))
                    context.toast(context.getString(R.string.copied))
                }) { Text(stringResource(R.string.logs_copy)) }
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { runCatching { Runtime.getRuntime().exec(arrayOf("logcat", "-c")).waitFor() } }
                        reload++
                    }
                }) { Text(stringResource(R.string.logs_clear)) }
            }

            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                Box(
                    Modifier.fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(12.dp)
                ) {
                    Text(
                        text = logs.ifBlank { stringResource(R.string.logs_empty) },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = cs.onSurface,
                    )
                }
            }
        }
    }
}

/** Reads this app's own recent logcat (own UID only; no special permission). */
private fun readLogcat(): String = try {
    val process = Runtime.getRuntime().exec(
        arrayOf("logcat", "-d", "-v", "time", "-t", "800")
    )
    val text = process.inputStream.bufferedReader().use { it.readText() }
    // Keep the most relevant lines (app tag + core) near the end.
    text.lineSequence()
        .filter { it.isNotBlank() }
        .toList()
        .takeLast(500)
        .joinToString("\n")
} catch (e: Exception) {
    "Failed to read logs: ${e.message}"
}
