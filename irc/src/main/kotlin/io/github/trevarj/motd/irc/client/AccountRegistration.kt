package io.github.trevarj.motd.irc.client

data class AccountRegistrationPolicy(
    val beforeConnect: Boolean = false,
    val customAccountName: Boolean = false,
    val emailRequired: Boolean = false,
    val minPasswordLength: Int? = null,
    val maxPasswordLength: Int? = null,
)

sealed interface AccountRegistrationResult {
    data class Success(
        val account: String,
        val message: String,
    ) : AccountRegistrationResult

    data class VerificationRequired(
        val account: String,
        val message: String,
    ) : AccountRegistrationResult
}

fun accountRegistrationPolicy(caps: Set<String>): AccountRegistrationPolicy? {
    val token = caps.firstOrNull { it == ACCOUNT_REGISTRATION_CAP || it.startsWith("$ACCOUNT_REGISTRATION_CAP=") } ?: return null
    val values = token.substringAfter('=', "").split(',').filter(String::isNotBlank)
    val map = values.associate { it.substringBefore('=') to it.substringAfter('=', "") }
    return AccountRegistrationPolicy(
        beforeConnect = "before-connect" in map,
        customAccountName = "custom-account-name" in map,
        emailRequired = "email-required" in map,
        minPasswordLength = map["min-password-length"]?.toIntOrNull()?.takeIf { it > 0 },
        maxPasswordLength = map["max-password-length"]?.toIntOrNull()?.takeIf { it > 0 },
    )
}

const val ACCOUNT_REGISTRATION_CAP = "draft/account-registration"
