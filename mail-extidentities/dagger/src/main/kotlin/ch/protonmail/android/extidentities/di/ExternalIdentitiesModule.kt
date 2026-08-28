/*
 * Copyright (c) 2022 Proton Technologies AG
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

package ch.protonmail.android.extidentities.di

import ch.protonmail.android.extidentities.data.local.ExternalIdentitiesLocalDataSource
import ch.protonmail.android.extidentities.data.local.ExternalIdentitiesLocalDataSourceImpl
import ch.protonmail.android.extidentities.data.protonauth.ProtonSessionManager
import ch.protonmail.android.extidentities.data.repository.ExternalIdentityRepositoryImpl
import ch.protonmail.android.extidentities.data.smtp.ExternalSmtpMailSenderImpl
import ch.protonmail.android.extidentities.data.smtp.SmtpConnectionTesterImpl
import ch.protonmail.android.extidentities.domain.repository.ExternalIdentityRepository
import ch.protonmail.android.extidentities.domain.repository.ExternalSmtpMailSender
import ch.protonmail.android.extidentities.domain.repository.ProtonSessionRepository
import ch.protonmail.android.extidentities.domain.repository.SmtpConnectionTester
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module(includes = [ExternalIdentitiesModule.BindsModule::class])
@InstallIn(SingletonComponent::class)
object ExternalIdentitiesModule {

    @Module
    @InstallIn(SingletonComponent::class)
    internal interface BindsModule {

        @Binds
        fun bindExternalIdentityRepository(impl: ExternalIdentityRepositoryImpl): ExternalIdentityRepository

        @Binds
        fun bindExternalIdentitiesLocalDataSource(impl: ExternalIdentitiesLocalDataSourceImpl): ExternalIdentitiesLocalDataSource

        @Binds
        fun bindSmtpConnectionTester(impl: SmtpConnectionTesterImpl): SmtpConnectionTester

        @Binds
        fun bindExternalSmtpMailSender(impl: ExternalSmtpMailSenderImpl): ExternalSmtpMailSender

        @Binds
        fun bindProtonSessionRepository(impl: ProtonSessionManager): ProtonSessionRepository
    }
}
