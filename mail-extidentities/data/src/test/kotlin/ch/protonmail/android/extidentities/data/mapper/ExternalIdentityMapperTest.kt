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
import ch.protonmail.android.extidentities.domain.ExternalIdentityId
import ch.protonmail.android.extidentities.domain.SmtpAuthType
import ch.protonmail.android.extidentities.domain.SmtpSecurity
import kotlin.test.Test
import kotlin.test.assertEquals

class ExternalIdentityMapperTest {

    @Test
    fun `maps entity to domain and back preserving all fields`() {
        val config = SmtpServerConfigEntity(
            id = 7L,
            name = "External",
            host = "smtp.external.example",
            port = 465,
            security = SmtpSecurityDto.SslTls,
            authType = SmtpAuthTypeDto.Login
        )
        val entity = ExternalIdentityEntity(
            id = 42L,
            email = "me@external.example",
            displayName = "Me External",
            replyTo = "reply@external.example",
            signatureHtml = "<p>Sig</p>",
            isEnabled = false,
            sortOrder = 3,
            smtpServerConfigId = 7L,
            smtpUsername = "user@external.example"
        )

        val domain = entity.toExternalIdentity(hasPassword = true, serverConfigs = listOf(config))
        assertEquals(ExternalIdentityId(42L), domain.id)
        assertEquals("me@external.example", domain.email)
        assertEquals(7L, domain.smtpServerConfigId)
        assertEquals(SmtpSecurity.SslTls, domain.smtpServer.security)
        assertEquals(SmtpAuthType.Login, domain.smtpServer.authType)
        assertEquals(465, domain.smtpServer.port)
        assertEquals("user@external.example", domain.smtpServer.username)

        val roundTripped = domain.toEntity()
        assertEquals(7L, roundTripped.smtpServerConfigId)
        assertEquals("user@external.example", roundTripped.smtpUsername)
    }

    @Test
    fun `legacy entity without config id falls back to inline settings`() {
        val entity = ExternalIdentityEntity(
            id = 42L,
            email = "me@external.example",
            smtpServerConfigId = 0L,
            smtpHost = "smtp.external.example",
            smtpPort = 587,
            smtpSecurity = SmtpSecurityDto.StartTls,
            smtpAuthType = SmtpAuthTypeDto.Auto,
            smtpUsername = "user@external.example"
        )

        val domain = entity.toExternalIdentity(hasPassword = true)
        assertEquals(SmtpSecurity.StartTls, domain.smtpServer.security)
        assertEquals(SmtpAuthType.Auto, domain.smtpServer.authType)
        assertEquals(587, domain.smtpServer.port)
        assertEquals("user@external.example", domain.smtpServer.username)
    }

    @Test
    fun `server config entity maps to domain and back`() {
        val config = SmtpServerConfigEntity(
            id = 7L,
            name = "External",
            host = "smtp.external.example",
            port = 465,
            security = SmtpSecurityDto.SslTls,
            authType = SmtpAuthTypeDto.Login
        )

        val domain = config.toDomain()
        assertEquals(7L, domain.id)
        assertEquals("External", domain.name)
        assertEquals(SmtpSecurity.SslTls, domain.security)
        assertEquals(SmtpAuthType.Login, domain.authType)

        assertEquals(config, domain.toEntity())
    }
}