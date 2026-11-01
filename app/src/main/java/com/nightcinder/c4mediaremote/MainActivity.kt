package com.nightcinder.c4mediaremote

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.nightcinder.c4mediaremote.data.SettingsRepository
import com.nightcinder.c4mediaremote.service.MediaRemoteService
import com.nightcinder.c4mediaremote.ui.DiagnosticsScreen
import com.nightcinder.c4mediaremote.ui.SettingsScreen

class MainActivity : ComponentActivity() {

    private enum class Screen { SETTINGS, DIAGNOSTICS }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = SettingsRepository(applicationContext)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var screen by remember { mutableStateOf(Screen.SETTINGS) }
                    val notificationPermission = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission(),
                    ) { _ ->
                        // Denied is fine: the service still runs, the notification just may not show.
                    }

                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(modifier = Modifier.padding(8.dp)) {
                            TextButton(onClick = { screen = Screen.SETTINGS }) { Text("Settings") }
                            TextButton(onClick = { screen = Screen.DIAGNOSTICS }) { Text("Diagnostics") }
                        }
                        when (screen) {
                            Screen.SETTINGS -> SettingsScreen(
                                repo = repo,
                                onStartSession = {
                                    val needsNotifPermission =
                                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                            ContextCompat.checkSelfPermission(
                                                this@MainActivity,
                                                Manifest.permission.POST_NOTIFICATIONS,
                                            ) != PackageManager.PERMISSION_GRANTED
                                    if (needsNotifPermission) {
                                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                    MediaRemoteService.start(this@MainActivity)
                                },
                            )
                            Screen.DIAGNOSTICS -> DiagnosticsScreen()
                        }
                    }
                }
            }
        }
    }
}
