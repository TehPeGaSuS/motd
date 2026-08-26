package io.github.trevarj.motd.ui.invite

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
    fun `email validation rejects control and malformed values`() {
        assertTrue(validEmail("alice@example.org"))
        assertEquals(false, validEmail("not-an-email"))
        assertEquals(false, validEmail("a@example.org\nJOIN #evil"))
    }
}
