package io.github.trevarj.motd

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import io.github.trevarj.motd.data.repo.LinkPreview
import io.github.trevarj.motd.data.repo.LinkPreviewKind
import io.github.trevarj.motd.ui.components.LinkPreviewCard
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Rule
import org.junit.Test

class LinkPreviewCardUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun text_preview_exposes_a_dedicated_body() {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                LinkPreviewCard(
                    preview = LinkPreview("https://example.test/note.txt", "note.txt", "line one\nline two", null, "example.test", LinkPreviewKind.TEXT),
                    loading = false,
                    onClick = {},
                )
            }
        }
        compose.onNodeWithTag("link_preview_text_body").assertIsDisplayed()
    }
}
