package com.grka.xray.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.grka.xray.desktop.core.CoreRuntime
import com.grka.xray.desktop.data.Store
import com.grka.xray.desktop.ui.App

fun main() {
    Store.init()
    // A crashed or force-quit app must never leave the system proxy pointing at
    // a core that is no longer running — that would take the machine offline.
    Runtime.getRuntime().addShutdownHook(Thread { CoreRuntime.shutdownHook() })

    application {
        val windowState = rememberWindowState(size = DpSize(1020.dp, 720.dp))
        Window(
            onCloseRequest = {
                CoreRuntime.shutdownHook()
                exitApplication()
            },
            title = "${AppConfig.APP_NAME} ${AppVersion.name}",
            state = windowState,
        ) {
            App()
        }
    }
}
