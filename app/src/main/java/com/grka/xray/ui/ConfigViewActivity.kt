package com.grka.xray.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grka.xray.R
import com.grka.xray.data.Store
import com.grka.xray.ui.theme.GrKaXTheme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

class ConfigViewActivity : ComponentActivity() {
    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_CONTENT = "content"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Config"
        val content = intent.getStringExtra(EXTRA_CONTENT).orEmpty()
        setContent {
            GrKaXTheme(Store.theme) {
                ConfigViewScreen(title, prettify(content))
            }
        }
    }
}

private val prettyJson = Json { prettyPrint = true }

private fun prettify(content: String): String = runCatching {
    prettyJson.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(content.trim()))
}.getOrDefault(content)

@Composable
private fun ConfigViewScreen(title: String, content: String) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme

    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = cs.onBackground,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                TextButton(onClick = {
                    val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cb.setPrimaryClip(ClipData.newPlainText("config", content))
                    context.toast(context.getString(R.string.copied))
                }) { Text(stringResource(R.string.logs_copy)) }
            }

            Box(
                Modifier.fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp)
            ) {
                Text(
                    text = content.ifBlank { stringResource(R.string.config_empty) },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = cs.onSurface,
                )
            }
        }
    }
}
