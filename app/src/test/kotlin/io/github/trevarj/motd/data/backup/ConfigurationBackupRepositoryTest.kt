package io.github.trevarj.motd.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.attachment.AttachmentPrefsImpl
import io.github.trevarj.motd.avatar.AvatarPrefsImpl
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.ObfsMode
import io.github.trevarj.motd.data.db.inMemoryDb
import io.github.trevarj.motd.data.prefs.AppearancePrefsImpl
import io.github.trevarj.motd.data.prefs.BouncerKindPrefsImpl
import io.github.trevarj.motd.data.prefs.ContentPreviewPrefsImpl
import io.github.trevarj.motd.data.prefs.DataStoreSettingsRepository
import io.github.trevarj.motd.data.prefs.ReplyPrefsImpl
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConfigurationBackupRepositoryTest {

    @Test
    fun credentialsExcludedExportOmitsSecretsAndImportsAsPendingCredentials() = runTest {
        val sourceDb = inMemoryDb()
        val source = repository(sourceDb)
        sourceDb.networkDao().insert(secretNetwork(clientCertAlias = "device-cert"))

        val raw = source.exportToString(
            mode = BackupExportMode.CREDENTIALS_EXCLUDED,
            nowEpochMillis = 1_000L,
        )

        assertFalse(raw.contains("sasl-secret"))
        assertFalse(raw.contains("server-secret"))
        assertFalse(raw.contains("vless://secret"))

        val targetDb = inMemoryDb()
        val target = repository(targetDb)
        val preview = target.preview(raw, importMode = BackupImportMode.MERGE)
        assertEquals(1, preview.addedNetworks)
        assertEquals(1, preview.missingCredentialNetworks)

        target.import(raw, importMode = BackupImportMode.MERGE)

        val imported = targetDb.networkDao().allNow().single()
        assertNull(imported.saslPassword)
        assertNull(imported.serverPassword)
        assertNull(imported.obfsLink)
        assertEquals(
            "saslPassword,serverPassword,obfsLink,clientCertificate",
            imported.pendingCredentialRequirements,
        )
        assertFalse(imported.autoConnect)
        assertEquals(true, imported.restoreAutoConnect)
    }

    @Test
    fun encryptedExportRoundTripsCredentials() = runTest {
        val sourceDb = inMemoryDb()
        val source = repository(sourceDb)
        sourceDb.networkDao().insert(secretNetwork(clientCertAlias = null))

        val raw = source.exportToString(
            mode = BackupExportMode.ENCRYPTED_WITH_CREDENTIALS,
            password = "correct horse battery",
            nowEpochMillis = 1_000L,
        )

        assertFalse(raw.contains("sasl-secret"))
        assertFalse(raw.contains("server-secret"))
        assertFalse(raw.contains("vless://secret"))

        val targetDb = inMemoryDb()
        val target = repository(targetDb)
        val preview = target.preview(
            raw,
            password = "correct horse battery",
            importMode = BackupImportMode.MERGE,
        )
        assertEquals(true, preview.containsSecrets)
        assertEquals(0, preview.missingCredentialNetworks)

        target.import(raw, password = "correct horse battery", importMode = BackupImportMode.MERGE)

        val imported = targetDb.networkDao().allNow().single()
        assertEquals("sasl-secret", imported.saslPassword)
        assertEquals("server-secret", imported.serverPassword)
        assertEquals("vless://secret", imported.obfsLink)
        assertNull(imported.pendingCredentialRequirements)
        assertEquals(true, imported.autoConnect)
    }

    @Test
    fun wrongPasswordRejectsEncryptedImportWithoutMutation() = runTest {
        val sourceDb = inMemoryDb()
        val source = repository(sourceDb)
        sourceDb.networkDao().insert(secretNetwork(clientCertAlias = null))
        val raw = source.exportToString(
            mode = BackupExportMode.ENCRYPTED_WITH_CREDENTIALS,
            password = "correct horse battery",
            nowEpochMillis = 1_000L,
        )

        val targetDb = inMemoryDb()
        val target = repository(targetDb)

        try {
            target.import(raw, password = "wrong horse battery", importMode = BackupImportMode.MERGE)
            fail("wrong password must reject encrypted import")
        } catch (_: Exception) {
            // Expected: GCM authentication fails before any import mutation.
        }
        assertEquals(emptyList<NetworkEntity>(), targetDb.networkDao().allNow())
    }

    private fun repository(db: io.github.trevarj.motd.data.db.MotdDatabase): ConfigurationBackupRepositoryImpl {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = DataStoreSettingsRepository(context)
        return ConfigurationBackupRepositoryImpl(
            db = db,
            settingsRepository = settings,
            appearancePrefs = AppearancePrefsImpl(context),
            contentPreviewPrefs = ContentPreviewPrefsImpl(context),
            replyPrefs = ReplyPrefsImpl(context),
            attachmentPrefs = AttachmentPrefsImpl(context),
            avatarPrefs = AvatarPrefsImpl(context),
            bouncerKindPrefs = BouncerKindPrefsImpl(context),
            pushProviderPrefs = settings,
        )
    }

    private fun secretNetwork(clientCertAlias: String?): NetworkEntity = NetworkEntity(
        name = "libera",
        role = NetworkRole.DIRECT,
        host = "irc.libera.chat",
        port = 6697,
        tls = true,
        nick = "me",
        username = "me",
        realname = "Me",
        saslMechanism = "PLAIN",
        saslUser = "me",
        saslPassword = "sasl-secret",
        serverPassword = "server-secret",
        clientCertAlias = clientCertAlias,
        autoConnect = true,
        obfsMode = ObfsMode.EMBEDDED_REALITY,
        obfsLink = "vless://secret",
    )
}
