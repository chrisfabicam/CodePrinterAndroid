package com.herracor.codeprinter.operador.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.herracor.codeprinter.operador.data.PrintClient
import com.herracor.codeprinter.operador.data.SettingsStore
import com.herracor.codeprinter.operador.data.SoapClient
import com.herracor.codeprinter.operador.data.TsplRenderer
import com.herracor.codeprinter.operador.model.Articulo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class OperadorUiState(
    val codigo: String = "",
    val tipo: String = "articulo",          // "articulo" | "barras"
    val cantidad: Int = 1,
    val consultando: Boolean = false,
    val imprimiendo: Boolean = false,
    val articulo: Articulo? = null,
    val mensaje: String = "",
    val mensajeEsError: Boolean = false,
    // Ajustes (persistidos en SharedPreferences)
    val erpEndpoint: String = "",
    val erpHost: String = "",
    val printerUrl: String = ""
)

class OperadorViewModel(
    private val settings: SettingsStore
) : ViewModel() {

    private val soap = SoapClient(settings)
    private val printClient = PrintClient()

    private val _ui = MutableStateFlow(
        OperadorUiState(
            erpEndpoint = settings.erpEndpoint,
            erpHost = settings.erpHost,
            printerUrl = settings.printerUrl
        )
    )
    val ui: StateFlow<OperadorUiState> = _ui.asStateFlow()

    fun onCodigoChange(v: String) { _ui.value = _ui.value.copy(codigo = v) }
    fun onTipoChange(v: String) { _ui.value = _ui.value.copy(tipo = v) }
    fun onCantidadChange(v: Int) {
        _ui.value = _ui.value.copy(cantidad = v.coerceIn(1, 999))
    }
    fun onErpEndpointChange(v: String) {
        _ui.value = _ui.value.copy(erpEndpoint = v)
        settings.erpEndpoint = v
    }

    fun onErpHostChange(v: String) {
        _ui.value = _ui.value.copy(erpHost = v)
        settings.erpHost = v
    }

    fun onPrinterUrlChange(v: String) {
        _ui.value = _ui.value.copy(printerUrl = v)
        settings.printerUrl = v
    }

    fun consultar() {
        val s = _ui.value
        val codigo = s.codigo.trim()
        if (codigo.isEmpty()) {
            setMensaje("⚠️ Ingresá un código para consultar.", true)
            return
        }
        _ui.value = s.copy(consultando = true, mensaje = "⏳ Consultando...", mensajeEsError = false, articulo = null)

        viewModelScope.launch {
            try {
                val art = soap.buscarArticulo(codigo, s.tipo)
                if (art != null) {
                    _ui.value = _ui.value.copy(
                        consultando = false,
                        articulo = art,
                        mensaje = "✅ Artículo encontrado.",
                        mensajeEsError = false
                    )
                } else {
                    _ui.value = _ui.value.copy(
                        consultando = false,
                        articulo = null,
                        mensaje = "❌ El código no existe, por favor verifique.",
                        mensajeEsError = true
                    )
                }
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(
                    consultando = false,
                    articulo = null,
                    mensaje = "❌ ${e.message ?: "Error al consultar el ERP"}",
                    mensajeEsError = true
                )
            }
        }
    }

    fun imprimir() {
        val s = _ui.value
        val art = s.articulo ?: run {
            setMensaje("⚠️ Primero consultá un artículo.", true)
            return
        }
        if (s.printerUrl.isBlank()) {
            setMensaje("⚠️ Configurá la URL del celular-impresora en Ajustes.", true)
            return
        }
        if (!settings.erpConfigurado) {
            setMensaje("⚠️ Configurá el endpoint y el host del ERP en Ajustes.", true)
            return
        }
        _ui.value = s.copy(imprimiendo = true, mensaje = "⏳ Enviando a impresora...", mensajeEsError = false)

        viewModelScope.launch {
            try {
                // Generar TSPL2 (bitmap en memoria + comando completo)
                val tspl = TsplRenderer.build(art, s.cantidad)
                val res = printClient.enviar(s.printerUrl, tspl)
                res.onSuccess {
                    _ui.value = _ui.value.copy(
                        imprimiendo = false,
                        mensaje = "✅ ${it} — ${s.cantidad} etiqueta${if (s.cantidad > 1) "s" else ""}.",
                        mensajeEsError = false
                    )
                }.onFailure { err ->
                    _ui.value = _ui.value.copy(
                        imprimiendo = false,
                        mensaje = "❌ ${err.message}",
                        mensajeEsError = true
                    )
                }
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(
                    imprimiendo = false,
                    mensaje = "❌ ${e.message ?: "Error al imprimir"}",
                    mensajeEsError = true
                )
            }
        }
    }

    fun limpiar() {
        _ui.value = _ui.value.copy(
            codigo = "", articulo = null, cantidad = 1, mensaje = "", mensajeEsError = false
        )
    }

    private fun setMensaje(msg: String, error: Boolean) {
        _ui.value = _ui.value.copy(mensaje = msg, mensajeEsError = error)
    }
}
