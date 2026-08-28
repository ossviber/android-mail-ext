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

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Process-wide singleton DataStore for external identities preferences.
 * Defined as a top-level extension property so it is instantiated exactly once per process.
 */
private val Context.externalIdentitiesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "externalIdentitiesPrefDataStore"
)

class ExternalIdentitiesDataStoreProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {

    val dataStore: DataStore<Preferences> = context.externalIdentitiesDataStore
}