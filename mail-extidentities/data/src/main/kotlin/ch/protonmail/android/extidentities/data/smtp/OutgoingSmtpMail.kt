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

package ch.protonmail.android.extidentities.data.smtp

import ch.protonmail.android.extidentities.domain.ExternalIdentity

/** A recipient (To/Cc/Bcc) for an outbound SMTP message. */
data class SmtpRecipient(
    val name: String? = null,
    val address: String
)

data class SmtpAttachment(
    val fileName: String,
    val mimeType: String?,
    val bytes: ByteArray
)

/**
 * Fully materialized message ready to be handed to [SmtpMailSender].
 * Built by the composer integration from the Rust draft's fields.
 */
data class OutgoingSmtpMail(
    val identity: ExternalIdentity,
    val to: List<SmtpRecipient>,
    val cc: List<SmtpRecipient> = emptyList(),
    val bcc: List<SmtpRecipient> = emptyList(),
    val subject: String,
    val htmlBody: String,
    val attachments: List<SmtpAttachment> = emptyList()
)