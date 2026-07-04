package com.grka.xray.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import com.grka.xray.data.Store

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Store.autoStart) return
        if (Store.selectedProfile() == null) return
        // Requires the user to have granted the VPN permission earlier
        if (VpnService.prepare(context) != null) return
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, XVpnService::class.java))
        }
    }
}
