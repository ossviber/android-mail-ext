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

import ch.protonmail.android.extidentities.domain.SmtpServerConfig
import ch.protonmail.android.extidentities.domain.SmtpTestResult
import ch.protonmail.android.extidentities.domain.repository.SmtpConnectionTester
import javax.inject.Inject

class TestSmtpConnection @Inject constructor(
    private val tester: SmtpConnectionTester
) {

    suspend operator fun invoke(
        config: SmtpServerConfig,
        username: String?,
        password: CharArray?
    ): SmtpTestResult = tester.test(config, username, password)

    /**
     * Tests a saved identity: when [password] is blank, the stored sealed password
     * is used instead, so "leave empty to keep" behaves correctly for tests.
     */
    suspend fun testForIdentity(
        identityId: Long,
        hasStoredPassword: Boolean,
        config: SmtpServerConfig,
        username: String?,
        password: CharArray?
    ): SmtpTestResult = tester.testForIdentity(
        identityId = identityId,
        hasStoredPassword = hasStoredPassword,
        config = config,
        username = username,
        password = password
    )
}
