package io.github.trevarj.motd.data.fonts

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.R
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class CustomFontStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = CustomFontStore(context)

    // Reused as a real, parseable font fixture; openRawResource reads any file-backed resource's
    // raw bytes regardless of its res/ subfolder, so this works for a res/font entry too.
    private val validFontBytes by lazy {
        context.resources.openRawResource(R.font.jetbrains_mono_wght).use { it.readBytes() }
    }

    @Test fun installedFile_isNullUntilSomethingIsImported() {
        assertNull(store.installedFile())
    }

    @Test fun validFont_roundTripsThroughImportAndInstalledFile() = runTest {
        val source = File(context.cacheDir, "source-font.ttf").apply { writeBytes(validFontBytes) }

        val result = store.import(Uri.fromFile(source))

        assertTrue(result.isSuccess)
        assertEquals("source-font.ttf", result.getOrThrow())
        assertEquals(store.fontFile, store.installedFile())
        assertArrayEquals(validFontBytes, store.fontFile.readBytes())
    }

    @Test fun garbageBytes_areRejectedAndNeverOverwriteAnInstalledFont() = runTest {
        // Install a real font first so the reject path below can prove it left this alone.
        val validSource = File(context.cacheDir, "valid.ttf").apply { writeBytes(validFontBytes) }
        store.import(Uri.fromFile(validSource)).getOrThrow()
        val installedBytes = store.fontFile.readBytes()

        val garbageSource = File(context.cacheDir, "garbage.ttf").apply {
            writeBytes(ByteArray(256) { it.toByte() })
        }
        val result = store.import(Uri.fromFile(garbageSource))

        assertTrue(result.isFailure)
        assertArrayEquals(installedBytes, store.fontFile.readBytes())
    }

    @Test fun garbageBytes_neverCreateAFontFileWhenNoneWasInstalledYet() = runTest {
        val garbageSource = File(context.cacheDir, "garbage.ttf").apply {
            writeBytes(ByteArray(256) { it.toByte() })
        }

        val result = store.import(Uri.fromFile(garbageSource))

        assertTrue(result.isFailure)
        assertFalse(store.fontFile.exists())
        assertNull(store.installedFile())
    }

    @Test fun displayName_fallsBackToTheUrisLastPathSegment() = runTest {
        // file:// URIs (used here and by no real content provider) never answer an OpenableColumns
        // query, so the resolved name always takes the fallback path.
        val source = File(context.cacheDir, "MyCoolFont.ttf").apply { writeBytes(validFontBytes) }

        val result = store.import(Uri.fromFile(source))

        assertEquals("MyCoolFont.ttf", result.getOrThrow())
    }

    @Test fun blankDisplayName_fallsBackToTheUrisLastPathSegment() = runTest {
        // A provider that answers the OpenableColumns query with "" (blank, not null) must not be
        // treated as a real name — that would persist an empty customFontName while CUSTOM stays
        // selected, and the picker reads that as "nothing imported".
        Robolectric.setupContentProvider(BlankDisplayNameProvider::class.java, "blank.font.provider")
        val uri = Uri.parse("content://blank.font.provider/MyBlankNameFont.ttf")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(validFontBytes))

        val result = store.import(uri)

        assertEquals("MyBlankNameFont.ttf", result.getOrThrow())
    }

    @Test fun streamThatThrowsMidCopy_yieldsFailureAndLeavesNoTempFileAndDoesNotClobberInstalled() = runTest {
        // Install a real font first so the throw path below can prove it left this alone.
        val validSource = File(context.cacheDir, "valid-before-throw.ttf").apply { writeBytes(validFontBytes) }
        store.import(Uri.fromFile(validSource)).getOrThrow()
        val installedBytes = store.fontFile.readBytes()

        val throwingUri = Uri.parse("content://throwing.test.provider/font")
        shadowOf(context.contentResolver).registerInputStream(
            throwingUri,
            ThrowingAfterBytesInputStream(ByteArrayInputStream(validFontBytes), throwAfter = 16),
        )

        val result = store.import(throwingUri)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is RuntimeException)
        // openInputStream can also throw SecurityException on a revoked grant, and provider streams
        // can throw arbitrary unchecked exceptions — both must resolve through Result rather than
        // escaping import(), and neither may leave the temp file behind or touch the installed font.
        assertFalse(File(store.fontFile.parentFile, "custom_font.tmp").exists())
        assertArrayEquals(installedBytes, store.fontFile.readBytes())
    }

    @Test fun successfulImport_bumpsRevisionEvenOnASameNameReimport() = runTest {
        val initialRevision = store.revision.value
        val source = File(context.cacheDir, "revisioned.ttf").apply { writeBytes(validFontBytes) }

        store.import(Uri.fromFile(source)).getOrThrow()
        val revisionAfterFirstImport = store.revision.value
        assertNotEquals(initialRevision, revisionAfterFirstImport)

        // Same source, same resolved display name, but the on-disk font was replaced again — the
        // revision must still change so callers keyed on it re-key their cached FontFamily.
        store.import(Uri.fromFile(source)).getOrThrow()
        assertNotEquals(revisionAfterFirstImport, store.revision.value)
    }
}

/** Answers every OpenableColumns query with a blank (not null) DISPLAY_NAME. */
class BlankDisplayNameProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf<Any?>("")) }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}

/** Delegates reads to [delegate] until [throwAfter] bytes have been read, then throws unconditionally. */
private class ThrowingAfterBytesInputStream(
    private val delegate: InputStream,
    private val throwAfter: Int,
) : InputStream() {
    private var totalRead = 0

    override fun read(): Int {
        if (totalRead >= throwAfter) throw RuntimeException("boom mid-copy")
        val byte = delegate.read()
        if (byte >= 0) totalRead++
        return byte
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (totalRead >= throwAfter) throw RuntimeException("boom mid-copy")
        val n = delegate.read(b, off, len)
        if (n > 0) totalRead += n
        return n
    }
}
