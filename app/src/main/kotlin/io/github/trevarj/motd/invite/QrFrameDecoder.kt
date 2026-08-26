package io.github.trevarj.motd.invite

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/** Decode one CameraX luminance plane. Row padding and sensor rotation are normalized first. */
fun decodeQrFrame(
    bytes: ByteArray,
    width: Int,
    height: Int,
    rowStride: Int,
    rotationDegrees: Int,
    pixelStride: Int = 1,
    cropLeft: Int = 0,
    cropTop: Int = 0,
    cropWidth: Int = width,
    cropHeight: Int = height,
): String? {
    if (width <= 0 || height <= 0 || rowStride <= 0 || pixelStride <= 0 || cropWidth <= 0 || cropHeight <= 0 ||
        cropLeft < 0 || cropTop < 0 || cropLeft + cropWidth > width || cropTop + cropHeight > height
    ) {
        return null
    }
    val lastSourceIndex = (cropTop + cropHeight - 1) * rowStride + (cropLeft + cropWidth - 1) * pixelStride
    if (lastSourceIndex !in bytes.indices) return null
    val compact = ByteArray(cropWidth * cropHeight)
    for (y in 0 until cropHeight) {
        for (x in 0 until cropWidth) {
            compact[y * cropWidth + x] = bytes[(cropTop + y) * rowStride + (cropLeft + x) * pixelStride]
        }
    }
    val rotated = rotateLuma(compact, cropWidth, cropHeight, rotationDegrees)
    val source = PlanarYUVLuminanceSource(rotated.bytes, rotated.width, rotated.height, 0, 0, rotated.width, rotated.height, false)
    return try {
        QRCodeReader()
            .decode(
                BinaryBitmap(HybridBinarizer(source)),
                mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to listOf(com.google.zxing.BarcodeFormat.QR_CODE),
                    DecodeHintType.TRY_HARDER to true,
                    DecodeHintType.ALSO_INVERTED to true,
                ),
            ).text
    } catch (_: ReaderException) {
        // Blurry partial frames commonly fail checksum/format before autofocus settles; keep scanning.
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}

private data class RotatedLuma(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
)

private fun rotateLuma(
    source: ByteArray,
    width: Int,
    height: Int,
    rotationDegrees: Int,
): RotatedLuma =
    when ((rotationDegrees % 360 + 360) % 360) {
        0 -> {
            RotatedLuma(source, width, height)
        }

        90 -> {
            val out = ByteArray(source.size)
            for (y in 0 until height) for (x in 0 until width) out[x * height + (height - y - 1)] = source[y * width + x]
            RotatedLuma(out, height, width)
        }

        180 -> {
            RotatedLuma(ByteArray(source.size) { source[source.lastIndex - it] }, width, height)
        }

        270 -> {
            val out = ByteArray(source.size)
            for (y in 0 until height) for (x in 0 until width) out[(width - x - 1) * height + y] = source[y * width + x]
            RotatedLuma(out, height, width)
        }

        else -> {
            RotatedLuma(source, width, height)
        }
    }
