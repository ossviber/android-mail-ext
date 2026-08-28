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
import arrow.core.left
import arrow.core.right
import ch.protonmail.android.extidentities.domain.ExternalIdentitiesError
import ch.protonmail.android.extidentities.domain.ExternalIdentity
import ch.protonmail.android.extidentities.domain.ExternalIdentityId
import ch.protonmail.android.extidentities.domain.SmtpAuthType
import ch.protonmail.android.extidentities.domain.SmtpSecurity
import ch.protonmail.android.extidentities.domain.SmtpServerConfig
import ch.protonmail.android.extidentities.domain.repository.ExternalIdentityRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SaveExternalIdentityTest {

    private val repository: ExternalIdentityRepository = mockk()
    private val useCase = SaveExternalIdentity(repository)

    private fun identity(
        email: String = "me@external.example",
        host: String = "smtp.external.example",
        port: Int = 587,
        authType: SmtpAuthType = SmtpAuthType.Auto,
        id: Long = 0L
    ) = ExternalIdentity(
        id = ExternalIdentityId(id),
        email = email,
        smtpServer = SmtpServerConfig(host, port, SmtpSecurity.StartTls, authType)
    )

    @Test
    fun `rejects invalid email`() = runTest {
        val result = useCase(identity(email = "not-an-email"), "pass".toCharArray())
        assertTrue(result.isLeft())
        val error = (result as Either.Left).value
        assertEquals("Invalid email address", (error as ExternalIdentitiesError.Validation).message)
    }

    @Test
    fun `rejects empty host`() = runTest {
        val result = useCase(identity(host = ""), "pass".toCharArray())
        assertTrue((result as Either.Left).value is ExternalIdentitiesError.Validation)
    }

    @Test
    fun `rejects out of range port`() = runTest {
        val result = useCase(identity(port = 99_999), "pass".toCharArray())
        assertTrue((result as Either.Left).value is ExternalIdentitiesError.Validation)
    }

    @Test
    fun `rejects new authenticated identity without password`() = runTest {
        val result = useCase(identity(), password = null)
        assertTrue((result as Either.Left).value is ExternalIdentitiesError.Validation)
    }

    @Test
    fun `allows new identity with auth type None and no password`() = runTest {
        coEvery {
            repository.save(any(), null)
        } returns identity(authType = SmtpAuthType.None).right()

        val result = useCase(identity(authType = SmtpAuthType.None), password = null)
        assertTrue(result.isRight())
    }

    @Test
    fun `persists valid identity through repository`() = runTest {
        val saved = identity(id = 7L)
        coEvery { repository.save(any(), "secret".toCharArray()) } returns saved.right()

        val result = useCase(saved, "secret".toCharArray())
        assertEquals(saved.id, (result as Either.Right).value.id)
    }

    @Test
    fun `propagates storage failure from repository`() = runTest {
        coEvery {
            repository.save(any(), any())
        } returns ExternalIdentitiesError.StorageFailure("disk full").left()

        val result = useCase(identity(), "pass".toCharArray())
        assertTrue((result as Either.Left).value is ExternalIdentitiesError.StorageFailure)
    }
}
