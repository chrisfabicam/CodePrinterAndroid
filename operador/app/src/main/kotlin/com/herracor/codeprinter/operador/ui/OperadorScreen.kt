package com.herracor.codeprinter.operador.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OperadorScreen(vm: OperadorViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(title = {
                Column {
                    Text("CodePrinter", fontWeight = FontWeight.Bold)
                    Text(
                        "Impresión de etiquetas",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            })
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // -------- 1. Consultar --------
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("1. Consultar artículo", style = MaterialTheme.typography.titleMedium)

                    OutlinedTextField(
                        value = ui.codigo,
                        onValueChange = vm::onCodigoChange,
                        label = { Text("Código") },
                        placeholder = { Text("Escaneá o ingresá el código...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(
                            imeAction = ImeAction.Search,
                            keyboardType = KeyboardType.Text
                        ),
                        keyboardActions = KeyboardActions(onSearch = { vm.consultar() })
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = ui.tipo == "articulo",
                            onClick = { vm.onTipoChange("articulo") },
                            label = { Text("Código artículo") }
                        )
                        FilterChip(
                            selected = ui.tipo == "barras",
                            onClick = { vm.onTipoChange("barras") },
                            label = { Text("Código de barras") }
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = vm::consultar,
                            enabled = !ui.consultando,
                            modifier = Modifier.weight(1f)
                        ) { Text(if (ui.consultando) "Consultando…" else "🔍 Consultar") }

                        OutlinedButton(onClick = vm::limpiar) { Text("🗑️ Limpiar") }
                    }

                    if (ui.mensaje.isNotEmpty()) {
                        MessageBanner(ui.mensaje, ui.mensajeEsError)
                    }
                }
            }

            // -------- 2. Resultado --------
            val art = ui.articulo
            if (art != null) {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("2. Artículo", style = MaterialTheme.typography.titleMedium)
                        Text(art.nombreFamilia, fontWeight = FontWeight.Bold)
                        Text(art.nombreArticulo)
                        Divider(Modifier.padding(vertical = 6.dp))
                        LabeledRow("Código", art.codigoInterno)
                        LabeledRow("Familia", "${art.nombreFamilia} (${art.familia})")
                        if (art.precios.isNotEmpty()) {
                            LabeledRow("Unidad", art.precios.first().unidad)
                        }

                        // Precios
                        if (art.precios.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text("Precios", style = MaterialTheme.typography.labelLarge)
                            LazyColumn(modifier = Modifier.heightIn(180.dp)) {
                                items(art.precios) { p -> ListRow(p.lista, "${p.moneda} ${p.precio}") }
                            }
                        }

                        // Stock
                        if (art.stockLocales.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text("Stock por local", style = MaterialTheme.typography.labelLarge)
                            LazyColumn(modifier = Modifier.heightIn(180.dp)) {
                                items(art.stockLocales) { s ->
                                    ListRow(s.deposito.ifBlank { s.deposito }, s.saldo)
                                }
                            }
                        }
                    }
                }

                // -------- 3. Imprimir --------
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("3. Imprimir", style = MaterialTheme.typography.titleMedium)

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Cantidad:")
                            Spacer(Modifier.width(12.dp))
                            OutlinedTextField(
                                value = ui.cantidad.toString(),
                                onValueChange = {
                                    val n = it.filter { c -> c.isDigit() }.take(3).toIntOrNull() ?: 1
                                    vm.onCantidadChange(n)
                                },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.width(100.dp)
                            )
                        }

                        Button(
                            onClick = vm::imprimir,
                            enabled = !ui.imprimiendo,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (ui.imprimiendo) "Enviando…" else "🖨️ Imprimir") }

                        OutlinedButton(onClick = { showSettings = !showSettings }) {
                            Text(if (showSettings) "Ocultar ajustes" else "⚙️ Ajustes")
                        }

                        if (showSettings) {
                            // ---- ERP ----
                            Text("Servidor ERP", style = MaterialTheme.typography.titleSmall)

                            OutlinedTextField(
                                value = ui.erpEndpoint,
                                onValueChange = vm::onErpEndpointChange,
                                label = { Text("Endpoint SOAP del ERP") },
                                placeholder = { Text("http://<ip-o-host>/PSIGServices/PSIGServicios.svc") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                "URL completa del servicio SOAP. Se guarda solo en este dispositivo.",
                                style = MaterialTheme.typography.bodySmall
                            )

                            OutlinedTextField(
                                value = ui.erpHost,
                                onValueChange = vm::onErpHostChange,
                                label = { Text("Header Host del ERP") },
                                placeholder = { Text("hostname-interno-del-erp") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                "Valor del header HTTP 'Host' que exige el servidor IIS del ERP.",
                                style = MaterialTheme.typography.bodySmall
                            )

                            // ---- Impresora ----
                            Text("Celular-impresora", style = MaterialTheme.typography.titleSmall)

                            OutlinedTextField(
                                value = ui.printerUrl,
                                onValueChange = vm::onPrinterUrlChange,
                                label = { Text("URL del celular-impresora") },
                                placeholder = { Text("http://192.168.1.50:8080") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                "Es la IP del celular que tiene conectada la impresora TSPL2 por USB.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        if (ui.mensaje.isNotEmpty()) {
                            MessageBanner(ui.mensaje, ui.mensajeEsError)
                        }
                    }
                }
            }

            Text(
                "HERRACOR S.A. © ${java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun LabeledRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ListRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun MessageBanner(msg: String, isError: Boolean) {
    val color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Text(msg, color = color, style = MaterialTheme.typography.bodyMedium)
}
