package io.github.trevarj.motd.invite

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Local-only QR renderer. Black/white pixels preserve scanner contrast in every app theme. */
fun inviteQrBitmap(
    text: String,
    size: Int = 768,
): Bitmap {
    require(size > 0) { "QR size must be positive" }
    val matrix =
        QRCodeWriter().encode(
            text,
            BarcodeFormat.QR_CODE,
            size,
            size,
            mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 4,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            ),
        )
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) pixels[y * size + x] = if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}
