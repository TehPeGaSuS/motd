package io.github.trevarj.motd.attachment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentModelsTest {
    @Test fun endpointPresetsAreHttps() {
        assertEquals("https://crafterbin.glennstack.dev", EndpointPreset.CRAFTERBIN.endpoint)
        assertEquals("https://0x0.st", EndpointPreset.ZERO_X_ZERO.endpoint)
    }

    @Test fun curatedBackendsHaveExpectedProtocolsAndHttpsEndpoints() {
        val public = AttachmentBackend.entries.filter { it.endpoint != null }
        assertTrue(public.all { validateEndpoint(it.endpoint!!) != null })
        assertEquals(PasteProtocol.RAW_CNET, AttachmentBackend.CNET.protocol)
        assertEquals(PasteProtocol.MULTIPART_UGUU, AttachmentBackend.UGUU.protocol)
        assertEquals(PasteProtocol.MULTIPART_CATBOX, AttachmentBackend.LITTERBOX.protocol)
        assertEquals(PasteProtocol.SOJU_FILEHOST, AttachmentBackend.SOJU_FILEHOST.protocol)
    }

    @Test fun endpointValidationRejectsCredentialsAndHttp() {
        assertNull(validateEndpoint("http://example.com"))
        assertNull(validateEndpoint("https://user:pass@example.com"))
        assertEquals("https://paste.example/upload", validateEndpoint("https://paste.example/upload/"))
    }

    @Test fun sojuFileHostEndpointRequiresHttpsWithoutCredentials() {
        assertEquals(
            "https://irc.example/uploads/",
            sojuFileHostEndpoint(mapOf("soju.im/FILEHOST" to "https://irc.example/uploads/")),
        )
        assertNull(sojuFileHostEndpoint(emptyMap()))
        assertNull(sojuFileHostEndpoint(mapOf("soju.im/FILEHOST" to "http://irc.example/uploads")))
        assertNull(sojuFileHostEndpoint(mapOf("soju.im/FILEHOST" to "https://user:pass@irc.example/uploads")))
    }

    @Test fun sojuFileHostLocationResolvesRelativeHttpsUrls() {
        assertEquals(
            "https://irc.example/files/voice.ogg",
            resolveSojuLocation("https://irc.example/uploads/", "../files/voice.ogg"),
        )
        assertEquals(
            "https://cdn.example/voice.ogg",
            resolveSojuLocation("https://irc.example/uploads/", "https://cdn.example/voice.ogg"),
        )
    }

    @Test fun publicLimitIsCappedAt25MiB() {
        val config = normalizedConfig(PasteBackendConfig(sizeLimitBytes = MAX_CUSTOM_LIMIT_BYTES))
        assertEquals(DEFAULT_PUBLIC_LIMIT_BYTES, config.sizeLimitBytes)
    }

    @Test fun sojuFileHostUsesDefaultUploadCeiling() {
        val config = normalizedConfig(PasteBackendConfig(
            backend = AttachmentBackend.SOJU_FILEHOST,
            sizeLimitBytes = MAX_CUSTOM_LIMIT_BYTES,
        ))
        assertEquals(DEFAULT_PUBLIC_LIMIT_BYTES, config.sizeLimitBytes)
        assertTrue(AttachmentBackend.SOJU_FILEHOST.acceptsBinary)
    }

    @Test fun customLimitAllows512MiB() {
        val config = normalizedConfig(PasteBackendConfig(
            backend = AttachmentBackend.CUSTOM_0X0,
            endpoint = "https://paste.example",
            customEndpoint = "https://paste.example",
            sizeLimitBytes = MAX_CUSTOM_LIMIT_BYTES,
        ))
        assertEquals(MAX_CUSTOM_LIMIT_BYTES, config.sizeLimitBytes)
    }

    @Test fun x0atBackendUsesMultipart0x0AndOneGiBCeiling() {
        assertEquals(PasteProtocol.MULTIPART_0X0, AttachmentBackend.X0_AT.protocol)
        assertEquals("https://x0.at", AttachmentBackend.X0_AT.endpoint)
        assertTrue(AttachmentBackend.X0_AT.acceptsBinary)
        assertEquals(MAX_X0AT_LIMIT_BYTES, backendMaxBytes(AttachmentBackend.X0_AT))
        val capped = normalizedConfig(PasteBackendConfig(
            backend = AttachmentBackend.X0_AT,
            sizeLimitBytes = MAX_X0AT_LIMIT_BYTES,
        ))
        assertEquals(MAX_X0AT_LIMIT_BYTES, capped.sizeLimitBytes)
        assertEquals("https://x0.at", capped.endpoint)
    }

    @Test fun litterboxExpiryIsNormalized() {
        assertEquals(DEFAULT_LITTERBOX_EXPIRY, normalizedConfig(PasteBackendConfig(litterboxExpiry = "7d")).litterboxExpiry)
        assertEquals("72h", normalizedConfig(PasteBackendConfig(litterboxExpiry = "72h")).litterboxExpiry)
    }

    @Test fun legacyProtocolAndEndpointMapToStableBackend() {
        assertEquals(AttachmentBackend.TERMBIN, legacyAttachmentBackend("TERMBIN", null))
        assertEquals(
            AttachmentBackend.CRAFTERBIN,
            legacyAttachmentBackend("MULTIPART_0X0", EndpointPreset.CRAFTERBIN.endpoint),
        )
        assertEquals(
            AttachmentBackend.CUSTOM_0X0,
            legacyAttachmentBackend("MULTIPART_0X0", "https://paste.example"),
        )
    }

    @Test fun multipartEncodingIncludesOptionsAndSafeFilename() {
        val boundary = "test"
        assertTrue(MultipartEncoding.field(boundary, "expires", "7d").decodeToString().contains("name=\"expires\""))
        val header = MultipartEncoding.fileHeader(boundary, "files[]", "bad\"\nname.txt", "text/plain").decodeToString()
        assertTrue(header.contains("name=\"files[]\""))
        assertTrue(header.contains("filename=\"bad__name.txt\""))
        assertTrue(header.contains("Content-Type: text/plain"))
        assertTrue(MultipartEncoding.ending(boundary).decodeToString().endsWith("--test--\r\n"))
    }

    @Test fun backendResponsesParseUrlsAndDeletionKeys() {
        assertEquals(
            "https://uguu.se/file.txt",
            BackendResponses.uguu("""{"files":[{"url":"https://uguu.se/file.txt"}]}"""),
        )
        assertEquals(
            "https://paste.c-net.org/id" to "delete-me",
            BackendResponses.cnet("""{"url":"https://paste.c-net.org/id","delete_key":"delete-me"}"""),
        )
        assertEquals("https://files.catbox.moe/a.png", BackendResponses.plain("https://files.catbox.moe/a.png\n"))
    }

    @Test fun x0atUploadSendsNoSecretOrExpiryFields() {
        // x0.at ignores secret/expires; only the file field is sent.
        val config = PasteBackendConfig(
            backend = AttachmentBackend.X0_AT,
            secretUrl = true,
            expiry = "7d",
        )
        assertEquals(emptyList<Pair<String, String>>(), multipart0x0Fields(normalizedConfig(config)))
    }

    @Test fun compatibleBackendsStillSendSecretAndExpiry() {
        val config = normalizedConfig(PasteBackendConfig(
            backend = AttachmentBackend.ZERO_X_ZERO,
            secretUrl = true,
            expiry = "7d",
        ))
        assertEquals(listOf("secret" to "", "expires" to "7d"), multipart0x0Fields(config))
    }
}
