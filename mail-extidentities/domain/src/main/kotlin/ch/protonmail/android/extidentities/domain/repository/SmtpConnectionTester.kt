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

package ch.protonmail.android.extidentities.domain.repository

import ch.protonmail.android.extidentities.domain.SmtpServerConfig
import ch.protonmail.android.extidentities.domain.SmtpTestResult

/** Performs a throwaway SMTP connection check against a candidate server config. */
interface SmtpConnectionTester {

    suspend fun test(
        config: SmtpServerConfig,
        username: String?,
        password: CharArray?
    ): SmtpTestResult

    /**
     * Same as [test], but falls back to the stored (sealed) password of an existing
     * identity when [password] is null — used by the editor's "Test" action.
     */
    suspend fun testForIdentity(
        identityId: Long,
        hasStoredPassword: Boolean,
        config: SmtpServerConfig,
        username: String?,
        password: CharArray?
    ): SmtpTestResult = test(config, username, password)
}
