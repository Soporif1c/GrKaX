package com.grka.xray.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.grka.xray.desktop.AppConfig
import com.grka.xray.desktop.AppVersion
import com.grka.xray.desktop.core.ConnState
import com.grka.xray.desktop.core.CoreRuntime
import com.grka.xray.desktop.data.Store
import com.grka.xray.desktop.ui.screens.HomeScreen
import com.grka.xray.desktop.ui.screens.LogScreen
import com.grka.xray.desktop.ui.screens.ServersScreen
import com.grka.xray.desktop.ui.screens.SettingsScreen
import com.grka.xray.desktop.ui.theme.GrKaXTheme
import com.grka.xray.desktop.util.Platform

/**
 * On macOS the window draws under its own title bar (see Main.kt), which buys
 * the theme those extra pixels but puts the traffic lights on top of whatever
 * sits in the corner. Everything that must stay clickable starts below this.
 */
private val TitleBarInset = if (Platform.isMac) 28.dp else 0.dp

enum class Tab(val title: String, val glyph: String) {
    HOME("Главная", "◉"),
    SERVERS("Серверы", "☰"),
    SETTINGS("Настройки", "⚙"),
    LOGS("Логи", "▤"),
}

@Composable
fun App() {
    val theme by Store.themeFlow.collectAsState()
    GrKaXTheme(theme) {
        var tab by remember { mutableStateOf(Tab.HOME) }
        val cs = MaterialTheme.colorScheme

        Row(Modifier.fillMaxSize().background(cs.background)) {
            NavigationSidebar(selected = tab, onSelect = { tab = it })
            Box(Modifier.weight(1f).fillMaxHeight().padding(top = TitleBarInset)) {
                when (tab) {
                    Tab.HOME -> HomeScreen(onOpenServers = { tab = Tab.SERVERS })
                    Tab.SERVERS -> ServersScreen(onProfileChosen = { tab = Tab.HOME })
                    Tab.SETTINGS -> SettingsScreen()
                    Tab.LOGS -> LogScreen()
                }
            }
        }
    }
}

@Composable
private fun NavigationSidebar(selected: Tab, onSelect: (Tab) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val state by CoreRuntime.state.collectAsState()

    Column(
        modifier = Modifier
            .width(210.dp)
            .fillMaxHeight()
            .background(cs.surface)
            .padding(
                top = 20.dp + TitleBarInset,
                bottom = 20.dp,
                start = 14.dp,
                end = 14.dp,
            ),
    ) {
        Text(
            text = AppConfig.APP_NAME,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = cs.onSurface,
            modifier = Modifier.padding(start = 10.dp),
        )
        Text(
            text = when (state) {
                ConnState.CONNECTED -> "Подключено"
                ConnState.CONNECTING -> "Подключение…"
                ConnState.STOPPING -> "Отключение…"
                ConnState.DISCONNECTED -> "Отключено"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (state == ConnState.CONNECTED) cs.primary else cs.onSurfaceVariant,
            modifier = Modifier.padding(start = 10.dp, top = 2.dp),
        )

        Spacer(Modifier.height(24.dp))

        for (item in Tab.entries) {
            val active = item == selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (active) cs.primaryContainer else cs.surface)
                    .clickable { onSelect(item) }
                    .padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    item.glyph,
                    color = if (active) cs.onPrimaryContainer else cs.onSurfaceVariant,
                )
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) cs.onPrimaryContainer else cs.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
        }

        Spacer(Modifier.weight(1f))
        Text(
            text = "версия ${AppVersion.name}",
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}
