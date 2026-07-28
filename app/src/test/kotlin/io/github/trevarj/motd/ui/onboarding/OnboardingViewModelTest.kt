package io.github.trevarj.motd.ui.onboarding

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class OnboardingViewModelTest {
    @Test
    fun `await current onboarding resource retries until lookup succeeds`() = runTest {
        val resource = Any()
        var attempts = 0

        val result = awaitCurrentOnboardingResource(
            expectedNetworkId = 7L,
            currentNetworkId = { 7L },
            lookup = {
                attempts += 1
                if (attempts == 3) resource else null
            },
            maxAttempts = 5,
            delayMs = 1L,
        )

        assertSame(resource, result)
        assertEquals(3, attempts)
    }

    @Test
    fun `await current onboarding resource stops when network changes`() = runTest {
        var attempts = 0

        val result = awaitCurrentOnboardingResource<Any>(
            expectedNetworkId = 7L,
            currentNetworkId = { 8L },
            lookup = {
                attempts += 1
                Any()
            },
            maxAttempts = 5,
            delayMs = 1L,
        )

        assertNull(result)
        assertEquals(0, attempts)
    }
}
