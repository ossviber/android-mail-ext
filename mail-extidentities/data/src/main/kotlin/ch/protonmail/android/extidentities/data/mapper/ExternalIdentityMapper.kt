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

package ch.protonmail.android.extidentities.data.mapper

import ch.protonmail.android.extidentities.data.local.ExternalIdentityEntity
import ch.protonmail.android.extidentities.data.local.SmtpAuthTypeDto
import ch.protonmail.android.extidentities.data.local.SmtpSecurityDto
import ch.protonmail.android.extidentities.data.local.SmtpServerConfigEntity
import ch.protonmail.android.extidentities.domain.ExternalIdentity
import ch.protonmail.android.extidentities.domain.ExternalIdentityId
import ch.protonmail.android.extidentities.domain.SmtpAuthType
import ch.protonmail.android.extidentities.domain.SmtpSecurity
import ch.protonmail.android.extidentities.domain.SmtpServerConfig
import ch.protonmail.android.extidentities.domain.StoredSmtpServerConfig

fun ExternalIdentityEntity.toExternalIdentity(
    hasPassword: Boolean,
    serverConfigs: List<SmtpServerConfigEntity> = emptyList()
): ExternalIdentity {
    val config = serverConfigs.firstOrNull { it.id == smtpServerConfigId }
    val resolved = if (config != null) {
        SmtpServerConfig(
            host = config.host,
            port = config.port,
            security = config.security.toDomain(),
            authType = config.authType.toDomain(),
            username = smtpUsername
        )
    } else {
        // Legacy fallback: the entity itself carried inline settings. Build a
        // config from them so old identities keep working until edited.
        SmtpServerConfig(
            host = smtpHost ?: "",
            port = smtpPort ?: 587,
            security = smtpSecurity.toDomain(),
            authType = smtpAuthType.toDomain(),
            username = smtpUsername
        )
    }
    return ExternalIdentity(
        id = ExternalIdentityId(id),
        email = email,
        displayName = displayName,
        replyTo = replyTo,
        signatureHtml = signatureHtml,
        isEnabled = isEnabled,
        sortOrder = sortOrder,
        sentLabelId = sentLabelId,
        sentLabelName = sentLabelName,
        sentFilterId = sentFilterId,
        smtpServerConfigId = smtpServerConfigId,
        smtpServer = resolved
    )
}

private fun SmtpSecurityDto.toDomain(): SmtpSecurity = when (this) {
    SmtpSecurityDto.SslTls -> SmtpSecurity.SslTls
    SmtpSecurityDto.StartTls -> SmtpSecurity.StartTls
    SmtpSecurityDto.None -> SmtpSecurity.None
}

private fun SmtpAuthTypeDto.toDomain(): SmtpAuthType = when (this) {
    SmtpAuthTypeDto.Auto -> SmtpAuthType.Auto
    SmtpAuthTypeDto.Login -> SmtpAuthType.Login
    SmtpAuthTypeDto.Plain -> SmtpAuthType.Plain
    SmtpAuthTypeDto.CramMd5 -> SmtpAuthType.CramMd5
    SmtpAuthTypeDto.None -> SmtpAuthType.None
}

fun ExternalIdentity.toEntity(): ExternalIdentityEntity = ExternalIdentityEntity(
    id = id.value,
    email = email,
    displayName = displayName,
    replyTo = replyTo,
    signatureHtml = signatureHtml,
    isEnabled = isEnabled,
    sortOrder = sortOrder,
    sentLabelId = sentLabelId,
    sentLabelName = sentLabelName,
    sentFilterId = sentFilterId,
    smtpServerConfigId = smtpServerConfigId ?: 0L,
    smtpUsername = smtpServer.username
)

fun SmtpServerConfigEntity.toDomain(): StoredSmtpServerConfig = StoredSmtpServerConfig(
    id = id,
    name = name,
    host = host,
    port = port,
    security = security.toDomain(),
    authType = authType.toDomain()
)

fun StoredSmtpServerConfig.toEntity(): SmtpServerConfigEntity = SmtpServerConfigEntity(
    id = id,
    name = name,
    host = host,
    port = port,
    security = when (security) {
        SmtpSecurity.SslTls -> SmtpSecurityDto.SslTls
        SmtpSecurity.StartTls -> SmtpSecurityDto.StartTls
        SmtpSecurity.None -> SmtpSecurityDto.None
    },
    authType = when (authType) {
        SmtpAuthType.Auto -> SmtpAuthTypeDto.Auto
        SmtpAuthType.Login -> SmtpAuthTypeDto.Login
        SmtpAuthType.Plain -> SmtpAuthTypeDto.Plain
        SmtpAuthType.CramMd5 -> SmtpAuthTypeDto.CramMd5
        SmtpAuthType.None -> SmtpAuthTypeDto.None
    }
)