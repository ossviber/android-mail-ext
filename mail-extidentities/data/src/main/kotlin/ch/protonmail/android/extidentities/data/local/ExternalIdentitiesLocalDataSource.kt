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

package ch.protonmail.android.extidentities.data.local

import kotlinx.coroutines.flow.Flow

interface ExternalIdentitiesLocalDataSource {

    fun observeAll(): Flow<List<ExternalIdentityEntity>>

    suspend fun getAll(): List<ExternalIdentityEntity>

    suspend fun findById(id: Long): ExternalIdentityEntity?

    /** Returns the persisted entity with its final id assigned. */
    suspend fun upsert(entity: ExternalIdentityEntity, password: CharArray?): ExternalIdentityEntity

    suspend fun delete(id: Long)

    /** Persists the label used by the automation (null clears it). */
    suspend fun setSentLabel(id: Long, labelId: String?, labelName: String?)

    /** Persists the Proton filter id driving the automation (null clears it). */
    suspend fun setSentFilterId(id: Long, filterId: String?)

    suspend fun getSealedPassword(id: Long): String?

    suspend fun hasPassword(id: Long): Boolean

    // --- Stored SMTP server configurations ---

    fun observeServerConfigs(): Flow<List<SmtpServerConfigEntity>>

    suspend fun getServerConfigs(): List<SmtpServerConfigEntity>

    suspend fun upsertServerConfig(entity: SmtpServerConfigEntity): SmtpServerConfigEntity

    suspend fun deleteServerConfig(id: Long)
}