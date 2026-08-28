package ch.protonmail.android.extidentities.domain.repository

/** A dedicated Proton web session used by the external-identities feature. */
data class ProtonSessionInfo(
    val uid: String,
    val accessToken: String,
    val refreshToken: String,
    val username: String
)

sealed interface ProtonLoginStatus {
    data class Success(val username: String, val addresses: List<String>) : ProtonLoginStatus
    data object NeedsTotp : ProtonLoginStatus
    data class Error(val message: String) : ProtonLoginStatus
}

/**
 * A dedicated Proton API session for the external-identities feature: full
 * SRP login independent from the Rust mail session, used for the web API
 * calls the Rust SDK does not expose (the sent-copy import, addresses).
 */
interface ProtonSessionRepository {

    suspend fun getStoredSession(): ProtonSessionInfo?

    suspend fun getProtonAddresses(): List<String>

    suspend fun signOut()

    suspend fun login(username: String, password: String, totp: String? = null): ProtonLoginStatus

    suspend fun continueLogin(totp: String): ProtonLoginStatus

    /** Finds an existing filter id by its exact name. */
    suspend fun findFilterIdByName(name: String): String?

    /** Creates a filter; returns its id or null on failure. */
    suspend fun createFilter(name: String, sieve: String): String?

    /** Enables or disables a filter by id. */
    suspend fun setFilterEnabled(filterId: String, enabled: Boolean): Boolean
}