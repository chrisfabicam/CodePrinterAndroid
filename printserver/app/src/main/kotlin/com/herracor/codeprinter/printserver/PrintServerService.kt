package com.herracor.codeprinter.printserver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.herracor.codeprinter.printserver.server.PrintServer
import com.herracor.codeprinter.printserver.usb.UsbPrinter
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Servicio en primer plano que:
 *  - Mantiene el USB abierto y detectando la impresora.
 *  - Levanta NanoHTTPD en el puerto 8080.
 *  - Muestra una notificación permanente con la IP del celular.
 */
class PrintServerService : Service() {

    companion object {
        private const val TAG = "PrintServerService"
        private const val CHANNEL_ID = "printserver_channel"
        private const val NOTIF_ID = 1001
        const val PORT = 8080

        const val EXTRA_STATUS = "status"
        const val EXTRA_IP = "ip"
        const val EXTRA_DEVICE = "device"
        const val BROADCAST_STATUS = "com.herracor.codeprinter.printserver.STATUS"

        @Volatile var running = false
            private set
    }

    private lateinit var printer: UsbPrinter
    private var server: PrintServer? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()

        printer = UsbPrinter(this)
        printer.onStateChanged = { connected, name ->
            broadcastStatus(connected, name)
            updateNotification()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        printer.start()

        try {
            server = PrintServer(PORT, printer).also { it.start() }
            Log.i(TAG, "HTTP server iniciado en :$PORT")
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo iniciar el servidor HTTP", e)
        }

        // Mantener WiFi activo mientras imprime
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "CodePrinter").also {
            it.setReferenceCounted(false)
            it.acquire()
        }

        running = true
        broadcastStatus(printer.connected, printer.device?.productName ?: "")
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        try { server?.stop() } catch (_: Exception) {}
        server = null
        printer.stop()
        try { wifiLock?.release() } catch (_: Exception) {}
        wifiLock = null
        super.onDestroy()
    }

    // ------------------------------------------------------
    // Notificación / foreground
    // ------------------------------------------------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(
                CHANNEL_ID, "Servidor de impresión",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Mantiene activo el servidor TSPL2" }
            nm.createNotificationChannel(ch)
        }
    }

    private fun startForegroundCompat() {
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val ip = getLocalIp() ?: "sin IP"
        val pantalla = if (printer.connected) "Impresora conectada" else "Sin impresora"

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID)
        else @Suppress("DEPRECATION") Notification.Builder(this)

        return builder
            .setContentTitle("CodePrinter PrintServer")
            .setContentText("$ip:$PORT · $pantalla")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    private fun broadcastStatus(connected: Boolean, deviceName: String?) {
        val ip = getLocalIp() ?: ""
        val i = Intent(BROADCAST_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATUS, connected)
            putExtra(EXTRA_IP, ip)
            putExtra(EXTRA_DEVICE, deviceName ?: "")
        }
        sendBroadcast(i)
    }

    // ------------------------------------------------------
    // Utilidades de red
    // ------------------------------------------------------

    fun getLocalIp(): String? = getLocalIps().firstOrNull()

    fun getLocalIps(): List<String> {
        val out = mutableListOf<String>()
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress ?: continue
                        // Filtrar direcciones link-local
                        if (!ip.startsWith("169.254.")) out += ip
                    }
                }
            }
        } catch (_: Exception) {}
        return out
    }
}
