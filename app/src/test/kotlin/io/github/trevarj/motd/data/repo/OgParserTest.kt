package io.github.trevarj.motd.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Plain JVM fixture tests for the OG-tag extractor (no Android deps: parseOgTags uses only Regex).
class OgParserTest {
    private val url = "https://example.com/page"

    @Test
    fun extractsAllOgTags_doubleQuotes() {
        val html =
            """
            <html><head>
            <meta property="og:title" content="The Title">
            <meta property="og:description" content="A description here">
            <meta property="og:image" content="https://example.com/img.png">
            <meta property="og:site_name" content="Example Site">
            </head></html>
            """.trimIndent()

        val p = LinkPreviewRepositoryImpl.parseOgTags(url, html)!!
        assertEquals("The Title", p.title)
        assertEquals("A description here", p.description)
        assertEquals("https://example.com/img.png", p.imageUrl)
        // Provenance is the fetched host; the self-declared og:site_name is not trusted.
        assertEquals("example.com", p.siteName)
        assertEquals(url, p.url)
        assertEquals(LinkPreviewKind.WEB, p.kind)
    }

    @Test
    fun contentBeforeProperty_attributeOrderIndependent() {
        val html = """<meta content='Reversed Title' property='og:title'>"""
        val p = LinkPreviewRepositoryImpl.parseOgTags(url, html)!!
        assertEquals("Reversed Title", p.title)
    }

    @Test
    fun fallsBackToTitleTag_whenNoOgTitle() {
        val html = "<html><head><title>Plain Title</title></head><body>hi</body></html>"
        val p = LinkPreviewRepositoryImpl.parseOgTags(url, html)!!
        assertEquals("Plain Title", p.title)
        assertNull(p.description)
        assertNull(p.imageUrl)
    }

    @Test
    fun decodesHtmlEntities() {
        val html = """<meta property="og:title" content="Tom &amp; Jerry &lt;3">"""
        val p = LinkPreviewRepositoryImpl.parseOgTags(url, html)!!
        assertEquals("Tom & Jerry <3", p.title)
    }

    @Test
    fun decodesNumericAndNamedEntities() {
        val html =
            """<meta property="og:title" content="Tor is back &#8211; PTirc &mdash; &#x2026;">"""
        val p = LinkPreviewRepositoryImpl.parseOgTags(url, html)!!
        assertEquals("Tor is back – PTirc — …", p.title)
    }

    @Test
    fun noExtractableTags_returnsNull() {
        val html = "<html><body>no metadata at all</body></html>"
        assertNull(LinkPreviewRepositoryImpl.parseOgTags(url, html))
    }

    @Test
    fun video_pages_use_a_playable_thumbnail_card() {
        val preview =
            LinkPreviewRepositoryImpl.parseOgTags(
                "https://www.youtube.com/watch?v=abc123",
                """
                <meta property="og:title" content="Demo video">
                <meta property="og:image" content="https://i.ytimg.com/vi/abc123/hqdefault.jpg">
                """.trimIndent(),
            )!!

        assertEquals("Demo video", preview.title)
        assertEquals("https://i.ytimg.com/vi/abc123/hqdefault.jpg", preview.imageUrl)
        assertEquals(LinkPreviewKind.VIDEO, preview.kind)
    }

    @Test
    fun video_metadata_and_host_detection_do_not_match_lookalikes() {
        assertTrue(LinkPreviewRepositoryImpl.isPopularVideoUrl("https://youtu.be/abc123"))
        assertTrue(LinkPreviewRepositoryImpl.isPopularVideoUrl("https://www.vimeo.com/12345"))
        assertFalse(LinkPreviewRepositoryImpl.isPopularVideoUrl("https://youtube.com.example.test/watch"))

        val preview =
            LinkPreviewRepositoryImpl.parseOgTags(
                url,
                """
                <meta property="og:type" content="video.other">
                <meta property="og:video" content="https://cdn.example.com/clip.mp4">
                """.trimIndent(),
            )!!
        assertEquals(LinkPreviewKind.VIDEO, preview.kind)
    }

    @Test
    fun wikipediaArticleUrls_useLanguageEditionSummaryEndpoint() {
        assertEquals(
            "https://en.wikipedia.org/api/rest_v1/page/summary/Alan_Turing",
            LinkPreviewRepositoryImpl.wikipediaSummaryUrl(
                "https://en.wikipedia.org/wiki/Alan_Turing#Early_life",
            ),
        )
        assertEquals(
            "https://en.wikipedia.org/api/rest_v1/page/summary/Alan%20Turing",
            LinkPreviewRepositoryImpl.wikipediaSummaryUrl(
                "https://en.wikipedia.org/wiki/Alan%20Turing",
            ),
        )
        assertEquals(
            "https://fr.wikipedia.org/api/rest_v1/page/summary/Alan_Turing",
            LinkPreviewRepositoryImpl.wikipediaSummaryUrl(
                "https://fr.m.wikipedia.org/wiki/Alan_Turing",
            ),
        )
        assertEquals(
            "https://de.wikipedia.org/api/rest_v1/page/summary/Alan_Turing",
            LinkPreviewRepositoryImpl.wikipediaSummaryUrl(
                "https://de.wikipedia.org/w/index.php?title=Alan_Turing&oldid=1",
            ),
        )
        assertEquals(
            "https://de.wikipedia.org/api/rest_v1/page/summary/Alan%20Turing",
            LinkPreviewRepositoryImpl.wikipediaSummaryUrl(
                "https://de.wikipedia.org/w/index.php?title=Alan+Turing",
            ),
        )
    }

