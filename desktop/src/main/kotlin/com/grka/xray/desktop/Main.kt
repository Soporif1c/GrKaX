package com.grka.xray.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.grka.xray.desktop.core.ConnState
import com.grka.xray.desktop.core.CoreRuntime
import com.grka.xray.desktop.data.Store
import com.grka.xray.desktop.platform.DeepLink
import com.grka.xray.desktop.ui.App
import com.grka.xray.desktop.ui.TrayIconPainter
import kotlinx.coroutines.launch

fun main() {
    Store.init()
    DeepLink.register()
    // A crashed or force-quit app must never leave the system proxy pointing at
    // a core that is no longer running — that would take the machine offline.
    Runtime.getRuntime().addShutdownHook(Thread { CoreRuntime.shutdownHook() })

    application {
        val windowState = rememberWindowState(size = DpSize(1020.dp, 720.dp))
        var windowVisible by remember { mutableStateOf(true) }
        val scope = rememberCoroutineScope()

        val state by CoreRuntime.state.collectAsState()
        val mode by Store.modeFlow.collectAsState()
        val connected = state == ConnState.CONNECTED

        LaunchedEffect(Unit) {
            if (Store.autoConnect) {
                Store.selectedProfile()?.let { CoreRuntime.connect(it) }
            }
        }

        Tray(
            icon = TrayIconPainter(connected),
            tooltip = "${AppConfig.APP_NAME} — ${if (connected) "подключено" else "отключено"}",
            onAction = { windowVisible = true },
            menu = {
                Item(if (windowVisible) "Показать окно" else "Открыть GrKa X") {
                    windowVisible = true
                }
                Separator()
                Item(if (connected) "Отключить" else "Подключить") {
                    scope.launch {
                        if (connected) {
                            CoreRuntime.disconnect()
                        } else {
                            Store.selectedProfile()?.let { CoreRuntime.connect(it) }
                        }
                    }
                }
                Separator()
                // Same three modes as the switch on the home screen, so the
                // common action needs no window at all.
                for ((value, label) in listOf(
                    AppConfig.MODE_RULE to "Правила",
                    AppConfig.MODE_GLOBAL to "Глобально",
                    AppConfig.MODE_DIRECT to "Прямое",
                )) {
                    Item(if (mode == value) "● $label" else "   $label") {
                        scope.launch { CoreRuntime.applyMode(value) }
                    }
                }
                Separator()
                Item("Выход") {
                    CoreRuntime.shutdownHook()
                    exitApplication()
                }
            },
        )

        Window(
            onCloseRequest = {
                // Closing hides to the menu bar; the tunnel keeps running.
                // Quitting for real is an explicit choice in the tray menu.
                windowVisible = false
            },
            visible = windowVisible,
            title = "${AppConfig.APP_NAME} ${AppVersion.name}",
            state = windowState,
        ) {
            App()
        }
    }
}
