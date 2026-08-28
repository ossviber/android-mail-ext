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
import jakarta.activation.DataHandler
import jakarta.mail.Message
import jakarta.mail.util.ByteArrayDataSource
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import java.util.Properties
import javax.inject.Inject

/**
 * Builds a Jakarta Mail [MimeMessage] for an external identity, mirroring the
 * structure FairEmail uses: `multipart/mixed > multipart/alternative` (plain+html),
 * with attachments added to the outer mixed part. Validated end-to-end in
 * `smtp-smoke/` against a live SMTP conversation.
 */
class SmtpMimeMessageBuilder @Inject constructor() {

    fun build(session: Session, mail: OutgoingSmtpMail): MimeMessage {
        val message = MimeMessage(session)

        message.setFrom(
            InternetAddress(mail.identity.email, mail.identity.displayName ?: "", "UTF-8")
        )
        mail.identity.replyTo?.takeIf { it.isNotBlank() }?.let { replyTo ->
            message.setReplyTo(arrayOf(InternetAddress(replyTo)))
        }

        setRecipients(message, Message.RecipientType.TO, mail.to)
        if (mail.cc.isNotEmpty()) setRecipients(message, Message.RecipientType.CC, mail.cc)
        if (mail.bcc.isNotEmpty()) setRecipients(message, Message.RecipientType.BCC, mail.bcc)

        message.setSubject(mail.subject, "UTF-8")
        message.setSentDate(java.util.Date())

        val textPart = MimeBodyPart().apply {
            setText(htmlToPlainText(mail.htmlBody), "UTF-8", "plain")
        }
        val htmlPart = MimeBodyPart().apply {
            setText(mail.htmlBody, "UTF-8", "html")
        }
        val alternative = MimeMultipart("alternative").apply {
            addBodyPart(textPart)
            addBodyPart(htmlPart)
        }

        if (mail.attachments.isEmpty()) {
            message.setContent(alternative)
        } else {
            val mixed = MimeMultipart("mixed")
            val bodyWrapper = MimeBodyPart().apply { setContent(alternative) }
            mixed.addBodyPart(bodyWrapper)
            mail.attachments.forEach { attachment ->
                mixed.addBodyPart(buildAttachmentPart(attachment))
            }
            message.setContent(mixed)
        }

        message.saveChanges()
        return message
    }

    private fun buildAttachmentPart(attachment: SmtpAttachment): MimeBodyPart =
        MimeBodyPart().apply {
            // ByteArrayDataSource avoids temp files; the MIME type is guessed by
            // the caller and passed through as-is.
            dataHandler = DataHandler(
                ByteArrayDataSource(attachment.bytes, attachment.mimeType ?: "application/octet-stream")
            )
            fileName = attachment.fileName
        }

    private fun setRecipients(
        message: MimeMessage,
        type: Message.RecipientType,
        recipients: List<SmtpRecipient>
    ) {
        message.setRecipients(
            type,
            recipients.map { InternetAddress(it.address, it.name ?: "", "UTF-8") }.toTypedArray()
        )
    }

    /** Minimal HTML→plain conversion (block tags to line breaks, entities unescaped). */
    private fun htmlToPlainText(html: String): String = html
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("</p\\s*>", RegexOption.IGNORE_CASE), "\n\n")
        .replace(Regex("<[^>]*>"), "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .trim() + "\n"
}