package com.herracor.codeprinter.printserver.server

import android.util.Log
import com.herracor.codeprinter.printserver.usb.UsbPrinter
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayOutputStream

/**
 * Servidor HTTP embebido (NanoHTTPD) que recibe TSPL2 crudo del operador
 * y lo escribe a la impresora USB.
 *
 * Endpoints:
 *   POST /print   -> body = bytes TSPL2 crudos (application/octet-stream)
 *   GET  /health  -> {"status":"ok","printer":true|false,"device":"..."}
 */
class PrintServer(
    port: Int,
    private val printer: UsbPrinter
) : NanoHTTPD(port) {

    companion object { private const val TAG = "PrintServer" }

    override fun serve(session: IHTTPSession): Response {
        return try {
            when (session.uri) {
                "/print" -> handlePrint(session)
                "/health" -> handleHealth()
                else -> json(Response.Status.NOT_FOUND, """{"success":false,"error":"not found"}""")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error atendiendo ${session.uri}", e)
            json(Response.Status.INTERNAL_ERROR, """{"success":false,"error":"${e.message}"}""")
        }
    }

    private fun handlePrint(session: IHTTPSession): Response {
        if (session.method != Method.POST) {
            return json(Response.Status.METHOD_NOT_ALLOWED, """{"success":false,"error":"use POST"}""")
        }
        if (!printer.connected) {
            return json(Response.Status.SERVICE_UNAVAILABLE,
                """{"success":false,"error":"impresora no conectada"}""")
        }

        // Leer body crudo
        val length = session.headers["content-length"]?.toLongOrNull() ?: 0L
        val data = if (length > 0) {
            ByteArray(length.toInt()).also { session.inputStream.readFully(it) }
        } else {
            val buf = ByteArrayOutputStream()
            val tmp = ByteArray(8192)
            while (true) {
                val n = session.inputStream.read(tmp)
                if (n <= 0) break
                buf.write(tmp, 0, n)
            }
            buf.toByteArray()
        }

        if (data.isEmpty()) {
            return json(Response.Status.BAD_REQUEST, """{"success":false,"error":"body vacío"}""")
        }

        Log.i(TAG, "Recibidos ${data.size} bytes TSPL2")
        val ok = printer.write(data)
        return if (ok) {
            json(Response.Status.OK, """{"success":true,"bytes":${data.size}}""")
        } else {
            json(Response.Status.INTERNAL_ERROR,
                """{"success":false,"error":"falló la escritura USB"}""")
        }
    }

    private fun handleHealth(): Response {
        val dev = printer.device?.productName ?: ""
        val body = """{"status":"ok","printer":${printer.connected},"device":"$dev"}"""
        return json(Response.Status.OK, body)
    }

    private fun json(status: Response.Status, body: String): Response =
        newFixedLengthResponse(status, "application/json", body)

    private fun java.io.InputStream.readFully(dest: ByteArray) {
        var off = 0
        while (off < dest.size) {
            val n = read(dest, off, dest.size - off)
            if (n <= 0) break
            off += n
        }
    }
}
