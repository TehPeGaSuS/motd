package io.github.trevarj.motd.ui.chat

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.platform.app.InstrumentationRegistry
import io.github.trevarj.motd.attachment.AttachmentSource
import io.github.trevarj.motd.attachment.PasteBackendConfig
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Rule
import org.junit.Test

class AttachmentConfirmationSheetUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun photoKeepsUploadActionVisible() {
        val file =
            InstrumentationRegistry
                .getInstrumentation()
                .targetContext.cacheDir
                .resolve("attachment-confirmation.png")
        Bitmap.createBitmap(800, 450, Bitmap.Config.ARGB_8888).also { bitmap ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }

        compose.setContent {
            MotdTheme {
                ConfirmationSheet(
                    source = AttachmentSource.Photo(Uri.fromFile(file), file.name, "image/png", file.length()),
                    config = PasteBackendConfig(),
                    sojuFileHostAvailable = false,
                    onChangeDestination = {},
                    onDismiss = {},
                    onUpload = {},
                )
            }
        }

        compose.onNodeWithTag("attachment_thumbnail").assertIsDisplayed()
        compose.onNodeWithTag("attachment_upload").assertIsDisplayed()
        file.delete()
    }
}
