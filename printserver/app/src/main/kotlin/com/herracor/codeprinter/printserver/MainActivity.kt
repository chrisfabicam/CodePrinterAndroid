package com.herracor.codeprinter.printserver

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Pedir POST_NOTIFICATIONS en Android 13+
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            MaterialTheme {
                Surface { PrintServerScreen() }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrintServerScreen() {
    val ctx = LocalContext.current

    var running by remember { mutableStateOf(PrintServerService.running) }
    var ip by remember { mutableStateOf(mostrarIps(ctx)) }
    var connected by remember { mutableStateOf(false) }
    var deviceName by remember { mutableStateOf("") }

    val receiver = remember {
        object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                connected = i?.getBooleanExtra(PrintServerService.EXTRA_STATUS, false) ?: false
                deviceName = i?.getStringExtra(PrintServerService.EXTRA_DEVICE) ?: ""
                val newIp = i?.getStringExtra(PrintServerService.EXTRA_IP).orEmpty()
                if (newIp.isNotBlank()) ip = newIp
                running = PrintServerService.running
            }
        }
    }

    DisposableEffect(Unit) {
        val filter = IntentFilter(PrintServerService.BROADCAST_STATUS)
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            ctx.registerReceiver(receiver, filter)
        }
        onDispose {
            try { ctx.unregisterReceiver(receiver) } catch (_: Exception) {}
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("CodePrinter PrintServer") }) }
    ) { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Estado", style = MaterialTheme.typography.titleMedium)
                    Row {
                        Text("Servidor: ", fontWeight = FontWeight.Bold)
                        Text(if (running) "🟢 Activo" else "🔴 Detenido")
                    }
                    Row {
                        Text("Puerto: ", fontWeight = FontWeight.Bold)
                        Text(PrintServerService.PORT.toString())
                    }
                    Row {
                        Text("IP de este celular: ", fontWeight = FontWeight.Bold)
                        Text(ip, fontFamily = FontFamily.Monospace)
                    }
                    Row {
                        Text("Impresora: ", fontWeight = FontWeight.Bold)
                        Text(if (connected) "🟢 ${deviceName.ifBlank { "conectada" }}" else "🔴 sin conexión")
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Configurá esta URL en la app Operador:",
                        style = MaterialTheme.typography.bodySmall)
                    Text("http://$ip:${PrintServerService.PORT}",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (!running) {
                            val i = Intent(ctx, PrintServerService::class.java)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                                ctx.startForegroundService(i)
                            else ctx.startService(i)
                            running = true
                        }
                    },
                    enabled = !running,
                    modifier = Modifier.weight(1f)
                ) { Text("▶️ Iniciar") }

                OutlinedButton(
                    onClick = {
                        ctx.stopService(Intent(ctx, PrintServerService::class.java))
                        running = false
                    },
                    enabled = running,
                    modifier = Modifier.weight(1f)
                ) { Text("⏹️ Detener") }
            }

            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Instrucciones", style = MaterialTheme.typography.titleMedium)
                    Text("1. Conectá la impresora TSPL2 por USB a este celular.", style = MaterialTheme.typography.bodySmall)
                    Text("2. Tocá \"Iniciar\" y aceptá el permiso USB.", style = MaterialTheme.typography.bodySmall)
                    Text("3. Anotá la IP que aparece arriba.", style = MaterialTheme.typography.bodySmall)
                    Text("4. En la app Operador, en Ajustes, poné esa URL.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    Text("Mientras el servicio corre, este celular debe quedarse desbloqueado / cargando.",
                        style = MaterialTheme.typography.labelSmall)
                }
            }

            Spacer(Modifier.width(1.dp))
        }
    }
}

private fun mostrarIps(ctx: Context): String {
    return try {
        val ips = mutableListOf<String>()
        for (nif in java.net.NetworkInterface.getNetworkInterfaces()) {
            if (!nif.isUp || nif.isLoopback) continue
            for (addr in nif.inetAddresses) {
                if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                    val s = addr.hostAddress ?: continue
                    if (!s.startsWith("169.254.")) ips += s
                }
            }
        }
        ips.firstOrNull() ?: "sin conexión"
    } catch (_: Exception) { "sin conexión" }
}
