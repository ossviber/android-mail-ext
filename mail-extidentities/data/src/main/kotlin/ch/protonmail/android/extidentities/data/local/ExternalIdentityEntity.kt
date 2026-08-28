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

package ch.protonmail.android.extidentities.data.local

import kotlinx.serialization.Serializable

/**
 * Persisted representation of an external identity. The SMTP password is NOT part
 * of this DTO: it is sealed with [me.proton.core.crypto.common.keystore.KeyStoreCrypto]
 * and stored under its own preference key (see [ExternalIdentitiesLocalDataSourceImpl]).
 */
@Serializable
data class ExternalIdentityEntity(
    val id: Long,
    val email: String,
    val displayName: String? = null,
    val replyTo: String? = null,
    val signatureHtml: String? = null,
    val isEnabled: Boolean = true,
    val sortOrder: Int = 0,
    val sentLabelId: String? = null,
    val sentLabelName: String? = null,
    val sentFilterId: String? = null,
    // Reference to a stored SMTP server configuration (0 = legacy, migrate on read).
    val smtpServerConfigId: Long = 0L,
    // Per-identity SMTP username.
    val smtpUsername: String? = null,
    // Legacy inline SMTP settings, kept only until the config migration rewrites
    // the store; new writes use smtpServerConfigId.
    val smtpHost: String? = null,
    val smtpPort: Int? = null,
    val smtpSecurity: SmtpSecurityDto = SmtpSecurityDto.StartTls,
    val smtpAuthType: SmtpAuthTypeDto = SmtpAuthTypeDto.Auto
)

@Serializable
enum class SmtpSecurityDto {
    SslTls,
    StartTls,
    None
}

@Serializable
enum class SmtpAuthTypeDto {
    Auto,
    Login,
    Plain,
    CramMd5,
    None
}