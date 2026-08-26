package io.github.trevarj.motd.ui.invite

import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.prefs.AccountEnrollmentDraft
import io.github.trevarj.motd.data.prefs.AccountEnrollmentProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountSetupInputTest {
    private val draft = AccountEnrollmentDraft(1, AccountEnrollmentProvider.LIBERA, "alice", "a@example.org", "secret")

    @Test
    fun `Libera code accepts token or exact command only`() {
        assertEquals("code", parseVerification("code", draft))
        assertEquals("code", parseVerification("/msg NickServ VERIFY REGISTER alice code", draft))
        assertEquals("code", parseVerification("VERIFY REGISTER alice code", draft))
        assertNull(parseVerification("/msg #channel hello everyone", draft))
        assertNull(parseVerification("/msg NickServ VERIFY REGISTER bob code", draft))
    }

    @Test
    fun `Libera advances only after explicit registration and verification success`() {
        assertTrue(liberaRegistrationAccepted("An email containing nickname activation instructions has been sent to a@example.org."))
        assertEquals(false, liberaRegistrationAccepted("The nickname alice cannot be registered."))
        assertTrue(liberaVerificationSucceeded("alice has now been verified."))
        assertTrue(liberaVerificationSucceeded("alice is already verified."))
        assertEquals(false, liberaVerificationSucceeded("Invalid key for registration."))
    }

    @Test
    fun `canonical OFTC uses NickServ instead of unsupported SASL`() {
        val network =
            NetworkEntity(
                id = 2,
                name = "OFTC",
                role = NetworkRole.DIRECT,
                host = "irc.oftc.net",
                port = 6697,
                nick = "alice",
                username = "alice",
                realname = "Alice",
            )
        val oftcDraft = AccountEnrollmentDraft(2, AccountEnrollmentProvider.OFTC, "alice", "a@example.org", "generated-password")

        assertEquals(AccountEnrollmentProvider.OFTC, accountEnrollmentProvider(network, hasIrcv3Registration = false))
        val activated = activateOftcNetwork(network.copy(saslMechanism = "PLAIN", saslUser = "old", saslPassword = "old"), oftcDraft)
        assertEquals("NONE", activated.saslMechanism)
        assertNull(activated.saslPassword)
        assertEquals("generated-password", activated.nickServPassword)
        assertEquals("PASSWORD_NICK", activated.nickServIdentifySyntax)
    }

    @Test
    fun `OFTC verification accepts only OFTC HTTPS links and confirmed info`() {
        val url = "https://verify.oftc.net/account/token"
        assertEquals(url, parseOftcVerificationUrl("Visit $url to continue."))
        assertNull(parseOftcVerificationUrl("https://oftc.net.evil.example/token"))
        assertTrue(oftcAccountVerified("Email address: a@example.org (verified)"))
        assertEquals(false, oftcAccountVerified("Email address: a@example.org (unverified)"))
    }

    @Test
    fun `email validation rejects control and malformed values`() {
        assertTrue(validEmail("alice@example.org"))
        assertEquals(false, validEmail("not-an-email"))
        assertEquals(false, validEmail("a@example.org\nJOIN #evil"))
    }
}
