package com.herracor.codeprinter.operador.data

import com.herracor.codeprinter.operador.model.Articulo
import com.herracor.codeprinter.operador.model.Precio
import com.herracor.codeprinter.operador.model.StockLocal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Cliente SOAP del ERP.
 *
 * Reemplaza al api.php en PHP. Habla directo con el endpoint del ERP
 * forzando el header `Host:` que exige IIS (matchea por hostname; sin ese
 * header se cuelga).
 *
 * El endpoint y el host se leen de [SettingsStore] en CADA request, así el
 * usuario puede cambiarlos desde Ajustes sin reiniciar la app.
 *
 * Todos los métodos replican lo que hacía el api.php:
 *   - GetArticulos01 / GetArticulos02 con sus namespaces correctos
 *   - GetFamilias01, GetListaDePrecios, GetCodigosPorCuenta02
 *   - DepositosSaldos_EstadoArticuloFecha
 */
class SoapClient(
    private val settings: SettingsStore
) {

    private val nsPsig = "http://www.atg.com.uy/psig5.4/psig"
    private val nsEntNull = "http://www.atg.com.uy/psig5.4/entidadesNull"
    private val nsEnt = "http://www.atg.com.uy/psig5.4/entidades"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // Forzar el Host: header (OkHttp normalmente lo ignora).
        // Se lee en cada request para reflejar cambios de Ajustes al instante.
        .addInterceptor { chain ->
            val host = settings.erpHost
            val original = chain.request()
            val newReq = if (host.isNotBlank()) {
                original.newBuilder().header("Host", host).build()
            } else {
                original
            }
            chain.proceed(newReq)
        }
        .build()

    /** Endpoint actual (leído en tiempo de ejecución). */
    private val endpoint: String
        get() = settings.erpEndpoint

    // ------------------------------------------------------
    // API pública
    // ------------------------------------------------------

    /**
     * Busca un artículo por código interno o por código de barras.
     * @return el Articulo encontrado, o null si no existe.
     * @throws Exception con mensaje de usuario si hay error de red/parseo.
     */
    suspend fun buscarArticulo(codigo: String, tipo: String): Articulo? =
        withContext(Dispatchers.IO) {
            if (!settings.erpConfigurado) {
                throw IllegalStateException(
                    "Configurá el endpoint y el host del ERP en Ajustes antes de consultar."
                )
            }

            val codigoInterno: String
            val nombre: String
            val familiaId: String

            // 1) Datos del artículo
            if (tipo == "barras") {
                val (c, n, f) = buscarPorBarras(codigo) ?: return@withContext null
                codigoInterno = c; nombre = n; familiaId = f
            } else {
                val (c, n, f) = buscarPorCodigo(codigo) ?: return@withContext null
                codigoInterno = c; nombre = n; familiaId = f
            }

            // 2) Nombre de familia
            val nombreFamilia = getFamiliaNombre(familiaId)

            // 3) Precios
            val precios = getPrecios(codigoInterno)

            // 4) Stock por locales
            val stock = getStock(codigoInterno)

            Articulo(
                codigoInterno = codigoInterno,
                nombreArticulo = nombre,
                familia = familiaId,
                nombreFamilia = nombreFamilia,
                precios = precios,
                stockLocales = stock
            )
        }

    // ------------------------------------------------------
    // Consultas SOAP individuales
    // ------------------------------------------------------

    /** GetArticulos01 (por código de artículo). Devuelve trio (codigo, nombre, familia). */
    private fun buscarPorCodigo(codigo: String): Triple<String, String, String>? {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:psig="$nsPsig" xmlns:ent="$nsEnt">
   <soapenv:Header/>
   <soapenv:Body>
      <psig:GetArticulos01>
         <psig:Articulo>
            <ent:Activo>S</ent:Activo>
            <ent:Articulo>${xmlEscape(codigo)}</ent:Articulo>
         </psig:Articulo>
         <psig:Proyeccion>
            <ent:Activo>1</ent:Activo>
            <ent:Articulo>1</ent:Articulo>
            <ent:Familia>1</ent:Familia>
            <ent:Nombre>1</ent:Nombre>
         </psig:Proyeccion>
         <psig:Origen>Herracor</psig:Origen>
      </psig:GetArticulos01>
   </soapenv:Body>
</soapenv:Envelope>"""
        val response = soapCall(xml, "$nsPsig/IPSIG/GetArticulos01")
        return parseDatoArticulo01(response)
    }

    /** GetArticulos02 (por código de barras). Devuelve trio (codigo, nombre, familia). */
    private fun buscarPorBarras(codigoBarras: String): Triple<String, String, String>? {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:psig="$nsPsig" xmlns:entNull="$nsEntNull" xmlns:ent="$nsEnt">
   <soapenv:Header/>
   <soapenv:Body>
      <psig:GetArticulos02>
         <psig:Articulo>
            <entNull:CodigoDeBarras>
               <entNull:CodigosDeBarraNull>
                  <entNull:CodigoDeBarra>${xmlEscape(codigoBarras)}</entNull:CodigoDeBarra>
               </entNull:CodigosDeBarraNull>
            </entNull:CodigoDeBarras>
         </psig:Articulo>
         <psig:Proyeccion>
            <ent:Articulo>1</ent:Articulo>
            <ent:Nombre>1</ent:Nombre>
            <ent:Familia>1</ent:Familia>
            <ent:CodigoDeBarras>1</ent:CodigoDeBarras>
            <ent:ListaUnidades>1</ent:ListaUnidades>
         </psig:Proyeccion>
         <psig:Origen>Herracor</psig:Origen>
      </psig:GetArticulos02>
   </soapenv:Body>
</soapenv:Envelope>"""
        val response = soapCall(xml, "$nsPsig/IPSIG/GetArticulos02")
        return parseDatoArticuloNull02(response)
    }

    /** GetFamilias01 — nombre de la familia. */
    private fun getFamiliaNombre(codFamilia: String): String {
        if (codFamilia.isBlank()) return ""
        val xml = """<?xml version="1.0" encoding="utf-8"?>
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:psig="$nsPsig" xmlns:ent="$nsEnt">
   <soapenv:Header/>
   <soapenv:Body>
      <psig:GetFamilias01>
         <psig:Familia><ent:Familia>${xmlEscape(codFamilia)}</ent:Familia></psig:Familia>
         <psig:Proyeccion><ent:Familia>1</ent:Familia><ent:Nombre>1</ent:Nombre></psig:Proyeccion>
         <psig:Origen>Herracor</psig:Origen>
      </psig:GetFamilias01>
   </soapenv:Body>
</soapenv:Envelope>"""
        val response = soapCall(xml, "$nsPsig/IPSIG/GetFamilias01")
        return parseDatoFamilia(response)
    }

    /** GetArticulosPrecio01 — listas de precios del artículo. */
    private fun getPrecios(articulo: String): List<Precio> {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:psig="$nsPsig" xmlns:ent="$nsEnt">
   <soapenv:Header/>
   <soapenv:Body>
      <psig:GetArticulosPrecio01>
         <psig:p_datoArticulo><ent:Articulo>${xmlEscape(articulo)}</ent:Articulo></psig:p_datoArticulo>
         <psig:p_datoArticuloProyeccion>
            <ent:Articulo>1</ent:Articulo>
            <ent:Lista>1</ent:Lista>
            <ent:Moneda>1</ent:Moneda>
            <ent:Unidad>1</ent:Unidad>
         </psig:p_datoArticuloProyeccion>
         <psig:Origen>Herracor</psig:Origen>
      </psig:GetArticulosPrecio01>
   </soapenv:Body>
</soapenv:Envelope>"""
        return try {
            val response = soapCall(xml, "$nsPsig/IPSIG/GetArticulosPrecio01")
            parsePrecios(response)
        } catch (_: Exception) { emptyList() }
    }

    /** DepositosSaldos_EstadoArticuloFecha — stock por local. */
    private fun getStock(articulo: String): List<StockLocal> {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:psig="$nsPsig" xmlns:ent="$nsEnt">
   <soapenv:Header/>
   <soapenv:Body>
      <psig:DepositosSaldos_EstadoArticuloFecha>
         <psig:Estado>EXIST</psig:Estado>
         <psig:Articulo>${xmlEscape(articulo)}</psig:Articulo>
         <psig:Origen>Herracor</psig:Origen>
      </psig:DepositosSaldos_EstadoArticuloFecha>
   </soapenv:Body>
</soapenv:Envelope>"""
        return try {
            val response = soapCall(xml, "$nsPsig/IPSIG/DepositosSaldos_EstadoArticuloFecha")
            parseStock(response)
        } catch (_: Exception) { emptyList() }
    }

    /** GetCodigosPorCuenta02 — nombre de un depósito/local. */
    private fun getLocalNombre(codigoLocal: String): String {
        if (codigoLocal.isBlank()) return ""
        val xml = """<?xml version="1.0" encoding="utf-8"?>
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:psig="$nsPsig" xmlns:entNull="$nsEntNull" xmlns:ent="$nsEnt">
   <soapenv:Header/>
   <soapenv:Body>
      <psig:GetCodigosPorCuenta02>
         <psig:CuentaAuxiliar>L</psig:CuentaAuxiliar>
         <psig:Codigo><entNull:Codigo>${xmlEscape(codigoLocal)}</entNull:Codigo></psig:Codigo>
         <psig:Proyeccion><ent:Nombre>1</ent:Nombre></psig:Proyeccion>
         <psig:Origen>Herracor</psig:Origen>
      </psig:GetCodigosPorCuenta02>
   </soapenv:Body>
</soapenv:Envelope>"""
        return try {
            val response = soapCall(xml, "$nsPsig/IPSIG/GetCodigosPorCuenta02")
            parseDatoCodigoNulo(response)
        } catch (_: Exception) { "" }
    }

    /** GetListaDePrecios — nombre de la lista. */
    private fun getListaPrecioNombre(codLista: String): String {
        if (codLista.isBlank()) return ""
        val xml = """<?xml version="1.0" encoding="utf-8"?>
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:psig="$nsPsig" xmlns:ent="$nsEnt">
   <soapenv:Header/>
   <soapenv:Body>
      <psig:GetListaDePrecios>
         <psig:ListaDePrecios><ent:Lista>${xmlEscape(codLista)}</ent:Lista></psig:ListaDePrecios>
         <psig:Proyeccion><ent:Nombre>1</ent:Nombre><ent:Lista>1</ent:Lista></psig:Proyeccion>
         <psig:Origen>Herracor</psig:Origen>
      </psig:GetListaDePrecios>
   </soapenv:Body>
</soapenv:Envelope>"""
        return try {
            val response = soapCall(xml, "$nsPsig/IPSIG/GetListaDePrecios")
            parseDatoListaPrecio(response)
        } catch (_: Exception) { "" }
    }

    // ------------------------------------------------------
    // HTTP + parseo XML
    // ------------------------------------------------------

    private fun soapCall(xml: String, soapAction: String): Element {
        val body = xml.toRequestBody("text/xml; charset=utf-8".toMediaType())
        val req = Request.Builder()
            .url(endpoint)
            .header("SOAPAction", "\"$soapAction\"")
            .header("Connection", "close")
            .post(body)
            .build()

        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw Exception("HTTP ${resp.code} al consultar el ERP")
            }
            val raw = resp.body?.string().orEmpty().trim().trim('"', '\'').trim()
            if (raw.isBlank()) throw Exception("El ERP devolvió una respuesta vacía")
            return parseSoapBody(raw)
        }
    }

    private fun parseSoapBody(xml: String): Element {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }
        val doc = factory.newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        val bodyList = doc.documentElement.getElementsByTagNameNS(
            "http://schemas.xmlsoap.org/soap/envelope/", "Body"
        )
        if (bodyList.length == 0) throw Exception("Respuesta SOAP sin Body")
        val body = bodyList.item(0) as Element

        // Primer hijo del Body = <*Response>
        var child = body.firstChild
        while (child != null) {
            if (child is Element) return child
            child = child.nextSibling
        }
        throw Exception("Respuesta SOAP con Body vacío")
    }

    // ------------------------------------------------------
    // Parsers de respuesta (equivalen a los children() de PHP)
    // ------------------------------------------------------

    private fun parseDatoArticulo01(resp: Element): Triple<String, String, String>? {
        val result = findChild(resp, "GetArticulos01Result") ?: return null
        val dato = findChildNS(result, nsEnt, "DatoArticulo") ?: return null
        val codigo = textOf(findChildNS(dato, nsEnt, "Articulo")).trim()
        if (codigo.isEmpty()) return null
        return Triple(
            codigo,
            textOf(findChildNS(dato, nsEnt, "Nombre")),
            textOf(findChildNS(dato, nsEnt, "Familia"))
        )
    }

    private fun parseDatoArticuloNull02(resp: Element): Triple<String, String, String>? {
        val result = findChild(resp, "GetArticulos02Result") ?: return null
        // Puede haber varios DatoArticuloNull, tomar el primero con Articulo no vacío
        val nodes = result.getElementsByTagNameNS(nsEntNull, "DatoArticuloNull")
        for (i in 0 until nodes.length) {
            val it = nodes.item(i) as? Element ?: continue
            val codigo = textOf(findChildNS(it, nsEntNull, "Articulo")).trim()
            if (codigo.isNotEmpty()) {
                return Triple(
                    codigo,
                    textOf(findChildNS(it, nsEntNull, "Nombre")),
                    textOf(findChildNS(it, nsEntNull, "Familia"))
                )
            }
        }
        return null
    }

    private fun parseDatoFamilia(resp: Element): String {
        val result = findChild(resp, "GetFamilias01Result") ?: return ""
        val dato = findChildNS(result, nsEnt, "DatoFamilia") ?: return ""
        return textOf(findChildNS(dato, nsEnt, "Nombre"))
    }

    private fun parsePrecios(resp: Element): List<Precio> {
        val result = findChild(resp, "GetArticulosPrecio01Result") ?: return emptyList()
        val nodes = result.getElementsByTagNameNS(nsEnt, "DatoArticuloPrecio")
        val out = mutableListOf<Precio>()
        for (i in 0 until nodes.length) {
            val p = nodes.item(i) as? Element ?: continue
            val codLista = textOf(findChildNS(p, nsEnt, "Lista"))
            out += Precio(
                lista = getListaPrecioNombre(codLista),
                moneda = textOf(findChildNS(p, nsEnt, "Moneda")),
                unidad = textOf(findChildNS(p, nsEnt, "Unidad")),
                precio = textOf(findChildNS(p, nsEnt, "Precio")),
                fecha = formatFecha(textOf(findChildNS(p, nsEnt, "Fecha")))
            )
        }
        return out
    }

    private fun parseStock(resp: Element): List<StockLocal> {
        val result = findChild(resp, "DepositosSaldos_EstadoArticuloFechaResult") ?: return emptyList()
        val nodes = result.getElementsByTagNameNS(nsEnt, "DepositoSaldo")
        val out = mutableListOf<StockLocal>()
        for (i in 0 until nodes.length) {
            val d = nodes.item(i) as? Element ?: continue
            val codDep = textOf(findChildNS(d, nsEnt, "Deposito"))
            out += StockLocal(
                deposito = getLocalNombre(codDep),
                saldo = textOf(findChildNS(d, nsEnt, "Saldo"))
            )
        }
        return out
    }

    private fun parseDatoCodigoNulo(resp: Element): String {
        val result = findChild(resp, "GetCodigosPorCuenta02Result") ?: return ""
        val dato = findChildNS(result, nsEntNull, "DatoCodigoNull") ?: return ""
        return textOf(findChildNS(dato, nsEntNull, "Nombre"))
    }

    private fun parseDatoListaPrecio(resp: Element): String {
        val result = findChild(resp, "GetListaDePreciosResult") ?: return ""
        val dato = findChildNS(result, nsEnt, "DatoListaDePrecios") ?: return ""
        return textOf(findChildNS(dato, nsEnt, "Nombre"))
    }

    // ------------------------------------------------------
    // Helpers XML
    // ------------------------------------------------------

    /** Busca un hijo por nombre local, ignorando namespace. */
    private fun findChild(parent: Element, localName: String): Element? {
        val list: NodeList = parent.childNodes
        for (i in 0 until list.length) {
            val n = list.item(i)
            if (n is Element && n.localName == localName) return n
        }
        return null
    }

    /** Busca un hijo por namespace + nombre local. */
    private fun findChildNS(parent: Element, ns: String, localName: String): Element? {
        val list: NodeList = parent.childNodes
        for (i in 0 until list.length) {
            val n = list.item(i)
            if (n is Element && n.localName == localName && n.namespaceURI == ns) return n
        }
        return null
    }

    private fun textOf(el: Element?): String = el?.textContent.orEmpty()

    private fun formatFecha(raw: String): String {
        // Viene como "2024-05-01T00:00:00" -> "01/05/2024"
        val iso = raw.substringBefore('T')
        val parts = iso.split('-')
        return if (parts.size == 3) "${parts[2]}/${parts[1]}/${parts[0]}" else raw
    }

    private fun xmlEscape(s: String): String = buildString {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(c)
        }
    }
}
