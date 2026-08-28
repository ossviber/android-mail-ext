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
import arrow.core.raise.either
import arrow.core.raise.ensure
import ch.protonmail.android.extidentities.domain.ExternalIdentitiesError
import ch.protonmail.android.extidentities.domain.ExternalIdentity
import ch.protonmail.android.extidentities.domain.ExternalIdentityId
import ch.protonmail.android.extidentities.domain.SmtpAuthType
import ch.protonmail.android.extidentities.domain.repository.ExternalIdentityRepository
import javax.inject.Inject

class SaveExternalIdentity @Inject constructor(
    private val repository: ExternalIdentityRepository
) {

    /**
     * Validates and persists an identity. When [password] is `null` on update,
     * the previously sealed password is kept.
     */
    suspend operator fun invoke(
        identity: ExternalIdentity,
        password: CharArray?
    ): Either<ExternalIdentitiesError, ExternalIdentity> = either {
        validate(identity, password).bind()
        repository.save(identity, password).bind()
    }

    private fun validate(
        identity: ExternalIdentity,
        password: CharArray?
    ): Either<ExternalIdentitiesError.Validation, Unit> = either {
        ensure(identity.email.isNotBlank() && identity.email.contains('@')) {
            ExternalIdentitiesError.Validation("Invalid email address")
        }
        ensure(identity.smtpServer.host.isNotBlank()) {
            ExternalIdentitiesError.Validation("SMTP host must not be empty")
        }
        ensure(identity.smtpServer.port in 1..65535) {
            ExternalIdentitiesError.Validation("SMTP port out of range")
        }
        val requiresAuth = identity.smtpServer.authType != SmtpAuthType.None
        if (requiresAuth && identity.id == ExternalIdentityId(0)) {
            ensure(password != null && password.isNotEmpty()) {
                ExternalIdentitiesError.Validation("Password required for authenticated SMTP")
            }
        }
    }
}
