package io.github.trevarj.motd.irc.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccountRegistrationPolicyTest {
    @Test
    fun `parses advertised registration policy and ignores bad bounds`() {
        assertEquals(
            AccountRegistrationPolicy(
                beforeConnect = true,
                customAccountName = true,
                emailRequired = true,
                minPasswordLength = 16,
                maxPasswordLength = 64,
            ),
            accountRegistrationPolicy(
                setOf("draft/account-registration=before-connect,custom-account-name,email-required,min-password-length=16,max-password-length=64,unknown=x"),
            ),
        )
        assertEquals(
            AccountRegistrationPolicy(),
            accountRegistrationPolicy(setOf("draft/account-registration=min-password-length=nope,max-password-length=0")),
        )
        assertNull(accountRegistrationPolicy(setOf("sasl")))
    }
}
