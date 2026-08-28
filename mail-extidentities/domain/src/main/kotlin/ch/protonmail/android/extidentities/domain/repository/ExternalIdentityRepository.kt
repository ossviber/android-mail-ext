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

import arrow.core.Either
import ch.protonmail.android.extidentities.domain.ExternalIdentitiesError
import ch.protonmail.android.extidentities.domain.ExternalIdentity
import ch.protonmail.android.extidentities.domain.ExternalIdentityId
import ch.protonmail.android.extidentities.domain.StoredSmtpServerConfig
import kotlinx.coroutines.flow.Flow
import me.proton.core.domain.entity.UserId

/**
 * Local repository for external identities (addresses from other providers sent
 * through user-configured SMTP servers). Storage is device-local only; identities
 * are never synced to Proton servers.
 */
interface ExternalIdentityRepository {

    fun observeAll(): Flow<List<ExternalIdentity>>

    suspend fun findById(id: ExternalIdentityId): ExternalIdentity?

    /**
     * Inserts or updates an identity.
     *
     * @param password plain SMTP password to (re)seal, or `null` to keep the
     * existing password on update. A password is required when creating a new
     * identity whose [ch.protonmail.android.extidentities.domain.SmtpAuthType]
     * requires authentication.
     */
    suspend fun save(
        identity: ExternalIdentity,
        password: CharArray?
    ): Either<ExternalIdentitiesError, ExternalIdentity>

    /** Whether a (sealed) password exists for the given identity. */
    suspend fun hasPassword(id: ExternalIdentityId): Boolean

    suspend fun delete(id: ExternalIdentityId): Either<ExternalIdentitiesError, Unit>

    // ------------------------------------------------------------------
    // Stored SMTP server configurations (shared across identities)
    // ------------------------------------------------------------------

    fun observeServerConfigs(): Flow<List<StoredSmtpServerConfig>>

    suspend fun saveServerConfig(config: StoredSmtpServerConfig): Either<ExternalIdentitiesError, StoredSmtpServerConfig>

    suspend fun deleteServerConfig(configId: Long): Either<ExternalIdentitiesError, Unit>


    /**
     * Ensures the per-identity label used by the sent e-mail automation exists
     * (creating it when missing) and persists its id and name on the identity.
     * A legacy folder from earlier versions is deleted; its messages return to
     * the inbox.
     */
    suspend fun ensureSentLabel(
        userId: UserId,
        identityId: ExternalIdentityId,
        labelBase: String
    ): Either<ExternalIdentitiesError, ExternalIdentity>

    /** Persists the automation label reference (nulls turn the labeling off). */
    suspend fun setSentLabel(
        identityId: ExternalIdentityId,
        labelId: String?,
        labelName: String?
    )

    /** Persists the Proton filter id driving the automation (null clears it). */
    suspend fun setSentFilterId(identityId: ExternalIdentityId, filterId: String?)

    /**
     * Applies or removes the identity's sent label on the already stored sent
     * e-mails. [apply] = true labels every message in the Sent location with
     * the identity label; false removes the label from every message carrying it.
     */
    suspend fun applySentLabelToExisting(
        userId: UserId,
        identityId: ExternalIdentityId,
        apply: Boolean
    ): Either<ExternalIdentitiesError, Unit>
}