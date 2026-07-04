package com.grka.xray.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.grka.xray.R
import com.grka.xray.core.ConnState
import com.grka.xray.core.CoreRuntime
import com.grka.xray.data.Store
import com.grka.xray.ui.toast
import com.grka.xray.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenServers: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme

    val state by CoreRuntime.state.collectAsState()
    val traffic by CoreRuntime.traffic.collectAsState()
    val connectedAt by CoreRuntime.connectedAt.collectAsState()
    val lastError by CoreRuntime.lastError.collectAsState()
    val profiles by Store.profilesFlow.collectAsState()
    val selectedId by Store.selectedIdFlow.collectAsState()

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

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(cs.background, cs.surface)))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = cs.onBackground,
            )
            Text(
                text = when (state) {
                    ConnState.CONNECTED -> stringResource(R.string.state_connected)
                    ConnState.CONNECTING -> stringResource(R.string.state_connecting)
                    ConnState.STOPPING -> stringResource(R.string.state_stopping)
                    ConnState.DISCONNECTED -> stringResource(R.string.state_disconnected)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (state == ConnState.CONNECTED) cs.primary else cs.onSurfaceVariant,
            )

            Spacer(Modifier.height(36.dp))

            PowerButton(state = state) {
                pingResult = null
                when (state) {
                    ConnState.DISCONNECTED -> {
                        if (selected == null) {
                            context.toast(context.getString(R.string.error_no_server))
                            onOpenServers()
                        } else {
                            onConnect()
                        }
                    }

                    ConnState.CONNECTED -> onDisconnect()
                    else -> {}
                }
            }

            Spacer(Modifier.height(12.dp))
            if (state == ConnState.CONNECTED) {
                Text(
                    text = Utils.formatDuration(now - connectedAt),
                    style = MaterialTheme.typography.titleMedium,
                    color = cs.onSurfaceVariant,
                )
            } else {
                Spacer(Modifier.height(24.dp))
            }

            Spacer(Modifier.height(24.dp))

            // Selected server card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenServers() },
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = cs.surfaceVariant),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (state == ConnState.CONNECTED) cs.primary else cs.outline)
                    )
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = selected?.name ?: stringResource(R.string.no_server_hint),
                            style = MaterialTheme.typography.titleSmall,
                            color = cs.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (selected != null) {
                            Text(
                                text = "${selected.protoLabel()} · ${selected.transportLabel()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        text = stringResource(R.string.change),
                        style = MaterialTheme.typography.labelLarge,
                        color = cs.secondary,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // Traffic stats
            if (state == ConnState.CONNECTED) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StatCard(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.stat_download),
                        value = Utils.formatSpeed(traffic.downSpeed),
                        sub = Utils.formatBytes(traffic.downTotal),
                        accent = cs.secondary,
                    )
                    StatCard(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.stat_upload),
                        value = Utils.formatSpeed(traffic.upSpeed),
                        sub = Utils.formatBytes(traffic.upTotal),
                        accent = cs.tertiary,
                    )
                }

                Spacer(Modifier.height(14.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = cs.surfaceVariant),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.latency_check),
                                style = MaterialTheme.typography.titleSmall,
                                color = cs.onSurface,
                            )
                            Text(
                                text = pingResult ?: stringResource(R.string.latency_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant,
                            )
                        }
                        TextButton(
                            enabled = !pingRunning,
                            onClick = {
                                pingRunning = true
                                pingResult = null
                                scope.launch {
                                    val ms = withContext(Dispatchers.IO) {
                                        CoreRuntime.measureConnectedDelay()
                                    }
                                    pingResult = if (ms >= 0) {
                                        context.getString(R.string.latency_ok, ms)
                                    } else {
                                        context.getString(R.string.latency_fail)
                                    }
                                    pingRunning = false
                                }
                            }
                        ) {
                            Text(if (pingRunning) "…" else stringResource(R.string.test))
                        }
                    }
                }
            }

            // Error card
            if (state == ConnState.DISCONNECTED && !lastError.isNullOrBlank()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = cs.error.copy(alpha = 0.12f)),
                ) {
                    Text(
                        text = lastError.orEmpty(),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.error,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StatCard(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    sub: String,
    accent: Color,
) {
    val cs = MaterialTheme.colorScheme
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(text = label, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = accent,
            )
            Text(text = sub, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun PowerButton(state: ConnState, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme

    val infinite = rememberInfiniteTransition(label = "pulse")
    val pulse by infinite.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
        label = "pulseScale",
    )
    val scale = if (state == ConnState.CONNECTING || state == ConnState.STOPPING) pulse else 1f

    val ringBrush = when (state) {
        ConnState.CONNECTED -> Brush.sweepGradient(listOf(cs.primary, cs.secondary, cs.primary))
        ConnState.CONNECTING, ConnState.STOPPING ->
            Brush.sweepGradient(listOf(cs.secondary, cs.surfaceVariant, cs.secondary))
        else -> Brush.sweepGradient(listOf(cs.outline, cs.surfaceVariant, cs.outline))
    }
    val iconColor = when (state) {
        ConnState.CONNECTED -> cs.primary
        ConnState.CONNECTING, ConnState.STOPPING -> cs.secondary
        else -> cs.onSurfaceVariant
    }

    Box(
        modifier = Modifier
            .size(190.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    listOf(cs.surfaceVariant, cs.surface)
                )
            )
            .border(BorderStroke(5.dp, ringBrush), CircleShape)
            .clickable(enabled = state == ConnState.CONNECTED || state == ConnState.DISCONNECTED) {
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(64.dp)) {
            val strokeWidth = 7.dp.toPx()
            drawArc(
                color = iconColor,
                startAngle = -60f,
                sweepAngle = 300f,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                size = Size(size.width, size.height),
                topLeft = Offset.Zero,
            )
            drawLine(
                color = iconColor,
                start = Offset(size.width / 2f, 0f),
                end = Offset(size.width / 2f, size.height * 0.42f),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}
