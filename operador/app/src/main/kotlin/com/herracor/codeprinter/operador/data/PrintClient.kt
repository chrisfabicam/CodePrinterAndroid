package com.herracor.codeprinter.operador.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Envía el TSPL2 crudo (texto + bytes binarios del BITMAP) por HTTP
 * al celular-impresora (app printserver corriendo en la LAN).
 *
 * POST http://<ip-impresora>:<puerto>/print
 * Content-Type: application/octet-stream
 * Body: bytes TSPL2 crudos
 */
class PrintClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * @param baseUrl  ej: "http://192.168.1.50:8080"
     * @param tspl     string que contiene los bytes TSPL2 crudos (ISO-8859-1)
     * @param cantidad copias (informativo; el PRINT n,1 ya va dentro del TSPL)
     */
    suspend fun enviar(baseUrl: String, tspl: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val url = baseUrl.trimEnd('/') + "/print"
                val bytes = tspl.toByteArray(Charsets.ISO_8859_1)
                val body = bytes.toRequestBody("application/octet-stream".toMediaType())

                val req = Request.Builder()
                    .url(url)
                    .post(body)
                    .build()

                http.newCall(req).execute().use { resp ->
                    val txt = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        Result.success("Impresión enviada (${bytes.size} bytes)")
                    } else {
                        Result.failure(Exception("El impresor respondió HTTP ${resp.code}: $txt"))
                    }
                }
            } catch (e: Exception) {
                Result.failure(Exception("No se pudo conectar al celular-impresora ($baseUrl): ${e.message}"))
            }
        }

    /** Health-check del celular-impresora. */
    suspend fun ping(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(baseUrl.trimEnd('/') + "/health").get().build()
            http.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }
}
