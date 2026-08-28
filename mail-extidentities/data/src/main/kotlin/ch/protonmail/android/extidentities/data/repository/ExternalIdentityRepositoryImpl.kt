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

package ch.protonmail.android.extidentities.data.repository

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import ch.protonmail.android.extidentities.data.local.ExternalIdentitiesLocalDataSource
import ch.protonmail.android.extidentities.data.protonauth.ProtonSessionManager
import ch.protonmail.android.extidentities.data.local.ExternalIdentitiesLocalDataSourceImpl.Companion.NEW_ID
import ch.protonmail.android.extidentities.data.mapper.toDomain
import ch.protonmail.android.extidentities.data.mapper.toEntity
import ch.protonmail.android.extidentities.data.mapper.toExternalIdentity
import ch.protonmail.android.extidentities.domain.StoredSmtpServerConfig
import ch.protonmail.android.extidentities.domain.ExternalIdentitiesError
import ch.protonmail.android.extidentities.domain.ExternalIdentity
import ch.protonmail.android.extidentities.domain.ExternalIdentityId
import ch.protonmail.android.extidentities.domain.repository.ExternalIdentityRepository
import ch.protonmail.android.extidentities.domain.sentLabelNameFor
import ch.protonmail.android.mailsession.data.usecase.ExecuteWithUserSession
import ch.protonmail.android.maillabel.domain.repository.LabelRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.proton.core.domain.entity.UserId
import timber.log.Timber
import kotlinx.coroutines.withTimeout
import uniffi.mail_uniffi.Id
import uniffi.mail_uniffi.VoidActionResult
import uniffi.mail_uniffi.WellKnownLabelColor
import uniffi.mail_uniffi.createCustomLabel
import uniffi.mail_uniffi.deleteLabel
import uniffi.mail_uniffi.updateCustomFolder
import javax.inject.Inject

