package com.nightcinder.c4mediaremote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.nightcinder.c4mediaremote.data.AppSettings
import com.nightcinder.c4mediaremote.data.Room
import com.nightcinder.c4mediaremote.data.SettingsRepository
import com.nightcinder.c4mediaremote.net.HttpControllerClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(repo: SettingsRepository, onStartSession: () -> Unit) {
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    var port by remember { mutableStateOf(AppSettings.DEFAULT_PORT.toString()) }
    var token by remember { mutableStateOf("") }
    var room by remember { mutableStateOf(Room.UPSTAIRS) }
    var locked by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val s = repo.settings.first()
        address = s.address
        port = s.port.toString()
        room = s.activeRoom
        locked = s.roomLocked
        token = repo.loadToken()
        loaded = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Controller settings", style = MaterialTheme.typography.titleLarge)

        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            label = { Text("Controller address (IP or host)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = port,
            onValueChange = { port = it.filter(Char::isDigit).take(5) },
            label = { Text("Port") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("Bearer token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )

        Text("Active room", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Room.entries.forEach { r ->
                FilterChip(
                    selected = room == r,
                    onClick = { room = r },
                    label = { Text(r.label) },
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = locked, onCheckedChange = { locked = it })
            Spacer(modifier = Modifier.width(8.dp))
            Text("Lock room")
        }

        Button(
            enabled = loaded,
            onClick = {
                scope.launch {
                    val parsedPort = port.toIntOrNull()?.takeIf { it in 1..65535 }
                        ?: AppSettings.DEFAULT_PORT
                    repo.save(AppSettings(address.trim(), parsedPort, room, locked))
                    repo.saveToken(token.trim())
                    message = "Saved"
                }
            },
        ) { Text("Save") }

        Button(
            enabled = loaded && !testing,
            onClick = {
                testing = true
                testResult = "Testing..."
                scope.launch {
                    val parsedPort = port.toIntOrNull()?.takeIf { it in 1..65535 }
                        ?: AppSettings.DEFAULT_PORT
                    val client = HttpControllerClient(
                        "http://${address.trim()}:$parsedPort",
                        token.trim(),
                    )
                    testResult = client.status().fold(
                        onSuccess = { s ->
                            buildString {
                                append("Connected. Version ${s.version}, active room ")
                                append(s.activeRoom.ifBlank { "unknown" })
                                s.volume?.let { append(", volume $it") }
                                s.muted?.let { append(", ${if (it) "muted" else "unmuted"}") }
                                s.power?.let { if (!it) append(", powered off") }
                                s.source?.let { append(", source $it") }
                                s.npTitle?.let { t ->
                                    append("\nNow playing: $t")
                                    s.npArtist?.let { append(" by $it") }
                                    s.npApp?.let { append(" (${it})") }
                                }
                            }
                        },
                        onFailure = { e -> "Failed: ${e.message ?: e.javaClass.simpleName}" },
                    )
                    testing = false
                }
            },
        ) { Text("Test connection") }

        if (testResult.isNotEmpty()) Text(testResult)

        Button(onClick = onStartSession) { Text("Start media session") }

        if (message.isNotEmpty()) Text(message)
    }
}
