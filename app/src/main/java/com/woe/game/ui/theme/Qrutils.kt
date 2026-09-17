package com.woe.game

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Genera el código QR de una carta a partir de su texto (normalmente el `id`,
 * el mismo valor que llevaría grabado la etiqueta NFC). Útil para imprimir
 * el QR junto a la carta física en dispositivos sin NFC.
 */
object QrUtils {

    fun generarBitmap(texto: String, tamano: Int = 512): Bitmap? {
        return try {
            val matriz = QRCodeWriter().encode(texto, BarcodeFormat.QR_CODE, tamano, tamano)
            val bitmap = Bitmap.createBitmap(tamano, tamano, Bitmap.Config.RGB_565)
            for (x in 0 until tamano) {
                for (y in 0 until tamano) {
                    bitmap.setPixel(x, y, if (matriz.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
                }
            }
            bitmap
        } catch (e: Exception) {
            null
        }
    }
}