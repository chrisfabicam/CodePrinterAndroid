package com.herracor.codeprinter.operador.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.herracor.codeprinter.operador.model.Articulo
import java.io.ByteArrayOutputStream

/**
 * Genera el comando TSPL2 completo (con BITMAP embebido) para la etiqueta.
 *
 * Réplica exacta de frontend/js/bitmapbuilder.js + frontend/js/tspl.js:
 *   - Canvas 392 x 240 dots (49x30 mm a 203 dpi = 8 dots/mm)
 *   - Familia + Nombre arriba (fuente bold 20px)
 *   - QR (Code de barras) a la izquierda
 *   - UNIDAD y CÓDIGO a la derecha
 *   - Code128 abajo centrado
 *
 * Configuración hardcodeada de TSPL_CONFIG (tspl.js):
 *   width=49mm, height=32mm (calibrado), gap=2mm, density=8, speed=4
 */
object TsplRenderer {

    private const val LABEL_WIDTH = 392
    private const val LABEL_HEIGHT = 240

    // TSPL_CONFIG (tspl.js)
    private const val SIZE_WIDTH_MM = 49
    private const val SIZE_HEIGHT_MM = 32
    private const val GAP_MM = 2
    private const val DENSITY = 8
    private const val SPEED = 4
    private const val DIRECTION = 1

    /**
     * Genera el string TSPL2 completo listo para enviar a la impresora.
     * Incluye los 512 bytes NUL de inicio (como en printservice.py).
     *
     * @param articulo datos del artículo
     * @param cantidad copias a imprimir (1-999)
     */
    fun build(articulo: Articulo, cantidad: Int): String {
        val bitmap = renderLabel(articulo)

        val widthBytes = (LABEL_WIDTH + 7) / 8
        val header = buildString {
            // 512 bytes NUL de inicio (reset de buffer, igual que Python)
            repeat(512) { append('\u0000') }
            append("\r\nSIZE $SIZE_WIDTH_MM mm,$SIZE_HEIGHT_MM mm")
            append("\r\nGAP $GAP_MM mm,0 mm")
            append("\r\nDENSITY $DENSITY")
            append("\r\nSPEED $SPEED")
            append("\r\nDIRECTION $DIRECTION")
            append("\r\nREFERENCE 0,0")
            append("\r\nSET PEEL OFF")
            append("\r\nSET TEAR ON")
            append("\r\nCLS")
            append("\r\nBITMAP 0,0,$widthBytes,$LABEL_HEIGHT,1,")
        }

        // TSPL es binario: el BITMAP va como bytes crudos seguido de \r\nPRINT n,1
        val out = ByteArrayOutputStream()
        out.write(header.toByteArray(Charsets.US_ASCII))
        out.write(bitmapToTsplBytes(bitmap))
        out.write("\r\nPRINT $cantidad,1\r\n".toByteArray(Charsets.US_ASCII))

        // Devolvemos los bytes crudos en un String ISO-8859-1 (1:1 byte->char),
        // así el cliente HTTP los envía tal cual.
        return out.toByteArray().toString(Charsets.ISO_8859_1)
    }

    // ------------------------------------------------------
    // Render del label en un Bitmap 392x240
    // ------------------------------------------------------

    private fun renderLabel(a: Articulo): Bitmap {
        val bmp = Bitmap.createBitmap(LABEL_WIDTH, LABEL_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)

        val codigo = a.codigoInterno.trim()

        // 1) Familia y Nombre arriba
        drawText(canvas, a.nombreFamilia, 10f, 6f, 20f, LABEL_WIDTH - 20)
        drawText(canvas, a.nombreArticulo, 10f, 28f, 20f, LABEL_WIDTH - 20)

        // 2) QR (código interno) de 126x126 dots (cell=6)
        drawQr(canvas, codigo, 10, 54, 6)

        // 3) UNIDAD a la derecha
        drawText(canvas, "UNIDAD: ${a.precios.firstOrNull()?.unidad ?: ""}", 150f, 70f, 20f, LABEL_WIDTH - 160)

        // 4) CÓDIGO: + valor
        drawText(canvas, "CÓDIGO:", 150f, 105f, 20f, LABEL_WIDTH - 160)
        drawText(canvas, codigo, 150f, 145f, 28f, LABEL_WIDTH - 160, autoFit = true)

        // 5) Code128 abajo
        drawCode128(canvas, codigo, 200f, 30f, (LABEL_WIDTH - 20).toFloat())

        return bmp
    }

    private fun drawText(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        sizePx: Float,
        maxWidth: Int,
        autoFit: Boolean = false
    ) {
        if (text.isBlank()) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            this.textSize = sizePx
        }

