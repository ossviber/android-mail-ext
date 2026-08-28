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

import ch.protonmail.android.extidentities.domain.ExternalIdentityId
import ch.protonmail.android.extidentities.domain.SmtpAuthType
import kotlinx.coroutines.flow.Flow

sealed interface ExternalIdentitiesState {
    data object Loading : ExternalIdentitiesState
    data class Data(
        val identities: List<ExternalIdentityUiModel>,
        val serverConfigs: List<SmtpServerConfigUiModel>
    ) : ExternalIdentitiesState
}

data class SmtpServerConfigUiModel(
    val id: Long,
    val name: String,
    val host: String,
    val port: Int,
    val securityIndex: Int,
    val authTypeIndex: Int,
    val inUseCount: Int = 0
)

data class ExternalIdentityUiModel(
    val id: ExternalIdentityId,
    val email: String,
    val displayName: String?,
    val smtpHost: String,
    val isEnabled: Boolean
)

enum class ExternalIdentityTestStatus {
    Idle, Testing, Success, Failed
}

/** Fields of the edit form that can fail validation; mapped to inline errors in the UI. */
enum class EditIdentityFieldError {
    Email,
    Host,
    Port,
    Password
}

data class EditExternalIdentityState(
    val isNew: Boolean = true,
    val email: String = "",
    val displayName: String = "",
    val replyTo: String = "",
    val signatureHtml: String = "",
    val isEnabled: Boolean = true,
    val smtpServerConfigId: Long = 0L,
    // Auth type of the selected stored config (-1 = none selected).
    val smtpAuthTypeIndex: Int = -1,
    val smtpUsername: String = "",
    val smtpPassword: String = "",
    val hasStoredPassword: Boolean = false,
    val sortOrder: Int = 0,
    val sentLabelId: String? = null,
    val sentLabelName: String? = null,
    val sentFilterId: String? = null,
    val testStatus: ExternalIdentityTestStatus = ExternalIdentityTestStatus.Idle,
    val fieldErrors: Set<EditIdentityFieldError> = emptySet(),
    val isSaving: Boolean = false,
    val saveFailed: Boolean = false,
    val saved: Boolean = false,
    val isSettingUpAutomation: Boolean = false,
    val automationFailed: Boolean = false,
    // true when the automation could not run because no Proton session exists.
    val needsProtonSession: Boolean = false
) {

    // Auth requirement comes from the selected stored server configuration.
    val isAuthRequired: Boolean
        get() = SmtpAuthType.entries.getOrNull(smtpAuthTypeIndex) != SmtpAuthType.None &&
            smtpAuthTypeIndex >= 0
}