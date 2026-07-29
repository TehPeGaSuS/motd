package io.github.trevarj.motd.ui.chat

import android.content.Context
import android.util.Xml
import androidx.compose.ui.unit.IntSize
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.prefs.ChatWallpaperPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
class WallpaperAssetTest {
    private val assets = ApplicationProvider.getApplicationContext<Context>().assets

    @Test fun everyPreset_isAConstrainedMonochromeSvg() {
        ChatWallpaperPreset.entries.filterNot { it == ChatWallpaperPreset.NONE }.forEach { preset ->
            assets.open("chat-wallpapers/${assetName(preset)}").use { input ->
                val parser = Xml.newPullParser().apply { setInput(input, "UTF-8") }
                var paths = 0
                while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == XmlPullParser.START_TAG) {
                        assertTrue("unsupported ${parser.name}", parser.name == "svg" || parser.name == "path")
                        if (parser.name == "svg") assertEquals("0 0 512 512", parser.getAttributeValue(null, "viewBox"))
                        if (parser.name == "path") {
                            paths++
                            assertEquals("#000000", parser.getAttributeValue(null, "stroke"))
                            assertEquals("none", parser.getAttributeValue(null, "fill"))
                            assertFalse(parser.getAttributeValue(null, "d").isNullOrBlank())
                            assertFalse(parser.getAttributeValue(null, "transform").isNullOrBlank())
                        }
                    }
                    parser.next()
                }
                assertTrue("$preset must contain paths", paths >= 16)
            }
        }
    }

    @Test fun wallpaperTextureStaysSubtleAcrossThemeModes() {
        assertEquals(0.10f, wallpaperPatternMaxAlpha(dark = false, trueBlack = false))
        assertEquals(0.12f, wallpaperPatternMaxAlpha(dark = true, trueBlack = false))
        assertEquals(0.06f, wallpaperPatternMaxAlpha(dark = true, trueBlack = true))
    }

    @Test fun rasterCoverageOnlyExpandsAcrossViewportChanges() {
        val initial = expandedRasterCoverage(IntSize.Zero, IntSize(1080, 1800))
        val keyboardOpen = expandedRasterCoverage(initial, IntSize(1080, 1100))
        val wider = expandedRasterCoverage(keyboardOpen, IntSize(1200, 1600))

        assertEquals(IntSize(1080, 1800), keyboardOpen)
        assertEquals(IntSize(1200, 1800), wider)
    }

    @Test fun retainedRasterIsCroppedAtNativeScale() {
        assertEquals(
            IntSize(540, 600),
            wallpaperRasterSourceSize(
                rasterSize = IntSize(540, 900),
                canvasSize = IntSize(1080, 1200),
            ),
        )
    }
}
