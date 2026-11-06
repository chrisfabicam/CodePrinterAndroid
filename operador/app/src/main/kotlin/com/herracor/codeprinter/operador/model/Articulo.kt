package com.herracor.codeprinter.operador.model

/**
 * Artículo devuelto por el ERP.
 *
 * Equivalente al JSON `{success, data:{Articulo, NombreArticulo, Familia,
 * NombreFamilia, Precios, StockLocales}}` que producía api.php.
 */
data class Articulo(
    val codigoInterno: String,
    val nombreArticulo: String,
    val familia: String,
    val nombreFamilia: String,
    val precios: List<Precio>,
    val stockLocales: List<StockLocal>
)

data class Precio(
    val lista: String,
    val moneda: String,
    val unidad: String,
    val precio: String,
    val fecha: String
)

data class StockLocal(
    val deposito: String,
    val saldo: String
)

/** Estado de la UI para mostrar mensajes */
sealed class UiState {
    data object Idle : UiState()
    data object Loading : UiState()
    data class Success(val articulo: Articulo) : UiState()
    data class Error(val mensaje: String) : UiState()
}
