package com.grka.xray.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.grka.xray.R
import com.grka.xray.core.ConnState
import com.grka.xray.core.CoreRuntime
import com.grka.xray.data.Store
import com.grka.xray.ui.screens.HomeScreen
import com.grka.xray.ui.screens.ServersScreen
import com.grka.xray.ui.screens.SettingsScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun Context.toast(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

@Composable
fun MainScreen(
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    // Selecting a server switches to it, returns to Home, and — if a tunnel is
    // already up — restarts it on the new server.
    val switchProfile: (String) -> Unit = { id ->
        val wasActive = CoreRuntime.state.value == ConnState.CONNECTED ||
                CoreRuntime.state.value == ConnState.CONNECTING
        Store.selectProfile(id)
        tab = 0
        if (wasActive) {
            onDisconnect()
            scope.launch {
                delay(700)
                onConnect()
            }
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_home)) },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Filled.List, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_servers)) },
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_settings)) },
                )
            }
        }
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (tab) {
            0 -> HomeScreen(modifier, onConnect, onDisconnect, onOpenServers = { tab = 1 })
            1 -> ServersScreen(modifier, switchProfile)
            else -> SettingsScreen(modifier)
        }
    }
}