@SuppressWarnings("TooManyFunctions")
class ExternalIdentityRepositoryImpl @Inject constructor(
    private val localDataSource: ExternalIdentitiesLocalDataSource,
    private val executeWithUserSession: ExecuteWithUserSession,
    private val labelRepository: LabelRepository,
    private val protonSessionManager: ProtonSessionManager
) : ExternalIdentityRepository {

    private val setupMutex = Mutex()


    override fun observeAll(): Flow<List<ExternalIdentity>> =
        combine(
            localDataSource.observeAll(),
            localDataSource.observeServerConfigs()
        ) { entities, configs ->
            entities.map { it.toExternalIdentity(hasPassword = true, serverConfigs = configs) }
        }

    override suspend fun findById(id: ExternalIdentityId): ExternalIdentity? {
        val entity = localDataSource.findById(id.value) ?: return null
        val configs = localDataSource.getServerConfigs()
        return entity.toExternalIdentity(hasPassword = true, serverConfigs = configs)
    }

    override suspend fun save(
        identity: ExternalIdentity,
        password: CharArray?
    ): Either<ExternalIdentitiesError, ExternalIdentity> =
        Either.catch {
            val configs = localDataSource.getServerConfigs()
            localDataSource.upsert(identity.toEntity(), password)
                .toExternalIdentity(hasPassword = true, serverConfigs = configs)
        }.mapLeft {
            Timber.e(it, "ext-identities: failed to persist identity")
            ExternalIdentitiesError.StorageFailure(it.message)
        }

    override suspend fun hasPassword(id: ExternalIdentityId): Boolean =
        localDataSource.hasPassword(id.value)

    override suspend fun delete(id: ExternalIdentityId): Either<ExternalIdentitiesError, Unit> =
        Either.catch { localDataSource.delete(id.value) }
            .mapLeft {
                Timber.e(it, "ext-identities: failed to delete identity")
                ExternalIdentitiesError.StorageFailure(it.message)
            }
            .map { }

    override suspend fun ensureSentLabel(
        userId: UserId,
        identityId: ExternalIdentityId,
        labelBase: String
    ): Either<ExternalIdentitiesError, ExternalIdentity> = setupMutex.withLock {
        Timber.d("ext-identities: ensureSentLabel start for " + identityId.value)
        val identity = localDataSource.findById(identityId.value)?.toExternalIdentity(
            hasPassword = true,
            serverConfigs = localDataSource.getServerConfigs()
        )
        if (identity == null) {
            return@withLock ExternalIdentitiesError.StorageFailure("identity $identityId not found").left()
        }

        val labelName = sentLabelNameFor(labelBase, identity.email)

        runCatching { withTimeout(POLL_TIMEOUT_MS) { executeWithUserSession(userId) { it.pollEvents() } } }

        var labelId = protonSessionManager.findServerLabelIdByName(labelName)

        if (labelId == null) {
            // Legacy cleanup: a FOLDER with the automation name comes from the
            // pre-label implementation. Deleting it returns its messages to the
            // inbox - nothing is lost.
            val legacyFolder = labelRepository.observeCustomFolders(userId).first()
                .firstOrNull { it.name == labelName || it.labelId.id == (identity.sentLabelId ?: "") }
            if (legacyFolder != null && legacyFolder.labelId.id.toULongOrNull() != null) {
                val delOk = executeWithUserSession(userId) { wrapper ->
                    when (val result = deleteLabel(
                        session = wrapper.getRustUserSession(),
                        labelId = Id(legacyFolder.labelId.id.toULong())
                    )) {
                        is VoidActionResult.Error -> {
                            Timber.w("ext-identities: legacy folder delete failed: " + result.v1.toString())
                            false
                        }
                        is VoidActionResult.Ok -> true
                    }
                }
                Timber.i("ext-identities: legacy folder deleted=" + delOk.fold({ false }, { it }))
            }

            val createOk = executeWithUserSession(userId) { wrapper ->
                when (val result = createCustomLabel(
                    session = wrapper.getRustUserSession(),
                    name = labelName,
                    color = WellKnownLabelColor.ENZIAN
                )) {
                    is VoidActionResult.Error -> {
                        Timber.w("ext-identities: createCustomLabel failed: " + result.v1.toString())
                        false
                    }
                    is VoidActionResult.Ok -> true
                }
            }
            val createFailed = createOk.fold(ifLeft = { true }, ifRight = { !it })
            if (createFailed) {
                return@withLock ExternalIdentitiesError.StorageFailure("createCustomLabel failed").left()
            }
            repeat(FOLDER_RESOLVE_ATTEMPTS) {
                runCatching { withTimeout(POLL_TIMEOUT_MS) { executeWithUserSession(userId) { it.pollEvents() } } }
                labelId = protonSessionManager.findServerLabelIdByName(labelName)
                if (labelId != null) return@repeat
                delay(FOLDER_RESOLVE_DELAY_MS)
            }
            if (labelId == null) {
                Timber.e("ext-identities: label created but not found on server")
                return@withLock ExternalIdentitiesError.StorageFailure("label not found after creation").left()
            }
        }

        localDataSource.setSentLabel(identityId.value, labelId, labelName)
        Timber.i("ext-identities: label " + labelName + " ensured for identity " + identityId.value)

        localDataSource.findById(identityId.value)?.toExternalIdentity(
            hasPassword = true,
            serverConfigs = localDataSource.getServerConfigs()
        )?.right()
            ?: ExternalIdentitiesError.StorageFailure("identity $identityId not found").left()
    }

    override suspend fun setSentLabel(identityId: ExternalIdentityId, labelId: String?, labelName: String?) {
        localDataSource.setSentLabel(identityId.value, labelId, labelName)
    }

    override suspend fun setSentFilterId(identityId: ExternalIdentityId, filterId: String?) {
        localDataSource.setSentFilterId(identityId.value, filterId)
    }

    override fun observeServerConfigs(): Flow<List<StoredSmtpServerConfig>> =
        localDataSource.observeServerConfigs().map { entities ->
            entities.map { it.toDomain() }
        }

    override suspend fun saveServerConfig(
        config: StoredSmtpServerConfig
    ): Either<ExternalIdentitiesError, StoredSmtpServerConfig> =
        Either.catch {
            localDataSource.upsertServerConfig(config.toEntity()).toDomain()
        }.mapLeft {
            Timber.e(it, "ext-identities: failed to persist server config")
            ExternalIdentitiesError.StorageFailure(it.message)
        }

    override suspend fun deleteServerConfig(configId: Long): Either<ExternalIdentitiesError, Unit> =
        Either.catch { localDataSource.deleteServerConfig(configId) }
            .mapLeft {
                Timber.e(it, "ext-identities: failed to delete server config")
                ExternalIdentitiesError.StorageFailure(it.message)
            }
            .map { }

    private companion object {
        const val FOLDER_RESOLVE_ATTEMPTS = 5
        const val FOLDER_RESOLVE_DELAY_MS = 600L
        const val POLL_TIMEOUT_MS = 5000L
    }
}