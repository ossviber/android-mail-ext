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
import arrow.core.left
import arrow.core.right
import ch.protonmail.android.extidentities.data.local.ExternalIdentitiesLocalDataSource
import ch.protonmail.android.extidentities.domain.ExternalIdentity
import ch.protonmail.android.extidentities.domain.SmtpAuthType
import ch.protonmail.android.extidentities.domain.SmtpError
import ch.protonmail.android.extidentities.domain.SmtpSecurity
import ch.protonmail.android.extidentities.domain.SmtpTestResult
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.Session
import jakarta.mail.Transport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.proton.core.crypto.common.keystore.KeyStoreCrypto
import timber.log.Timber
import java.util.Properties
import javax.inject.Inject
import javax.net.ssl.SSLException

/**
 * Sends mail through an external identity's own SMTP server (Angus Mail / Jakarta Mail),
 * bypassing the Proton API entirely — the same approach FairEmail uses for its identities.
 *
 * SMTP passwords are unsealed from the Keystore only here, inside the data layer,
 * and never returned to callers.
 */
class SmtpMailSender @Inject constructor(
    private val localDataSource: ExternalIdentitiesLocalDataSource,
    private val keyStoreCrypto: KeyStoreCrypto,
    private val mimeMessageBuilder: SmtpMimeMessageBuilder
) {

    suspend fun send(mail: OutgoingSmtpMail): Either<SmtpError, Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val config = mail.identity.smtpServer
            val protocol = protocolFor(config.security)
            val password = resolvePassword(mail.identity)
            val session = buildSession(config, password)
            val message = mimeMessageBuilder.build(session, mail)

            // Connect explicitly with the unsealed credentials: Transport.send would
            // only see the session properties, which never carry the password, so
            // authenticated servers would reject the message with 530.
            val transport = session.getTransport(protocol)
            transport.use {
                if (config.authType == SmtpAuthType.None) {
                    it.connect(config.host, config.port, null, null)
                } else {
                    it.connect(config.host, config.port, config.username, password)
                }
                it.sendMessage(message, message.allRecipients)
            }
        }.fold(
            onSuccess = { Unit.right() },
            onFailure = { mapSendFailure(it).left() }
        )
    }

    /** Throwaway connection check used by the identity editor before saving. */
    suspend fun testConnection(
        config: ch.protonmail.android.extidentities.domain.SmtpServerConfig,
        username: String?,
        password: CharArray?
    ): SmtpTestResult = withContext(Dispatchers.IO) {
        runCatching {
            val session = buildSession(config, password?.concatToString())
            val transport = session.getTransport(protocolFor(config.security))
            transport.use {
                transport.connect(config.host, config.port, username, password?.concatToString())
            }
            SmtpTestResult.Success
        }.getOrElse { SmtpTestResult.Failure(mapSendFailure(it)) }
    }

    private fun buildSession(
        config: ch.protonmail.android.extidentities.domain.SmtpServerConfig,
        password: String?
    ): Session {
        requireNotNull(config.host) { "SMTP host missing" }
        val protocol = protocolFor(config.security)
        val props = Properties().apply {
            put("mail.$protocol.connectiontimeout", CONNECT_TIMEOUT_MS.toString())
            put("mail.$protocol.timeout", READ_TIMEOUT_MS.toString())
            put("mail.$protocol.writetimeout", WRITE_TIMEOUT_MS.toString())
            put("mail.mime.encodefilename", true)
            put("mail.mime.charset", "UTF-8")

            when (config.security) {
                SmtpSecurity.SslTls -> put("mail.smtps.ssl.enable", true)
                SmtpSecurity.StartTls -> {
                    put("mail.smtp.starttls.enable", true)
                    put("mail.smtp.starttls.required", true)
                }

                SmtpSecurity.None -> Unit
            }

            when (config.authType) {
                SmtpAuthType.Auto -> if (password == null && config.username == null) {
                    put("mail.$protocol.auth", false)
                } else {
                    put("mail.$protocol.auth", true)
                }

                SmtpAuthType.Login -> {
                    put("mail.$protocol.auth", true)
                    put("mail.$protocol.auth.mechanisms", "LOGIN")
                }

                SmtpAuthType.Plain -> {
                    put("mail.$protocol.auth", true)
                    put("mail.$protocol.auth.mechanisms", "PLAIN")
                }

                SmtpAuthType.CramMd5 -> {
                    put("mail.$protocol.auth", true)
                    put("mail.$protocol.auth.mechanisms", "CRAM-MD5")
                }

                SmtpAuthType.None -> put("mail.$protocol.auth", false)
            }
        }
        return Session.getInstance(props).apply { debug = false }
    }

    private suspend fun resolvePassword(identity: ExternalIdentity): String? {
        val sealed = localDataSource.getSealedPassword(identity.id.value) ?: return null
        return runCatching { keyStoreCrypto.decrypt(sealed) }
            .onFailure { Timber.e(it, "ext-identities: failed to unseal SMTP password") }
            .getOrNull()
    }

    private fun mapSendFailure(throwable: Throwable): SmtpError = when {
        throwable is AuthenticationFailedException ->
            SmtpError.AuthenticationFailed

        throwable is SSLException || throwable.cause is SSLException ->
            SmtpError.TlsFailed

        throwable.message?.contains("UnknownHostException", ignoreCase = true) == true ||
            throwable.cause?.message?.contains("Unable to resolve host", ignoreCase = true) == true ->
            SmtpError.ConnectionFailed

        else -> {
            Timber.e(throwable, "ext-identities: smtp send failed")
            SmtpError.Unexpected(throwable.message)
        }
    }

    private fun protocolFor(security: SmtpSecurity): String =
        if (security == SmtpSecurity.SslTls) PROTOCOL_SMTPS else PROTOCOL_SMTP

    companion object {
        private const val PROTOCOL_SMTP = "smtp"
        private const val PROTOCOL_SMTPS = "smtps"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val WRITE_TIMEOUT_MS = 30_000
    }
}

/** Convenience for closing a [Transport] regardless of outcome. */
private inline fun <T> Transport.use(block: (Transport) -> T): T =
    try {
        block(this)
    } finally {
        runCatching { close() }
    }