        var txt = text.trim()
        if (autoFit) {
            while (paint.textSize > 10f && paint.measureText(txt) > maxWidth) {
                paint.textSize -= 2f
            }
        }
        while (txt.isNotEmpty() && paint.measureText(txt) > maxWidth) {
            txt = txt.dropLast(1)
        }
        canvas.drawText(txt, x, y + paint.textSize, paint)
    }

    private fun drawQr(canvas: Canvas, text: String, x: Int, y: Int, cell: Int) {
        if (text.isBlank()) return
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
            EncodeHintType.MARGIN to 0,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val matrix = try {
            QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        } catch (_: Exception) {
            return
        }
        val paint = Paint().apply { color = Color.BLACK }
        for (r in 0 until matrix.height) {
            for (c in 0 until matrix.width) {
                if (matrix.get(c, r)) {
                    canvas.drawRect(
                        (x + c * cell).toFloat(),
                        (y + r * cell).toFloat(),
                        (x + (c + 1) * cell).toFloat(),
                        (y + (r + 1) * cell).toFloat(),
                        paint
                    )
                }
            }
        }
    }

    // ------------------------------------------------------
    // Code 128 Set B (replica de encodeCode128 + drawCode128 de bitmapbuilder.js)
    // ------------------------------------------------------

    private val CODE128_PATTERNS = arrayOf(
        "212222","222122","222221","121223","121322","131222","122213","122312","132212","221213",
        "221312","231212","112232","122132","122231","113222","123122","123221","223211","221132",
        "221231","213212","223112","312131","311222","321122","321221","312212","322112","322211",
        "212123","212321","232121","111323","131123","131321","112313","132113","132311","211313",
        "231113","231311","112133","112331","132131","113123","113321","133121","313121","211331",
        "231131","213113","213311","213131","311123","311321","331121","312113","312311","332111",
        "314111","221411","431111","111224","111422","121124","121421","141122","141221","112214",
        "112412","122114","122411","142112","142211","241211","221114","413111","241112","134111",
        "111242","121142","121241","114212","124112","124211","411212","421112","421211","212141",
        "214121","412121","111143","111341","131141","114113","114311","411113","411311","113141",
        "114131","311141","411131","211412","211214","211232","2331112"
    )

    private fun encodeCode128(text: String): List<Int> {
        val startCode = 104
        var checksum = startCode
        val codes = mutableListOf(startCode)
        for (i in text.indices) {
            val v = text[i].code - 32
            if (v < 0 || v > 95) continue
            codes += v
            checksum += v * (i + 1)
        }
        codes += checksum % 103
        codes += 106
        return codes
    }

    private fun drawCode128(canvas: Canvas, text: String, y: Float, height: Float, targetWidth: Float) {
        if (text.isBlank()) return
        val codes = encodeCode128(text)

        var totalModules = 0
        for (code in codes) {
            for (ch in CODE128_PATTERNS[code]) totalModules += ch - '0'
        }
        if (totalModules == 0) return

        val dotsPerModule = maxOf(1, (targetWidth / totalModules).toInt())
        val actualWidth = totalModules * dotsPerModule
        val startX = (LABEL_WIDTH - actualWidth) / 2

        val paint = Paint().apply { color = Color.BLACK }
        var xPos = startX.toFloat()
        for (code in codes) {
            var isBar = true
            for (ch in CODE128_PATTERNS[code]) {
                val w = (ch - '0') * dotsPerModule
                if (isBar) {
                    canvas.drawRect(xPos, y, xPos + w, y + height, paint)
                }
                xPos += w
                isBar = !isBar
            }
        }
    }

    // ------------------------------------------------------
    // Bitmap ARGB -> bytes TSPL monocromáticos
    // ------------------------------------------------------

    /**
     * Convierte el bitmap a formato TSPL: 1 bit por pixel, MSB a la izquierda,
     * 1 = blanco, 0 = negro (igual que image_to_tspl_bitmap de printservice.py).
     */
    private fun bitmapToTsplBytes(bmp: Bitmap): ByteArray {
        val w = bmp.width
        val h = bmp.height
        val widthBytes = (w + 7) / 8
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)

        val out = ByteArray(widthBytes * h)
        var idx = 0
        for (row in 0 until h) {
            for (byteIdx in 0 until widthBytes) {
                var b = 0
                for (bit in 0 until 8) {
                    val x = byteIdx * 8 + bit
                    if (x < w) {
                        val p = pixels[row * w + x]
                        val lum = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
                        if (lum > 128) b = b or (1 shl (7 - bit))  // blanco -> bit 1
                    }
                }
                out[idx++] = b.toByte()
            }
        }
        return out
    }
}
