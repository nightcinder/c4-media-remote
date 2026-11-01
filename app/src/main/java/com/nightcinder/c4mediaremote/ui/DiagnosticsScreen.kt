package com.nightcinder.c4mediaremote.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.net.wifi.rtt.WifiRttManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

@Composable
fun DiagnosticsScreen() {
    val context = LocalContext.current
    val wifi = remember {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }
    val rttAvailable = remember {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT) &&
            (context.getSystemService(WifiRttManager::class.java)?.isAvailable == true)
    }

    var results by remember { mutableStateOf<List<ScanResult>>(emptyList()) }
    var status by remember { mutableStateOf("Idle") }

    @SuppressLint("MissingPermission")
    fun readResults() {
        results = readScanResults(wifi)
        status = "Showing ${results.size} access points"
    }

    @SuppressLint("MissingPermission")
    fun requestScan() {
        @Suppress("DEPRECATION")
        val started = wifi.startScan()
        status = if (started) "Scan requested, waiting for results" else "Scan throttled by system; showing last results"
        readResults()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) requestScan() else status = "Permission denied"
    }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                readResults()
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text("Diagnostics", style = MaterialTheme.typography.titleLarge)
            Text("WifiRttManager available: ${yesNo(rttAvailable)}")
            Text("Status: $status")
            Button(onClick = {
                val perm = scanPermission()
                if (ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED) {
                    requestScan()
                } else {
                    permissionLauncher.launch(perm)
                }
            }) { Text("Scan Wi-Fi") }
            HorizontalDivider()
        }
        items(results) { ap -> ApRow(ap) }
    }
}

@Suppress("DEPRECATION")
@Composable
private fun ApRow(ap: ScanResult) {
    val ssid = ap.SSID.ifBlank { "<hidden>" }
    val azText = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        yesNo(ap.is80211azNtbResponder())
    } else {
        "n/a (needs API 33+)"
    }
    Column {
        Text("$ssid (${ap.level} dBm)", style = MaterialTheme.typography.titleSmall)
        Text("BSSID ${ap.BSSID}")
        Text("802.11mc RTT responder: ${yesNo(ap.is80211mcResponder())}")
        Text("802.11az NTB responder: $azText")
    }
}

private fun scanPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.NEARBY_WIFI_DEVICES
    } else {
        Manifest.permission.ACCESS_FINE_LOCATION
    }

@Suppress("DEPRECATION")
@SuppressLint("MissingPermission")
private fun readScanResults(wifi: WifiManager): List<ScanResult> =
    try {
        wifi.scanResults.orEmpty()
    } catch (e: SecurityException) {
        emptyList()
    }

private fun yesNo(value: Boolean): String = if (value) "yes" else "no"
