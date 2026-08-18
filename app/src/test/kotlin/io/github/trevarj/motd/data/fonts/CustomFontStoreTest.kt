package io.github.trevarj.motd.data.fonts

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.R
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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
}
