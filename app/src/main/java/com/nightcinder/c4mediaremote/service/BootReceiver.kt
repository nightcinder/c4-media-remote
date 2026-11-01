package com.nightcinder.c4mediaremote.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nightcinder.c4mediaremote.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Restarts [MediaRemoteService] after a reboot or an app update, but only when a
 * bridge token has been configured. Without a token the service has nothing to
 * talk to, so it is left stopped until the user opens the app.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                val hasToken = SettingsRepository(appContext).loadToken().isNotBlank()
                if (hasToken) {
                    MediaRemoteService.start(appContext)
                    Log.i(TAG, "Started MediaRemoteService after $action")
                } else {
                    Log.i(TAG, "No token configured; not starting MediaRemoteService after $action")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start MediaRemoteService after $action: ${e.javaClass.simpleName}")
            } finally {
                pendingResult.finish()
                scope.cancel()
            }
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
