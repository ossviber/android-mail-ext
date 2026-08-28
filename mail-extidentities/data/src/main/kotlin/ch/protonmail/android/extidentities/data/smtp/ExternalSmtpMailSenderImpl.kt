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

import arrow.core.Either
import arrow.core.raise.either
import ch.protonmail.android.extidentities.data.local.ExternalIdentitiesLocalDataSource
import ch.protonmail.android.extidentities.data.mapper.toExternalIdentity
import ch.protonmail.android.extidentities.data.protonauth.ProtonSessionManager
import ch.protonmail.android.extidentities.domain.ExternalIdentityId
import ch.protonmail.android.extidentities.domain.SmtpAuthType
import ch.protonmail.android.extidentities.domain.SmtpError
import ch.protonmail.android.extidentities.domain.model.SmtpMessageAttachment
import ch.protonmail.android.extidentities.domain.model.SmtpMessageRecipient
import ch.protonmail.android.extidentities.domain.repository.ExternalSmtpMailSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.proton.core.crypto.common.keystore.KeyStoreCrypto
import timber.log.Timber
import javax.inject.Inject

class ExternalSmtpMailSenderImpl @Inject constructor(
    private val localDataSource: ExternalIdentitiesLocalDataSource,
    private val smtpMailSender: SmtpMailSender,
    private val keyStoreCrypto: KeyStoreCrypto,
    private val mimeMessageBuilder: SmtpMimeMessageBuilder,
    private val protonSessionManager: ProtonSessionManager
) : ExternalSmtpMailSender {

    override suspend fun send(
        identityId: ExternalIdentityId,
        to: List<SmtpMessageRecipient>,
        cc: List<SmtpMessageRecipient>,
        bcc: List<SmtpMessageRecipient>,
        subject: String,
        htmlBody: String,
        attachments: List<SmtpMessageAttachment>
    ): Either<SmtpError, Unit> = withContext(Dispatchers.IO) {
        either {
            val identityEntity = localDataSource.findById(identityId.value)
            val identity = identityEntity?.toExternalIdentity(
                hasPassword = true,
                serverConfigs = localDataSource.getServerConfigs()
            )
            if (identity == null) {
                raise(SmtpError.Unexpected("External identity no longer exists"))
                return@either
            }

            val password = localDataSource.getSealedPassword(identityId.value)?.let { sealed ->
                runCatching { keyStoreCrypto.decrypt(sealed) }
                    .onFailure { Timber.e(it, "ext-identities: failed to unseal SMTP password") }
                    .getOrNull()
            }
            if (password == null && identity.smtpServer.authType != SmtpAuthType.None) {
                raise(SmtpError.AuthenticationFailed)
                return@either
            }

            val mail = OutgoingSmtpMail(
                identity = identity,
                to = to.map { SmtpRecipient(it.name, it.address) },
                cc = cc.map { SmtpRecipient(it.name, it.address) },
                bcc = bcc.map { SmtpRecipient(it.name, it.address) },
                subject = subject,
                htmlBody = htmlBody,
                attachments = attachments.map { SmtpAttachment(it.fileName, it.mimeType, it.bytes) }
            )

            smtpMailSender.send(mail).bind()

            // Bridge-style direct import of the sent copy into Proton's
            // internal Sent folder.
            runCatching {
                val session = jakarta.mail.Session.getInstance(java.util.Properties())
                val mimeMessage = mimeMessageBuilder.build(session, mail)
                mimeMessage.saveChanges()
                val (ok, detail) = protonSessionManager.importSentCopyToSent(
                    mimeMessage,
                    sentLabelId = identity.sentLabelId
                )
                if (!ok && detail.contains("Invalid label") && identity.sentLabelId != null) {
                    // The stored label id is stale (e.g. a local Rust id) - clear it so
                    // the next automation toggle resolves the server id afresh.
                    Timber.w("ext-identities: clearing stale sent label for " + identityId.value)
                    localDataSource.setSentLabel(identityId.value, null, null)
                }
                Timber.i("ext-identities: sent copy import ok=" + ok + " detail=" + detail)
            }.onFailure { Timber.e(it, "ext-identities: sent copy import crashed") }
        }
    }
}