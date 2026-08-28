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
import ch.protonmail.android.extidentities.domain.ExternalIdentity
import ch.protonmail.android.extidentities.domain.ExternalIdentityId
import ch.protonmail.android.extidentities.domain.SmtpAuthType
import ch.protonmail.android.extidentities.domain.SmtpSecurity
import ch.protonmail.android.extidentities.domain.SmtpServerConfig
import ch.protonmail.android.extidentities.domain.SmtpTestResult
import ch.protonmail.android.extidentities.domain.StoredSmtpServerConfig
import ch.protonmail.android.extidentities.domain.usecase.DeleteExternalIdentity
import android.content.Context
import ch.protonmail.android.extidentities.domain.usecase.GetExternalIdentity
import ch.protonmail.android.extidentities.domain.usecase.ObserveSmtpServerConfigs
import ch.protonmail.android.extidentities.domain.usecase.SaveExternalIdentity
import ch.protonmail.android.extidentities.domain.usecase.SetupExternalSentAutomation
import ch.protonmail.android.extidentities.domain.usecase.TestSmtpConnection
import ch.protonmail.android.extidentities.domain.buildSentCopySieveRule
import ch.protonmail.android.extidentities.domain.sentLabelNameFor
import ch.protonmail.android.extidentities.presentation.R
import ch.protonmail.android.extidentities.domain.repository.ProtonSessionRepository
import ch.protonmail.android.mailsession.domain.usecase.ObservePrimaryUserId
import ch.protonmail.android.mailsession.domain.usecase.ObserveUser
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class EditExternalIdentityViewModel @Inject constructor(
    private val getExternalIdentity: GetExternalIdentity,
    private val saveExternalIdentity: SaveExternalIdentity,
    private val testSmtpConnection: TestSmtpConnection,
    private val deleteExternalIdentity: DeleteExternalIdentity,
    private val setupExternalSentAutomation: SetupExternalSentAutomation,
    private val observePrimaryUserId: ObservePrimaryUserId,
    private val observeUser: ObserveUser,
    @ApplicationContext private val appContext: Context,
    private val protonSessionRepository: ProtonSessionRepository,
    private val observeSmtpServerConfigs: ObserveSmtpServerConfigs
) : ViewModel() {

    private val mutableState = MutableStateFlow(EditExternalIdentityState())
    val state: StateFlow<EditExternalIdentityState> = mutableState.asStateFlow()

    private var editingIdentityId: Long = NEW_ID
    private var storedSentLabelId: String? = null
    private var storedSentLabelName: String? = null
    private var storedSentFilterId: String? = null
    private var loadedIdentityEmail: String = ""
    private var loadedSentCopyAddress: String = ""
    private var loadedIdentityEnabled: Boolean = true

    private val mutableServerConfigs = MutableStateFlow<List<StoredSmtpServerConfig>>(emptyList())
    val serverConfigs: StateFlow<List<StoredSmtpServerConfig>> = mutableServerConfigs.asStateFlow()

    init {
        viewModelScope.launch {
            observeSmtpServerConfigs()
                .collect { mutableServerConfigs.value = it }
        }
    }

    fun load(identityId: Long?) {
        if (identityId == null || identityId <= 0L) {
            editingIdentityId = NEW_ID
            mutableState.update { it.copy(isNew = true, hasStoredPassword = false) }
            return
        }
        viewModelScope.launch {
            val identity = getExternalIdentity(ExternalIdentityId(identityId)) ?: return@launch
            editingIdentityId = identityId
            storedSentLabelId = identity.sentLabelId
            storedSentLabelName = identity.sentLabelName
            storedSentFilterId = identity.sentFilterId
            loadedIdentityEmail = identity.email
            loadedIdentityEnabled = identity.isEnabled
            mutableState.update {
                it.copy(
                    isNew = false,
                    email = identity.email,
                    displayName = identity.displayName.orEmpty(),
                    replyTo = identity.replyTo.orEmpty(),
                    signatureHtml = identity.signatureHtml.orEmpty(),
                    isEnabled = identity.isEnabled,
                    sortOrder = identity.sortOrder,
                    smtpServerConfigId = identity.smtpServerConfigId ?: 0L,
                    smtpAuthTypeIndex = SmtpAuthType.entries.indexOf(identity.smtpServer.authType),
                    smtpUsername = identity.smtpServer.username.orEmpty(),
                    sentLabelId = identity.sentLabelId,
                    sentFilterId = identity.sentFilterId,
                    hasStoredPassword = true
                )
            }
        }
    }

    fun update(transform: (EditExternalIdentityState) -> EditExternalIdentityState) {
        mutableState.update(transform)
    }

    /** Selects the stored server configuration used by this identity. */
    fun selectServerConfig(configId: Long) {
        val authTypeIndex = mutableServerConfigs.value
            .firstOrNull { it.id == configId }
            ?.let { SmtpAuthType.entries.indexOf(it.authType) }
            ?: -1
        mutableState.update { it.copy(smtpServerConfigId = configId, smtpAuthTypeIndex = authTypeIndex) }
    }

    fun testConnection() {
        val current = mutableState.value
        if (current.testStatus == ExternalIdentityTestStatus.Testing || current.isSaving) return
        val config = current.toServerConfig(mutableServerConfigs.value)
        if (config == null) {
            mutableState.update { it.copy(fieldErrors = fieldsNeededForConnectionTest(current)) }
            return
        }
        mutableState.update { it.copy(testStatus = ExternalIdentityTestStatus.Testing) }
        viewModelScope.launch {
            val password = current.smtpPassword.takeIf { it.isNotBlank() }?.toCharArray()
            val result = if (editingIdentityId > 0L && password == null && current.hasStoredPassword) {
                testSmtpConnection.testForIdentity(
                    identityId = editingIdentityId,
                    hasStoredPassword = true,
                    config = config,
                    username = current.smtpUsername.ifBlank { null },
                    password = null
                )
            } else {
                testSmtpConnection(config, current.smtpUsername.ifBlank { null }, password)
            }
            mutableState.update {
                it.copy(
                    testStatus = when (result) {
                        is SmtpTestResult.Success -> ExternalIdentityTestStatus.Success
                        is SmtpTestResult.Failure -> ExternalIdentityTestStatus.Failed
                    }
                )
            }
        }
    }

    fun save(onDone: () -> Unit) {
        val current = mutableState.value
        if (current.isSaving) return

        val errors = validate(current)
        if (errors.isNotEmpty()) {
            mutableState.update { it.copy(fieldErrors = errors, saveFailed = false) }
            return
        }

        mutableState.update { it.copy(fieldErrors = emptySet(), saveFailed = false, isSaving = true) }
        val config = requireNotNull(current.toServerConfig(mutableServerConfigs.value)) {
            "server configuration required"
        }
        val identity = ExternalIdentity(
            id = ExternalIdentityId(editingIdentityId),
            email = current.email.trim(),
            displayName = current.displayName.trim().ifBlank { null },
            replyTo = current.replyTo.trim().ifBlank { null },
            signatureHtml = current.signatureHtml.trim().ifBlank { null },
            isEnabled = current.isEnabled,
            sortOrder = current.sortOrder,
            sentLabelId = storedSentLabelId,
            sentLabelName = storedSentLabelName,
            smtpServerConfigId = current.smtpServerConfigId.takeIf { it > 0L },
            smtpServer = config
        )
        viewModelScope.launch {
            saveExternalIdentity(identity, current.smtpPassword.takeIf { it.isNotBlank() }?.toCharArray())
                .fold(
                    ifLeft = { error ->
                        Timber.w("ext-identities: save failed: $error")
                        mutableState.update { it.copy(isSaving = false, saveFailed = true) }
                    },
                    ifRight = {
                        mutableState.update { it.copy(isSaving = false, saved = true) }
                        onDone()
                    }
                )
        }
    }

    fun delete(onDone: () -> Unit) {
        if (editingIdentityId <= 0L) return
        viewModelScope.launch {
            deleteExternalIdentity(ExternalIdentityId(editingIdentityId))
            onDone()
        }
    }

    /**
     * Turns the sent e-mail automation on: creates (or reuses) the per-identity
     * Proton label, plus the server-side filter that files incoming hidden
     * copies into that label. Requires a Proton session.
     */
    fun enableSentAutomation() {
        val current = mutableState.value
        if (current.isSettingUpAutomation) return
        if (editingIdentityId <= 0L) return
        mutableState.update { it.copy(isSettingUpAutomation = true, automationFailed = false, needsProtonSession = false) }
        viewModelScope.launch {
            val session = protonSessionRepository.getStoredSession()
            if (session == null) {
                Timber.w("ext-identities: no Proton session - cannot create label")
                mutableState.update { it.copy(isSettingUpAutomation = false, needsProtonSession = true) }
                return@launch
            }
            val labelBase = appContext.getString(R.string.ext_identities_automation_folder_base)
            setupExternalSentAutomation(ExternalIdentityId(editingIdentityId), labelBase).fold(
                ifLeft = { error ->
                    Timber.w("ext-identities: label setup failed: $error")
                    mutableState.update { it.copy(isSettingUpAutomation = false, automationFailed = true) }
                },
                ifRight = { updated ->
                    storedSentLabelId = updated.sentLabelId
                    storedSentLabelName = updated.sentLabelName
                    // Keep a server-side filter filing incoming copies into the label.
                    val filterId = storedSentFilterId
                        ?: protonSessionRepository.findFilterIdByName(
                            updated.sentLabelName ?: sentLabelNameFor(labelBase, updated.email)
                        )
                    val activeFilterId = if (filterId != null) {
                        protonSessionRepository.setFilterEnabled(filterId, enabled = true)
                        filterId
                    } else {
                        val protectedAddress = updated.protectedProtonAddress()
                        val rule = buildSentCopySieveRule(
                            externalAddress = updated.email,
                            labelName = updated.sentLabelName ?: sentLabelNameFor(labelBase, updated.email),
                            protonAddresses = listOfNotNull(protectedAddress)
                        )
                        val created = protonSessionRepository.createFilter(
                            name = sentLabelNameFor(labelBase, updated.email),
                            sieve = rule
                        )
                        created
                    }
                    if (activeFilterId != null) {
                        setupExternalSentAutomation.setFilterId(ExternalIdentityId(editingIdentityId), activeFilterId)
                        storedSentFilterId = activeFilterId
                    }
                    mutableState.update {
                        it.copy(
                            isSettingUpAutomation = false,
                            sentLabelId = updated.sentLabelId,
                            sentLabelName = updated.sentLabelName,
                            sentFilterId = activeFilterId ?: it.sentFilterId
                        )
                    }
                }
            )
        }
    }

    /**
     * Turns the automation off: the identity stops tagging its stored sent
     * copies and the server-side filter is disabled (kept for re-enabling).
     */
    fun disableSentAutomation() {
        val current = mutableState.value
        if (current.isSettingUpAutomation) return
        if (editingIdentityId <= 0L) return
        mutableState.update { it.copy(isSettingUpAutomation = true, automationFailed = false) }
        viewModelScope.launch {
            val email = current.email.trim()
            val labelName = storedSentLabelName
                ?: sentLabelNameFor(appContext.getString(R.string.ext_identities_automation_folder_base), email)
            val filterId = storedSentFilterId
                ?: protonSessionRepository.findFilterIdByName(labelName)
            if (filterId != null) {
                val disabled = protonSessionRepository.setFilterEnabled(filterId, enabled = false)
                Timber.i("ext-identities: filter disable ok=" + disabled + " id=" + filterId)
            }
            setupExternalSentAutomation.clear(ExternalIdentityId(editingIdentityId))
            storedSentLabelId = null
            storedSentLabelName = null
            mutableState.update { it.copy(isSettingUpAutomation = false, sentLabelId = null, sentLabelName = null) }
        }
    }

    /**
     * The Proton address whose inbox must stay free of filed copies. Primary
     * user email; aliases can be added to the rule in the web filters editor.
     */
    private suspend fun ExternalIdentity.protectedProtonAddress(): String {
        val userId = observePrimaryUserId().filterNotNull().first() ?: return ""
        return observeUser(userId).first().fold(
            ifLeft = { "" },
            ifRight = { user -> user.email }
        )
    }

    private fun validate(state: EditExternalIdentityState): Set<EditIdentityFieldError> = buildSet {
        val email = state.email.trim()
        if (email.isBlank() || !email.contains('@')) add(EditIdentityFieldError.Email)
        if (state.smtpServerConfigId <= 0L) add(EditIdentityFieldError.Host)
        val passwordUsable = state.smtpPassword.isNotBlank() ||
            (!state.isNew && state.hasStoredPassword)
        if (state.isAuthRequired && !passwordUsable) add(EditIdentityFieldError.Password)
    }

    private fun fieldsNeededForConnectionTest(state: EditExternalIdentityState): Set<EditIdentityFieldError> = buildSet {
        if (state.smtpServerConfigId <= 0L) add(EditIdentityFieldError.Host)
    }

    private fun EditExternalIdentityState.toServerConfig(
        configs: List<StoredSmtpServerConfig>
    ): SmtpServerConfig? {
        val stored = configs.firstOrNull { it.id == smtpServerConfigId } ?: return null
        return SmtpServerConfig(
            host = stored.host,
            port = stored.port,
            security = stored.security,
            authType = stored.authType,
            username = smtpUsername.trim().ifBlank { null }
        )
    }

    private companion object {
        const val NEW_ID = 0L
    }
}