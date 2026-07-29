package com.grka.xray.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grka.xray.desktop.core.CoreRuntime
import com.grka.xray.desktop.util.Platform
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/** AWT clipboard rather than Compose's: no experimental/deprecated API churn. */
private fun copyToClipboard(text: String) {
    runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }
}

@Composable
fun LogScreen() {
    val cs = MaterialTheme.colorScheme

    val logs by CoreRuntime.logs.collectAsState()
    val config by CoreRuntime.lastConfig.collectAsState()
    var showConfig by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.scrollToItem(logs.lastIndex)
    }

    Column(Modifier.fillMaxSize().padding(28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (showConfig) "Конфиг ядра" else "Логи ядра",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = cs.onBackground,
                modifier = Modifier.weight(1f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showConfig = !showConfig }) {
                    Text(if (showConfig) "К логам" else "Показать конфиг")
                }
                OutlinedButton(onClick = {
                    copyToClipboard(if (showConfig) config else logs.joinToString("\n"))
                }) { Text("Копировать") }
                if (!showConfig) {
                    Button(onClick = { CoreRuntime.clearLogs() }) { Text("Очистить") }
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            "Данные приложения: ${Platform.dataDir.absolutePath}",
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(14.dp))
                .background(cs.surface)
                .padding(14.dp),
        ) {
            if (showConfig) {
                Text(
                    text = config.ifBlank { "Ядро ещё не запускалось — конфиг появится после подключения." },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = cs.onSurface,
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                )
            } else if (logs.isEmpty()) {
                Text(
                    "Логов пока нет.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                )
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(logs) { line ->
                        Text(
                            text = line,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = if (line.contains("Error", true) || line.contains("Ошибка")) {
                                cs.error
                            } else {
                                cs.onSurfaceVariant
                            },
                            modifier = Modifier.padding(vertical = 1.dp),
                        )
                    }
                }
            }
        }
    }
}
