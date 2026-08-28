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
import ch.protonmail.android.extidentities.domain.ExternalIdentitiesError
import ch.protonmail.android.extidentities.domain.ExternalIdentity
import ch.protonmail.android.extidentities.domain.ExternalIdentityId
import ch.protonmail.android.extidentities.domain.repository.ExternalIdentityRepository
import ch.protonmail.android.mailsession.domain.usecase.ObservePrimaryUserId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import me.proton.core.domain.entity.UserId
import javax.inject.Inject

/**
 * Sets up the sent e-mail labeling for an identity: creates the per-identity Proton
 * label that tags the sent copies stored in Proton and persists its id on the identity.
 * The labeling itself happens at import time (see ProtonSessionManager).
 */
class SetupExternalSentAutomation @Inject constructor(
    private val repository: ExternalIdentityRepository,
    private val observePrimaryUserId: ObservePrimaryUserId
) {

    suspend operator fun invoke(
        identityId: ExternalIdentityId,
        labelBase: String
    ): Either<ExternalIdentitiesError, ExternalIdentity> {
        val userId: UserId = observePrimaryUserId().filterNotNull().first()
            ?: return ExternalIdentitiesError.StorageFailure(NO_PRIMARY).left()
        return repository.ensureSentLabel(userId, identityId, labelBase)
    }

    /** Turns the labeling off: the identity no longer tags its stored sent copies. */
    suspend fun clear(identityId: ExternalIdentityId) {
        repository.setSentLabel(identityId, null, null)
    }

    /** Persists the Proton filter id driving the automation (null clears it). */
    suspend fun setFilterId(identityId: ExternalIdentityId, filterId: String?) {
        repository.setSentFilterId(identityId, filterId)
    }
}

private const val NO_PRIMARY = "no primary user session"