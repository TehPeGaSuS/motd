package io.github.trevarj.motd.ui.share

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ParseSharedContentTest {
    private val uri: Uri = Uri.parse("content://media/external/images/media/42")

    private fun sendIntent(
        type: String? = null,
        text: String? = null,
        stream: Uri? = null,
    ): Intent = Intent(Intent.ACTION_SEND).apply {
        this.type = type
        text?.let { putExtra(Intent.EXTRA_TEXT, it) }
        stream?.let { putExtra(Intent.EXTRA_STREAM, it) }
    }

    @Test fun plainTextShareBecomesText() {
        val share = parseSharedContent(sendIntent(type = "text/plain", text = "hello"))
        assertEquals(PendingShare.Text("hello"), share)
    }

    @Test fun streamShareBecomesFileWithDeclaredMime() {
        val share = parseSharedContent(sendIntent(type = "image/png", stream = uri))
        assertEquals(PendingShare.File(uri, "image/png"), share)
    }

    @Test fun textPlainWithBothExtrasPrefersText() {
        val share = parseSharedContent(sendIntent(type = "text/plain", text = "hello", stream = uri))
        assertEquals(PendingShare.Text("hello"), share)
    }

    @Test fun textPlainWithOnlyStreamBecomesFile() {
        val share = parseSharedContent(sendIntent(type = "text/plain", stream = uri))
        assertEquals(PendingShare.File(uri, "text/plain"), share)
    }

    @Test fun sendMultipleIsIgnored() {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(uri))
        }
        assertNull(parseSharedContent(intent))
    }

    @Test fun launcherIntentIsIgnored() {
        assertNull(parseSharedContent(Intent(Intent.ACTION_MAIN)))
    }

    @Test fun nullIntentIsIgnored() {
        assertNull(parseSharedContent(null))
    }

    @Test fun sendWithoutExtrasIsIgnored() {
        assertNull(parseSharedContent(sendIntent(type = "text/plain")))
    }

    @Test fun blankTextIsIgnored() {
        assertNull(parseSharedContent(sendIntent(type = "text/plain", text = "   ")))
    }
}
