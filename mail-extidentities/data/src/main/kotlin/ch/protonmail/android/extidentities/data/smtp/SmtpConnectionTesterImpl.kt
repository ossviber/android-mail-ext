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
import ch.protonmail.android.extidentities.domain.SmtpError
import ch.protonmail.android.extidentities.domain.SmtpServerConfig
import ch.protonmail.android.extidentities.domain.SmtpTestResult
import ch.protonmail.android.extidentities.domain.repository.SmtpConnectionTester
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.proton.core.crypto.common.keystore.KeyStoreCrypto
import javax.inject.Inject

class SmtpConnectionTesterImpl @Inject constructor(
    private val smtpMailSender: SmtpMailSender,
    private val localDataSource: ExternalIdentitiesLocalDataSource,
    private val keyStoreCrypto: KeyStoreCrypto
) : SmtpConnectionTester {

    override suspend fun test(
        config: SmtpServerConfig,
        username: String?,
        password: CharArray?
    ): SmtpTestResult = smtpMailSender.testConnection(config, username, password)

    override suspend fun testForIdentity(
        identityId: Long,
        hasStoredPassword: Boolean,
        config: SmtpServerConfig,
        username: String?,
        password: CharArray?
    ): SmtpTestResult = withContext(Dispatchers.IO) {
        val effectivePassword = password ?: storedPassword(identityId, hasStoredPassword)
        smtpMailSender.testConnection(config, username, effectivePassword)
    }

    private suspend fun storedPassword(identityId: Long, hasStoredPassword: Boolean): CharArray? {
        if (!hasStoredPassword) return null
        val sealed = localDataSource.getSealedPassword(identityId) ?: return null
        return runCatching { keyStoreCrypto.decrypt(sealed) }
            .getOrNull()
            ?.toCharArray()
    }

    /** Exposed for tests / future reuse: wraps a test result into an Either. */
    fun asEither(result: SmtpTestResult): Either<SmtpError, Unit> = either {
        when (result) {
            is SmtpTestResult.Failure -> raise(result.error)
            SmtpTestResult.Success -> Unit
        }
    }
}
