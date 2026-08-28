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

package ch.protonmail.android.extidentities.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.protonmail.android.extidentities.domain.ExternalIdentityId
import ch.protonmail.android.extidentities.domain.StoredSmtpServerConfig
import ch.protonmail.android.extidentities.domain.usecase.DeleteExternalIdentity
import ch.protonmail.android.extidentities.domain.usecase.DeleteSmtpServerConfig
import ch.protonmail.android.extidentities.domain.usecase.ObserveExternalIdentities
import ch.protonmail.android.extidentities.domain.usecase.ObserveSmtpServerConfigs
import ch.protonmail.android.extidentities.domain.usecase.SaveSmtpServerConfig
import ch.protonmail.android.extidentities.domain.repository.ProtonLoginStatus
import ch.protonmail.android.extidentities.domain.repository.ProtonSessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class ProtonSessionUiState(
    val signedInUsername: String? = null,
    val addresses: List<String> = emptyList(),
    val showLoginDialog: Boolean = false,
    val needsTotp: Boolean = false,
    val isSigningIn: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class ExternalIdentitiesViewModel @Inject constructor(
    observeExternalIdentities: ObserveExternalIdentities,
    private val observeSmtpServerConfigs: ObserveSmtpServerConfigs,
    private val saveSmtpServerConfig: SaveSmtpServerConfig,
    private val deleteSmtpServerConfig: DeleteSmtpServerConfig,
    private val deleteExternalIdentity: DeleteExternalIdentity,
    private val protonSessionRepository: ProtonSessionRepository
) : ViewModel() {

    private val mutableState = MutableStateFlow<ExternalIdentitiesState>(ExternalIdentitiesState.Loading)
    val state: StateFlow<ExternalIdentitiesState> = mutableState.asStateFlow()

    private val mutableSessionState = MutableStateFlow(ProtonSessionUiState())
    val sessionState: StateFlow<ProtonSessionUiState> = mutableSessionState.asStateFlow()

    private val mutableServerConfigs = MutableStateFlow<List<SmtpServerConfigUiModel>>(emptyList())
    val serverConfigs: StateFlow<List<SmtpServerConfigUiModel>> = mutableServerConfigs.asStateFlow()

    private val deletedSignal = Channel<ExternalIdentityId>(Channel.BUFFERED)
    val identityDeleted: Channel<ExternalIdentityId> = deletedSignal

    init {
        viewModelScope.launch {
            val session = protonSessionRepository.getStoredSession()
            mutableSessionState.update { it.copy(signedInUsername = session?.username) }
        }
        combine(
            observeExternalIdentities(),
            observeSmtpServerConfigs()
        ) { identities, configs ->
            val inUse = identities
                .groupingBy { it.smtpServerConfigId }
                .eachCount()
            identities.map { identity ->
                ExternalIdentityUiModel(
                    id = identity.id,
                    email = identity.email,
                    displayName = identity.displayName,
                    smtpHost = identity.smtpServer.host,
                    isEnabled = identity.isEnabled
                )
            } to configs.map { config ->
                config.toUiModel(inUse = inUse[config.id] ?: 0)
            }
        }
            .onEach { (identities, serverConfigs) ->
                mutableServerConfigs.value = serverConfigs
                mutableState.value = ExternalIdentitiesState.Data(
                    identities = identities,
                    serverConfigs = serverConfigs
                )
            }
            .launchIn(viewModelScope)
    }

    fun delete(id: ExternalIdentityId) {
        viewModelScope.launch {
            deleteExternalIdentity(id)
            deletedSignal.trySend(id)
        }
    }

    fun saveServerConfig(config: StoredSmtpServerConfig, onDone: () -> Unit) {
        viewModelScope.launch {
            saveSmtpServerConfig(config).fold(
                ifLeft = { error -> Timber.w("ext-identities: save server config failed: $error") },
                ifRight = { onDone() }
            )
        }
    }

    fun deleteServerConfig(configId: Long, onDone: () -> Unit) {
        viewModelScope.launch {
            deleteSmtpServerConfig(configId).fold(
                ifLeft = { error -> Timber.w("ext-identities: delete server config failed: $error") },
                ifRight = { onDone() }
            )
        }
    }

    fun showLoginDialog() {
        mutableSessionState.update { it.copy(showLoginDialog = true, error = null) }
    }

    fun dismissLoginDialog() {
        mutableSessionState.update { it.copy(showLoginDialog = false, needsTotp = false, error = null) }
    }

    fun signIn(username: String, password: String, totp: String?) {
        val current = mutableSessionState.value
        if (current.isSigningIn) return
        mutableSessionState.update { it.copy(isSigningIn = true, error = null) }
        viewModelScope.launch {
            val result = if (current.needsTotp) {
                protonSessionRepository.continueLogin(totp.orEmpty())
            } else {
                protonSessionRepository.login(username, password, totp)
            }
            when (result) {
                is ProtonLoginStatus.NeedsTotp -> mutableSessionState.update {
                    it.copy(isSigningIn = false, needsTotp = true)
                }

                is ProtonLoginStatus.Success -> {
                    val addresses = protonSessionRepository.getProtonAddresses()
                    mutableSessionState.update {
                        it.copy(
                            isSigningIn = false,
                            showLoginDialog = false,
                            needsTotp = false,
                            signedInUsername = result.username,
                            addresses = addresses
                        )
                    }
                }

                is ProtonLoginStatus.Error -> mutableSessionState.update {
                    it.copy(isSigningIn = false, error = result.message)
                }
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            protonSessionRepository.signOut()
            mutableSessionState.update { ProtonSessionUiState() }
        }
    }
}

private fun StoredSmtpServerConfig.toUiModel(inUse: Int): SmtpServerConfigUiModel = SmtpServerConfigUiModel(
    id = id,
    name = name,
    host = host,
    port = port,
    securityIndex = ch.protonmail.android.extidentities.domain.SmtpSecurity.entries.indexOf(security),
    authTypeIndex = ch.protonmail.android.extidentities.domain.SmtpAuthType.entries.indexOf(authType),
    inUseCount = inUse
)