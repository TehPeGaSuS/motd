package io.github.trevarj.motd.avatar

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class LocalAvatarStoreTest {
    private lateinit var context: Context
    private lateinit var store: LocalAvatarStore
    private lateinit var source: File

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, LocalAvatarStore.DIRECTORY).deleteRecursively()
        source = File(context.cacheDir, "avatar-source.png")
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        try {
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
        store = LocalAvatarStore(context)
    }

    @Test fun imports_valid_images_with_unique_owned_models_and_replaces_safely() {
        val first = store.import(Uri.fromFile(source)).getOrThrow()
        val second = store.import(Uri.fromFile(source)).getOrThrow()
        assertTrue(store.owns(first))
        assertTrue(store.owns(second))
        assertNotEquals(first, second)

        store.delete(first)
        assertFalse(File(checkNotNull(Uri.parse(first).path)).exists())
        assertTrue(File(checkNotNull(Uri.parse(second).path)).exists())
    }

    @Test fun rejects_invalid_and_oversized_files_without_leaving_partial_files() {
        val invalid = File(context.cacheDir, "not-image").apply { writeText("nope") }
        assertTrue(store.import(Uri.fromFile(invalid)).isFailure)
        val oversized =
            File(context.cacheDir, "oversized-image").apply {
                outputStream().use { it.write(ByteArray((LocalAvatarStore.MAX_BYTES + 1).toInt())) }
            }
        assertTrue(store.import(Uri.fromFile(oversized)).isFailure)
        assertTrue(File(context.filesDir, LocalAvatarStore.DIRECTORY).listFiles().orEmpty().isEmpty())
    }

    @Test fun rollback_and_orphan_pruning_never_delete_outside_owned_directory() {
        val live = store.import(Uri.fromFile(source)).getOrThrow()
        val orphan = store.import(Uri.fromFile(source)).getOrThrow()
        val outside = File(context.cacheDir, "outside-avatar").apply { writeText("keep") }

        store.delete(orphan) // failed database write rollback
        store.prune(listOf(live, Uri.fromFile(outside).toString()))

        assertTrue(File(checkNotNull(Uri.parse(live).path)).exists())
        assertFalse(File(checkNotNull(Uri.parse(orphan).path)).exists())
        assertTrue(outside.exists())
    }
}