    @Test
    fun wikipediaSummaryRouting_rejectsNonArticlesAndLookalikeHosts() {
        assertNull(LinkPreviewRepositoryImpl.wikipediaSummaryUrl("https://en.wikipedia.org/"))
        assertNull(LinkPreviewRepositoryImpl.wikipediaSummaryUrl("https://wikipedia.org.example.test/wiki/Test"))
        assertNull(LinkPreviewRepositoryImpl.wikipediaSummaryUrl("https://example.test/wiki/Test"))
    }

    @Test
    fun parsesWikipediaSummaryIntoRicherArticlePreview() {
        val summary =
            """
            {
              "title": "Alan Turing",
              "description": "English computer scientist",
              "extract": "Alan Turing was an English mathematician,\ncomputer scientist.",
              "thumbnail": {
                "source": "https://upload.wikimedia.org/turing.jpg",
                "width": 320,
                "height": 427
              },
              "ignored": true
            }
            """.trimIndent()

        val preview =
            LinkPreviewRepositoryImpl.parseWikipediaSummary(
                "https://en.wikipedia.org/wiki/Alan_Turing",
                summary,
            )!!

        assertEquals("Alan Turing", preview.title)
        assertEquals(
            "Alan Turing was an English mathematician, computer scientist.",
            preview.description,
        )
        assertEquals("https://upload.wikimedia.org/turing.jpg", preview.imageUrl)
        assertEquals("Wikipedia", preview.siteName)
        assertEquals(LinkPreviewKind.WIKIPEDIA, preview.kind)
    }

    @Test
    fun wikipediaSummary_ignoresUnsafeThumbnailAndMalformedJson() {
        val preview =
            LinkPreviewRepositoryImpl.parseWikipediaSummary(
                url,
                """{"title":"Safe","thumbnail":{"source":"file:///tmp/private"}}""",
            )!!
        assertEquals("Safe", preview.title)
        assertNull(preview.imageUrl)
        assertNull(LinkPreviewRepositoryImpl.parseWikipediaSummary(url, "not json"))
        assertNull(LinkPreviewRepositoryImpl.parseWikipediaSummary(url, "{}"))
    }

    @Test
    fun site_name_is_the_fetched_host_never_the_self_declared_brand() {
        val preview =
            LinkPreviewRepositoryImpl.parseOgTags(
                "https://evil.example/login",
                """<meta property="og:title" content="Sign in"><meta property="og:site_name" content="GitHub">""",
            )!!
        assertEquals("evil.example", preview.siteName)
    }

    @Test
    fun og_fields_are_sanitized_and_capped() {
        val spoofedTitle = "paypal.com\u202Emoc.live\u0000"
        val html =
            "<meta property=\"og:title\" content=\"$spoofedTitle\">" +
                // Kept inside the 64 KB head-scan window; oversized fields are covered separately.
                "<meta property=\"og:description\" content=\"" + "a".repeat(10_000) + "\">"
        val preview = LinkPreviewRepositoryImpl.parseOgTags(url, html)!!

        // RTL-override FORMAT and CONTROL code points are stripped, exactly as the TEXT path does.
        assertEquals("paypal.commoc.live", preview.title)
        // A count-based LRU cache means each entry must be small; the sanitizer caps retention.
        assertEquals(2_048, preview.description?.length)
    }

    @Test
    fun og_image_requires_an_http_scheme() {
        for (bad in listOf("file:///etc/passwd", "content://media/external/images/1", "javascript:alert(1)")) {
            val preview =
                LinkPreviewRepositoryImpl.parseOgTags(
                    url,
                    """<meta property="og:title" content="t"><meta property="og:image" content="$bad">""",
                )!!
            assertNull(preview.imageUrl)
        }
        val ok =
            LinkPreviewRepositoryImpl.parseOgTags(
                url,
                """<meta property="og:image" content="https://example.com/i.png">""",
            )!!
        assertEquals("https://example.com/i.png", ok.imageUrl)
    }

    @Test
    fun metadata_outside_the_head_region_is_ignored() {
        // OG tags after </head> are body content and must not be scanned.
        assertNull(
            LinkPreviewRepositoryImpl.parseOgTags(
                url,
                """<html><head></head><body><meta property="og:title" content="Injected"></body></html>""",
            ),
        )
        // Metadata beyond the 64 KB scan cap is likewise out of reach for the regexes.
        assertNull(
            LinkPreviewRepositoryImpl.parseOgTags(
                url,
                "<html><head>" + " ".repeat(70 * 1024) + """<meta property="og:title" content="Late">""",
            ),
        )
    }

    @Test
    fun title_extraction_is_linear_and_stops_at_markup() {
        // An unterminated <title> over a large body must fail fast instead of backtracking.
        val started = System.nanoTime()
        assertNull(LinkPreviewRepositoryImpl.parseOgTags(url, "<title>" + "a ".repeat(30_000)))
        assertTrue((System.nanoTime() - started) < 2_000_000_000L)

        // The strict pattern captures plain text only; a title containing markup is no preview
        // rather than a backtracking hazard.
        assertEquals("safe", LinkPreviewRepositoryImpl.parseOgTags(url, "<title>safe</title>")?.title)
        assertNull(LinkPreviewRepositoryImpl.parseOgTags(url, "<title>safe <b>rest</b></title>"))
    }

    @Test
    fun wikipediaSummary_fallsBackToShortDescriptionWhenExtractIsMissing() {
        val preview =
            LinkPreviewRepositoryImpl.parseWikipediaSummary(
                url,
                """{"title":"Alan Turing","description":"English computer scientist"}""",
            )!!
        assertEquals("English computer scientist", preview.description)
    }
}
