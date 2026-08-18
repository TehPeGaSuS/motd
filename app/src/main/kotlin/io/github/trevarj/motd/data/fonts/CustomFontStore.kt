package io.github.trevarj.motd.data.fonts

import android.content.Context
import android.database.Cursor
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_FONT_BYTES = 30L * 1024 * 1024

/**
 * Holds the single user-imported custom font on disk. There is exactly one slot (fixed path); a
 * new import replaces whatever was there. The DataStore-backed display name in [AppearancePrefs]
 * is the source of truth for "is a font imported"; this store only owns the binary.
 */
@Singleton
class CustomFontStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val fontsDir = File(context.filesDir, "fonts")

    /** Fixed on-disk location for the imported font; extension is irrelevant to Typeface. */
    val fontFile: File = File(fontsDir, "custom_font.ttf")

    /** The installed font file, or null if nothing has been imported (or it was removed). */
    fun installedFile(): File? = fontFile.takeIf { it.exists() }

    /**
     * Copy [uri] into the font slot on [Dispatchers.IO], validating it decodes as a font before
     * committing. Returns the resolved display name on success.
     */
    suspend fun import(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        fontsDir.mkdirs()
        val tempFile = File(fontsDir, "custom_font.tmp")
        try {
            val copiedBytes = context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyToLimited(output, MAX_FONT_BYTES) }
            } ?: return@withContext Result.failure(IOException("Could not open the selected file."))
            if (copiedBytes > MAX_FONT_BYTES) {
                tempFile.delete()
                return@withContext Result.failure(IOException("Font file is too large."))
            }
            if (!tempFile.hasFontMagicNumber()) {
                tempFile.delete()
                return@withContext Result.failure(IOException("Not a valid font file."))
            }
            // Typeface.Builder.build() is documented to return null for bytes it can't parse as a
            // font, but unparseable native font data can also surface as a thrown exception
            // depending on platform version — treat both as "invalid font" rather than propagating.
            // Layered on top of the magic-number sniff above: some test/graphics stacks accept a
            // File without actually decoding it, so the sniff is the gate that's guaranteed real.
            val decoded = runCatching { Typeface.Builder(tempFile).build() }.getOrNull()
            if (decoded == null) {
                tempFile.delete()
                return@withContext Result.failure(IOException("Not a valid font file."))
            }
            val displayName = resolveDisplayName(uri)
            if (!tempFile.renameTo(fontFile)) {
                tempFile.delete()
                return@withContext Result.failure(IOException("Could not install the font file."))
            }
            Result.success(displayName)
        } catch (io: IOException) {
            tempFile.delete()
            Result.failure(io)
        }
    }

    private fun resolveDisplayName(uri: Uri): String {
        // Only content:// URIs answer OpenableColumns queries; other schemes (e.g. a file:// URI in
        // tests) can throw rather than return null, so this fallback path must not propagate that.
        val cursor: Cursor? = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )
        }.getOrNull()
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) it.getString(nameIndex)?.let { name -> return name }
            }
        }
        return uri.lastPathSegment ?: "Custom font"
    }
}

/** Copies at most [limit] + 1 bytes so the caller can detect an over-limit source and reject it. */
private fun java.io.InputStream.copyToLimited(output: java.io.OutputStream, limit: Long): Long {
    val buffer = ByteArray(DEFAULT_COPY_BUFFER_SIZE)
    var copied = 0L
    while (copied <= limit) {
        val read = read(buffer)
        if (read < 0) break
        output.write(buffer, 0, read)
        copied += read
    }
    return copied
}

private const val DEFAULT_COPY_BUFFER_SIZE = 8 * 1024

/** SFNT container signatures: TrueType, OpenType/CFF, older Mac TrueType/Type1, and collections. */
private val FONT_MAGIC_NUMBERS = listOf(
    byteArrayOf(0x00, 0x01, 0x00, 0x00),
    "OTTO".toByteArray(Charsets.US_ASCII),
    "true".toByteArray(Charsets.US_ASCII),
    "typ1".toByteArray(Charsets.US_ASCII),
    "ttcf".toByteArray(Charsets.US_ASCII),
)

/**
 * Cheap, format-level sanity check ahead of the [Typeface] decode: garbage bytes never start with
 * one of the handful of SFNT signatures every real TTF/OTF file begins with.
 */
private fun File.hasFontMagicNumber(): Boolean {
    val header = ByteArray(4)
    val read = inputStream().use { it.read(header) }
    if (read < 4) return false
    return FONT_MAGIC_NUMBERS.any { it.contentEquals(header) }
}
