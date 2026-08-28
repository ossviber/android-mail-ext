/*
 * Copyright (c) 2025 Proton Technologies AG
 * This file is part of Proton Technologies AG and Proton Mail.
 *
 * Proton Mail is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Proton Mail is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Proton Mail. If not, see <https://www.gnu.org/licenses/>.
 */

package ch.protonmail.android.extidentities.domain

/**
 * An external email identity (address hosted outside Proton) together with the
 * SMTP server used to send mail from it. Modeled after FairEmail's `EntityIdentity`,
 * which embeds the full SMTP configuration into the identity.
 *
 * The SMTP password is intentionally absent from this model: it is sealed with the
 * Android Keystore in the data layer and never exposed to the domain/presentation layers.
 */
data class ExternalIdentity(
    val id: ExternalIdentityId,
    val email: String,
    val displayName: String? = null,
    val replyTo: String? = null,
    val signatureHtml: String? = null,
    val isEnabled: Boolean = true,
    val sortOrder: Int = 0,
    // Proton label that tags the sent copies stored in Proton for this identity
    // (see SentFolderAutomation); null when the automation is off.
    val sentLabelId: String? = null,
    // Exact folder name on the server (the sieve rule must reference it verbatim).
    val sentLabelName: String? = null,
    // Proton filter id created for the automation (mail/v4/filters).
    val sentFilterId: String? = null,
    // Reference to a stored SMTP server configuration (shared across identities).
    // `null` only for legacy identities that predate stored configs.
    val smtpServerConfigId: Long? = null,
    // Resolved connection settings for sending: host/port/security/auth come from
    // the stored config; username is per-identity.
    val smtpServer: SmtpServerConfig
)

@JvmInline
value class ExternalIdentityId(val value: Long)

/** Transport security applied to the SMTP connection. Mirrors FairEmail's encryption constants. */
enum class SmtpSecurity {
    /** Implicit TLS on connect (typically port 465). Protocol becomes `smtps`. */
    SslTls,

    /** Plain connect, then upgrade via STARTTLS (typically port 587). */
    StartTls,

    /** No TLS at all (only sensible for local relays / testing). */
    None
}

enum class SmtpAuthType {
    /** Let the client negotiate among PLAIN/LOGIN/CRAM-MD5, whatever the server advertises. */
    Auto,

    /** Force AUTH LOGIN. */
    Login,

    /** Force AUTH PLAIN. */
    Plain,

    /** Force AUTH CRAM-MD5. */
    CramMd5,

    /** No authentication (open relay / local MSA). */
    None
}

/**
 * A stored SMTP server configuration shared by multiple external identities.
 * Host/port/security/auth live here once; each identity adds its own username
 * and (sealed) password.
 */
data class StoredSmtpServerConfig(
    val id: Long,
    val name: String,
    val host: String,
    val port: Int,
    val security: SmtpSecurity,
    val authType: SmtpAuthType
)

/** Fully resolved SMTP connection settings for one identity. */
data class SmtpServerConfig(
    val host: String,
    val port: Int,
    val security: SmtpSecurity,
    val authType: SmtpAuthType,
    val username: String? = null
)

sealed interface ExternalIdentitiesError {
    data class StorageFailure(val message: String?) : ExternalIdentitiesError
    data class Validation(val message: String) : ExternalIdentitiesError
}

/**
 * Result of an SMTP connection test performed before saving an identity.
 */
sealed interface SmtpTestResult {
    data object Success : SmtpTestResult
    data class Failure(val error: SmtpError) : SmtpTestResult
}

sealed interface SmtpError {
    data object AuthenticationFailed : SmtpError
    data object ConnectionFailed : SmtpError
    data object TlsFailed : SmtpError
    data class Unexpected(val message: String?) : SmtpError
}