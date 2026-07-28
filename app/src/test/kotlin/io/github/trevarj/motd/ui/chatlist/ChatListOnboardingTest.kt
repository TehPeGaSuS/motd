package io.github.trevarj.motd.ui.chatlist

import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatListOnboardingTest {
    @Test
    fun freshEmptyInstallOpensOnboardingAfterLoading() {
        assertTrue(shouldOpenOnboarding(ChatListState(loading = false)))
    }

    @Test
    fun completedOnboardingKeepsEmptyMainScreen() {
        assertFalse(
            shouldOpenOnboarding(
                ChatListState(loading = false, onboardingComplete = true),
            ),
        )
    }

    @Test
    fun configuredOrStillLoadingStateDoesNotOpenOnboarding() {
        assertFalse(shouldOpenOnboarding(ChatListState()))
        assertFalse(
            shouldOpenOnboarding(
                ChatListState(
                    networks = listOf(
                        NetworkEntity(
                            name = "Libera",
                            role = NetworkRole.DIRECT,
                            host = "irc.example",
                            port = 6697,
                            nick = "me",
                            username = "me",
                            realname = "Me",
                        ),
                    ),
                    loading = false,
                ),
            ),
        )
    }
}
