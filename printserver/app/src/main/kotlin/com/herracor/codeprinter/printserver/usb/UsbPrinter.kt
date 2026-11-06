package com.herracor.codeprinter.printserver.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log

/**
 * Driver USB para impresoras TSPL2.
 *
 * Se encarga de:
 *  - Detectar la impresora conectada por USB.
 *  - Pedir permiso al usuario (Android 11+ lo requiere en runtime).
 *  - Abrir una conexión al endpoint BULK OUT y escribir los bytes TSPL2.
 *
 * No depende de IDs de vendor: busca cualquier interfaz con clase PRINTER
 * (0x07), y si no la encuentra, usa la primera interfaz con endpoint OUT.
 */
class UsbPrinter(private val context: Context) {

    companion object {
        private const val TAG = "UsbPrinter"
        private const val ACTION_USB_PERMISSION = "com.herracor.codeprinter.printserver.USB_PERMISSION"
    }

    private val usbManager: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    @Volatile
    var device: UsbDevice? = null
        private set

    @Volatile
    var connected: Boolean = false
        private set

    private var iface: UsbInterface? = null
    private var endpointOut: UsbEndpoint? = null
    private var connection: UsbDeviceConnection? = null

    /** Listener simple para avisar a la UI de cambios de conexión. */
    var onStateChanged: ((connected: Boolean, nombre: String) -> Unit)? = null

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            val dev = if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

            if (granted && dev != null) {
                open(dev)
            } else {
                Log.w(TAG, "Permiso USB denegado para $dev")
                connected = false
                onStateChanged?.invoke(false, "Permiso USB denegado")
            }
        }
    }

    private val attachReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val dev = if (Build.VERSION.SDK_INT >= 33)
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    dev?.let { requestPermission(it) }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    close()
                    connected = false
                    onStateChanged?.invoke(false, "Impresora desconectada")
                }
            }
        }
    }

    /** Registra los receivers y busca una impresora ya conectada. */
    fun start() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            context.registerReceiver(attachReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(permissionReceiver, filter)
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(attachReceiver, filter)
        }

        // Buscar impresora ya conectada
        val found = findPrinter()
        if (found != null) requestPermission(found)
        else onStateChanged?.invoke(false, "Sin impresora conectada")
    }

    fun stop() {
        try { context.unregisterReceiver(permissionReceiver) } catch (_: Exception) {}
        try { context.unregisterReceiver(attachReceiver) } catch (_: Exception) {}
        close()
    }

    // ------------------------------------------------------
    // Detección
    // ------------------------------------------------------

    private fun findPrinter(): UsbDevice? {
        val devices = usbManager.deviceList.values
        // 1) Preferir interfaz USB clase PRINTER (0x07)
        for (d in devices) {
            for (i in 0 until d.interfaceCount) {
                if (d.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_PRINTER) return d
            }
        }
        // 2) Si no, cualquier dispositivo con endpoint BULK OUT
        for (d in devices) {
            for (i in 0 until d.interfaceCount) {
                val itf = d.getInterface(i)
                for (e in 0 until itf.endpointCount) {
                    val ep = itf.getEndpoint(e)
                    if (ep.direction == UsbConstants.USB_DIR_OUT &&
                        ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK
                    ) return d
                }
            }
        }
        return null
    }

    /** Pide permiso al usuario (si ya lo tiene, abre directamente). */
    fun requestPermission(device: UsbDevice) {
        this.device = device
        if (usbManager.hasPermission(device)) {
            open(device)
            return
        }
        val flags = if (Build.VERSION.SDK_INT >= 23)
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        else PendingIntent.FLAG_UPDATE_CURRENT
        val pi = PendingIntent.getBroadcast(
            context, 0, Intent(ACTION_USB_PERMISSION).setPackage(context.packageName), flags
        )
        usbManager.requestPermission(device, pi)
    }

    // ------------------------------------------------------
    // Apertura / cierre
    // ------------------------------------------------------

    private fun open(device: UsbDevice) {
        val itf = pickInterface(device)
        if (itf == null) {
            connected = false
            onStateChanged?.invoke(false, "No se encontró endpoint BULK OUT")
            return
        }
        val out = findBulkOut(itf)
        if (out == null) {
            connected = false
            onStateChanged?.invoke(false, "Interfaz sin endpoint de salida")
            return
        }

        val conn = usbManager.openDevice(device)
        if (conn == null) {
            connected = false
            onStateChanged?.invoke(false, "No se pudo abrir el dispositivo USB")
            return
        }
        if (!conn.claimInterface(itf, true)) {
            conn.close()
            connected = false
            onStateChanged?.invoke(false, "No se pudo reclamar la interfaz USB")
            return
        }

        this.iface = itf
        this.endpointOut = out
        this.connection = conn
        this.connected = true
        onStateChanged?.invoke(true, device.productName ?: device.deviceName)
    }

    private fun close() {
        try {
            connection?.takeIf { iface != null }?.releaseInterface(iface)
        } catch (_: Exception) {}
        try { connection?.close() } catch (_: Exception) {}
        connection = null
        iface = null
        endpointOut = null
    }

    private fun pickInterface(d: UsbDevice): UsbInterface? {
        for (i in 0 until d.interfaceCount) {
            val itf = d.getInterface(i)
            if (itf.interfaceClass == UsbConstants.USB_CLASS_PRINTER) return itf
        }
        for (i in 0 until d.interfaceCount) {
            val itf = d.getInterface(i)
            if (findBulkOut(itf) != null) return itf
        }
        return null
    }

    private fun findBulkOut(itf: UsbInterface): UsbEndpoint? {
        for (e in 0 until itf.endpointCount) {
            val ep = itf.getEndpoint(e)
            if (ep.direction == UsbConstants.USB_DIR_OUT &&
                ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK
            ) return ep
        }
        return null
    }

    // ------------------------------------------------------
    // Escritura
    // ------------------------------------------------------

    /**
     * Escribe los bytes TSPL2 en la impresora.
     * Divide en chunks de 16KB (límite usual de bulk transfer).
     * @return true si escribió todo.
     */
    fun write(data: ByteArray): Boolean {
        val conn = connection ?: return false
        val ep = endpointOut ?: return false

        val chunk = 16 * 1024
        var offset = 0
        while (offset < data.size) {
            val len = minOf(chunk, data.size - offset)
            val buf = data.copyOfRange(offset, offset + len)
            val sent = conn.bulkTransfer(ep, buf, len, 10_000)
            if (sent < 0) {
                Log.e(TAG, "bulkTransfer falló en offset=$offset (ret=$sent)")
                return false
            }
            offset += sent
        }
        return true
    }
}
