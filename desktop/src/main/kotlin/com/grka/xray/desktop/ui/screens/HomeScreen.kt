package com.grka.xray.desktop.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.grka.xray.desktop.AppConfig
import com.grka.xray.desktop.core.ConnState
import com.grka.xray.desktop.core.CoreRuntime
import com.grka.xray.desktop.data.Store
import com.grka.xray.desktop.net.PingService
import com.grka.xray.desktop.ui.SegmentedSwitch
import com.grka.xray.desktop.ui.StatTile
import com.grka.xray.desktop.util.Utils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(onOpenServers: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    val state by CoreRuntime.state.collectAsState()
    val traffic by CoreRuntime.traffic.collectAsState()
    val connectedAt by CoreRuntime.connectedAt.collectAsState()
    val lastError by CoreRuntime.lastError.collectAsState()
    val profiles by Store.profilesFlow.collectAsState()
    val selectedId by Store.selectedIdFlow.collectAsState()
    val mode by Store.modeFlow.collectAsState()

    val selected = profiles.firstOrNull { it.id == selectedId }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state) {
        while (state == ConnState.CONNECTED) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    var pingResult by remember { mutableStateOf<String?>(null) }
    var pingRunning by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        NetworkModeChip()

        Spacer(Modifier.height(18.dp))

        PowerButton(
            state = state,
            enabled = selected != null,
            onClick = {
                scope.launch {
                    when (state) {
                        ConnState.CONNECTED -> CoreRuntime.disconnect()
                        ConnState.DISCONNECTED -> selected?.let { CoreRuntime.connect(it) }
                        else -> {}
                    }
                }
            },
        )

        Spacer(Modifier.height(18.dp))

        Text(
            text = when (state) {
                ConnState.CONNECTED -> "Защищено"
                ConnState.CONNECTING -> "Подключение…"
                ConnState.STOPPING -> "Отключение…"
                ConnState.DISCONNECTED -> if (selected == null) "Выберите сервер" else "Отключено"
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (state == ConnState.CONNECTED) cs.primary else cs.onBackground,
        )

        if (state == ConnState.CONNECTED) {
            Text(
                text = Utils.formatDuration(now - connectedAt),
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(22.dp))

        // The Karing-style switch: right below the power button, always live —
        // flipping it while connected restarts the core with new routing but
        // keeps the OS-level plumbing (and the admin prompt) in place.
        SegmentedSwitch(
            options = listOf(
                AppConfig.MODE_RULE to "Правила",
                AppConfig.MODE_GLOBAL to "Глобально",
                AppConfig.MODE_DIRECT to "Прямое",
            ),
            selected = mode,
            onSelect = { scope.launch { CoreRuntime.applyMode(it) } },
        )

        Spacer(Modifier.height(8.dp))
        Text(
            text = when (mode) {
                AppConfig.MODE_GLOBAL -> "Весь трафик идёт через сервер, кроме локальной сети"
                AppConfig.MODE_DIRECT -> "Трафик идёт напрямую, ядро остаётся запущенным"
                else -> "Маршруты из подписки, шаблона или встроенного набора правил"
            },
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
        )

        Spacer(Modifier.height(24.dp))

        ServerCard(
            title = selected?.name?.takeIf { it.isNotBlank() } ?: "Сервер не выбран",
            subtitle = selected?.let { "${it.protoLabel()} · ${it.transportLabel()} · ${it.server}:${it.port}" }
                ?: "Откройте «Серверы» и добавьте подписку или ссылку",
            pingText = pingResult,
            pingRunning = pingRunning,
            onPing = {
                val profile = selected
                if (profile != null && !pingRunning) {
                    pingRunning = true
                    scope.launch {
                        // Connected: measure what the user actually gets, through
                        // the core. Idle: a plain TCP handshake to the server.
                        val ms = if (state == ConnState.CONNECTED) {
                            PingService.proxyDelay(Store.httpPort)
                        } else {
                            PingService.tcpPing(profile)
                        }
                        pingResult = if (ms >= 0) "$ms мс" else "нет ответа"
                        pingRunning = false
                    }
                }
            },
            onOpenServers = onOpenServers,
        )

        Spacer(Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatTile("Отдача", Utils.formatSpeed(traffic.upSpeed), Modifier.weight(1f))
            StatTile("Загрузка", Utils.formatSpeed(traffic.downSpeed), Modifier.weight(1f))
            StatTile("Всего ↑", Utils.formatBytes(traffic.upTotal), Modifier.weight(1f))
            StatTile("Всего ↓", Utils.formatBytes(traffic.downTotal), Modifier.weight(1f))
        }

        if (lastError != null && state == ConnState.DISCONNECTED) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = lastError.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = cs.error,
            )
        }
    }
}

@Composable
private fun NetworkModeChip() {
    val cs = MaterialTheme.colorScheme
    val networkMode by Store.networkModeFlow.collectAsState()
    val label = if (networkMode == AppConfig.NET_TUN) {
        "Режим сети: TUN (весь трафик)"
    } else {
        "Режим сети: системный прокси"
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = cs.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(cs.surfaceVariant)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

@Composable
private fun PowerButton(state: ConnState, enabled: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val transition = rememberInfiniteTransition()
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
    )

    val ringColor = when {
        !enabled -> cs.outline
        state == ConnState.CONNECTED -> cs.primary
        else -> cs.onSurfaceVariant
    }

    Box(
        modifier = Modifier
            .size(196.dp)
            .clip(RoundedCornerShape(percent = 50))
            .clickable(enabled = enabled && (state == ConnState.CONNECTED || state == ConnState.DISCONNECTED)) {
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 10.dp.toPx()
            val inset = stroke / 2 + 6.dp.toPx()
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)

            drawArc(
                color = ringColor.copy(alpha = 0.18f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )

            val progressSweep = when (state) {
                ConnState.CONNECTED -> 360f
                ConnState.CONNECTING, ConnState.STOPPING -> 90f
                ConnState.DISCONNECTED -> 0f
            }
            if (progressSweep > 0f) {
                drawArc(
                    brush = Brush.sweepGradient(listOf(ringColor, cs.secondary, ringColor)),
                    startAngle = if (state == ConnState.CONNECTED) -90f else sweep,
                    sweepAngle = progressSweep,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }

        Text(
            text = "⏻",
            style = MaterialTheme.typography.displayMedium,
            color = ringColor,
        )
    }
}

@Composable
private fun ServerCard(
    title: String,
    subtitle: String,
    pingText: String?,
    pingRunning: Boolean,
    onPing: () -> Unit,
    onOpenServers: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(cs.surface)
            .clickable { onOpenServers() }
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (pingText != null) {
            Text(
                text = pingText,
                style = MaterialTheme.typography.bodyMedium,
                color = cs.secondary,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        TextButton(onClick = onPing, enabled = !pingRunning) {
            Text(if (pingRunning) "…" else "Пинг")
        }
    }
}
