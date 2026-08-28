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

package ch.protonmail.android.extidentities.domain.usecase

import arrow.core.Either
import ch.protonmail.android.extidentities.domain.ExternalIdentityId
import ch.protonmail.android.extidentities.domain.SmtpError
import ch.protonmail.android.extidentities.domain.model.SmtpMessageAttachment
import ch.protonmail.android.extidentities.domain.model.SmtpMessageRecipient
import ch.protonmail.android.extidentities.domain.repository.ExternalSmtpMailSender
import javax.inject.Inject

/**
 * Sends a message through an external identity's SMTP server, bypassing Proton's
 * send pipeline entirely. This is the entry point used by the composer when the
 * selected sender is an external identity.
 */
class SendViaExternalIdentity @Inject constructor(
    private val sender: ExternalSmtpMailSender
) {

    suspend operator fun invoke(
        identityId: ExternalIdentityId,
        to: List<SmtpMessageRecipient>,
        cc: List<SmtpMessageRecipient>,
        bcc: List<SmtpMessageRecipient>,
        subject: String,
        htmlBody: String,
        attachments: List<SmtpMessageAttachment> = emptyList()
    ): Either<SmtpError, Unit> = sender.send(identityId, to, cc, bcc, subject, htmlBody, attachments)
}