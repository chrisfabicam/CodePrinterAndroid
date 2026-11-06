package com.herracor.codeprinter.operador.data

import android.content.Context
import androidx.core.content.edit

/**
 * Persistencia de ajustes en SharedPreferences (equivalente a localStorage
 * en la PWA original).
 *
 * Todos los valores sensibles (endpoint del ERP, host header, URL del
 * celular-impresora) viven acá y NO en el código fuente, para poder
 * publicar el repo sin exponer datos internos.
 *
 * Los defaults son vacíos: si el usuario no configuró nada, la app muestra
 * un mensaje pidiendo que vaya a Ajustes en vez de fallar.
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** URL completa del endpoint SOAP del ERP (ej: http://1.2.3.4/PSIGServices/PSIGServicios.svc) */
    var erpEndpoint: String
        get() = prefs.getString(KEY_ERP_ENDPOINT, "") ?: ""
        set(value) = prefs.edit { putString(KEY_ERP_ENDPOINT, value.trim()) }

    /** Valor del header `Host:` que exige IIS (hostname interno del ERP) */
    var erpHost: String
        get() = prefs.getString(KEY_ERP_HOST, "") ?: ""
        set(value) = prefs.edit { putString(KEY_ERP_HOST, value.trim()) }

    /** URL del celular-impresora (ej: http://192.168.1.50:8080) */
    var printerUrl: String
        get() = prefs.getString(KEY_PRINTER_URL, "") ?: ""
        set(value) = prefs.edit { putString(KEY_PRINTER_URL, value.trim()) }

    /** true si el ERP está configurado (endpoint + host no vacíos) */
    val erpConfigurado: Boolean
        get() = erpEndpoint.isNotBlank() && erpHost.isNotBlank()

    companion object {
        private const val PREFS_NAME = "codeprinter_settings"
        private const val KEY_ERP_ENDPOINT = "erp_endpoint"
        private const val KEY_ERP_HOST = "erp_host"
        private const val KEY_PRINTER_URL = "printer_url"
    }
}
