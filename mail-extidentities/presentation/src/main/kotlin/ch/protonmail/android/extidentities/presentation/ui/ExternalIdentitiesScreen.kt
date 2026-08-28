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

package ch.protonmail.android.extidentities.presentation.ui

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Surface
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import ch.protonmail.android.design.compose.component.ProtonCenteredProgress
import ch.protonmail.android.design.compose.component.ProtonSettingsDetailsAppBar
import ch.protonmail.android.design.compose.theme.ProtonTheme
import ch.protonmail.android.extidentities.domain.ExternalIdentityId
import ch.protonmail.android.extidentities.domain.SmtpAuthType
import ch.protonmail.android.extidentities.domain.SmtpSecurity
import ch.protonmail.android.extidentities.domain.StoredSmtpServerConfig
import ch.protonmail.android.extidentities.presentation.ExternalIdentityUiModel
import ch.protonmail.android.extidentities.presentation.SmtpServerConfigUiModel
import ch.protonmail.android.extidentities.presentation.R
import ch.protonmail.android.extidentities.presentation.ExternalIdentitiesState
import ch.protonmail.android.extidentities.presentation.ExternalIdentitiesViewModel
import ch.protonmail.android.extidentities.presentation.ProtonSessionUiState

@Composable
fun ExternalIdentitiesScreen(
    modifier: Modifier = Modifier,
    onBackClick: () -> Unit,
    onAddIdentityClick: () -> Unit,
    onEditIdentityClick: (ExternalIdentityId) -> Unit,
    viewModel: ExternalIdentitiesViewModel = hiltViewModel()
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            ProtonSettingsDetailsAppBar(
                title = stringResource(R.string.ext_identities_title),
                onBackClick = onBackClick
            )
        },
        containerColor = ProtonTheme.colors.backgroundInvertedNorm,
        content = { paddingValues ->
            val state = viewModel.state.collectAsState().value
            val sessionState = viewModel.sessionState.collectAsState().value
            when (state) {
                ExternalIdentitiesState.Loading -> ProtonCenteredProgress()
                is ExternalIdentitiesState.Data -> ExternalIdentitiesContent(
                    modifier = Modifier.padding(paddingValues),
                    identities = state.identities,
                    serverConfigs = state.serverConfigs,
                    sessionState = sessionState,
                    onAddClick = onAddIdentityClick,
                    onEditClick = onEditIdentityClick,
                    onShowLogin = viewModel::showLoginDialog,
                    onDismissLogin = viewModel::dismissLoginDialog,
                    onSignIn = viewModel::signIn,
                    onSignOut = viewModel::signOut,
                    onSaveServerConfig = viewModel::saveServerConfig,
                    onDeleteServerConfig = viewModel::deleteServerConfig
                )
            }
        }
    )
}

@Composable
private fun ExternalIdentitiesContent(
    modifier: Modifier = Modifier,
    identities: List<ExternalIdentityUiModel>,
    serverConfigs: List<SmtpServerConfigUiModel>,
    sessionState: ProtonSessionUiState,
    onAddClick: () -> Unit,
    onEditClick: (ExternalIdentityId) -> Unit,
    onShowLogin: () -> Unit,
    onDismissLogin: () -> Unit,
    onSignIn: (String, String, String?) -> Unit,
    onSignOut: () -> Unit,
    onSaveServerConfig: (StoredSmtpServerConfig, () -> Unit) -> Unit,
    onDeleteServerConfig: (Long, () -> Unit) -> Unit
) {
    Column(modifier = modifier.fillMaxSize()) {
        if (identities.isEmpty()) {
            Text(
                text = stringResource(R.string.ext_identities_empty_state),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp)
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(identities, key = { it.id.value }) { identity ->
                    IdentityRow(
                        identity = identity,
                        onClick = { onEditClick(identity.id) }
                    )
                    HorizontalDivider()
                }
            }
        }
        Button(
            onClick = onAddClick,
            enabled = serverConfigs.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(stringResource(R.string.ext_identities_add_button))
        }
        if (serverConfigs.isEmpty()) {
            Text(
                text = stringResource(R.string.ext_identities_add_identity_needs_server),
                style = MaterialTheme.typography.bodySmall,
                color = ProtonTheme.colors.textWeak,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        HorizontalDivider()

        ServerConfigsSection(
            serverConfigs = serverConfigs,
            onSave = onSaveServerConfig,
            onDelete = onDeleteServerConfig
        )

        HorizontalDivider()

        ProtonSessionCard(
            sessionState = sessionState,
            onShowLogin = onShowLogin,
            onSignOut = onSignOut
        )
    }

    if (sessionState.showLoginDialog) {
        ProtonLoginDialog(
            sessionState = sessionState,
            onSignIn = onSignIn,
            onDismiss = onDismissLogin
        )
    }
}

@Composable
private fun ProtonSessionCard(
    sessionState: ProtonSessionUiState,
    onShowLogin: () -> Unit,
    onSignOut: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text(
            text = stringResource(R.string.ext_identities_session_title),
            style = MaterialTheme.typography.titleMedium,
            color = ProtonTheme.colors.textNorm
        )
        Spacer(modifier = Modifier.padding(4.dp))
        val signedIn = sessionState.signedInUsername
        if (signedIn == null) {
            Text(
                text = stringResource(R.string.ext_identities_session_signed_out_hint),
                style = MaterialTheme.typography.bodySmall,
                color = ProtonTheme.colors.textWeak
            )
            Spacer(modifier = Modifier.padding(4.dp))
            Button(onClick = onShowLogin, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.ext_identities_session_sign_in))
            }
        } else {
            Text(
                text = stringResource(R.string.ext_identities_session_signed_in, signedIn),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            if (sessionState.addresses.isNotEmpty()) {
                Text(
                    text = stringResource(
                        R.string.ext_identities_session_addresses,
                        sessionState.addresses.size
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = ProtonTheme.colors.textWeak
                )
            }
            Spacer(modifier = Modifier.padding(4.dp))
            OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.ext_identities_session_sign_out))
            }
        }
    }
}

@Composable
private fun ProtonLoginDialog(
    sessionState: ProtonSessionUiState,
    onSignIn: (String, String, String?) -> Unit,
    onDismiss: () -> Unit
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var totp by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            color = ProtonTheme.colors.backgroundNorm
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.ext_identities_session_sign_in),
                    style = MaterialTheme.typography.titleLarge,
                    color = ProtonTheme.colors.textNorm
                )
                if (!sessionState.needsTotp) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text(stringResource(R.string.ext_identities_session_username)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false),
                        modifier = Modifier
                            .fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.ext_identities_session_password)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        modifier = Modifier
                            .fillMaxWidth()
                    )
                } else {
                    OutlinedTextField(
                        value = totp,
                        onValueChange = { totp = it },
                        label = { Text(stringResource(R.string.ext_identities_session_totp)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier
                            .fillMaxWidth()
                    )
                }
                sessionState.error?.let { error ->
                    Text(
                        text = stringResource(R.string.ext_identities_session_failed, error),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.ext_identities_cancel))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { onSignIn(username.trim(), password, totp.takeIf { it.isNotBlank() }) },
                        enabled = !sessionState.isSigningIn
                    ) {
                        if (sessionState.isSigningIn) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(stringResource(R.string.ext_identities_session_login))
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerConfigsSection(
    serverConfigs: List<SmtpServerConfigUiModel>,
    onSave: (StoredSmtpServerConfig, () -> Unit) -> Unit,
    onDelete: (Long, () -> Unit) -> Unit
) {
    var editing by remember { mutableStateOf<SmtpServerConfigUiModel?>(null) }
    var showEditor by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text(
            text = stringResource(R.string.ext_identities_server_configs_title),
            style = MaterialTheme.typography.titleMedium,
            color = ProtonTheme.colors.textNorm
        )
        Spacer(modifier = Modifier.padding(4.dp))
        if (serverConfigs.isEmpty()) {
            Text(
                text = stringResource(R.string.ext_identities_server_configs_empty),
                style = MaterialTheme.typography.bodySmall,
                color = ProtonTheme.colors.textWeak
            )
        } else {
            serverConfigs.forEach { config ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            editing = config
                            showEditor = true
                        }
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = config.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = config.host + ":" + config.port + " - " +
                                stringResource(
                                    when (SmtpSecurity.entries.getOrElse(config.securityIndex) { SmtpSecurity.StartTls }) {
                                        SmtpSecurity.SslTls -> R.string.ext_identities_security_ssl_tls
                                        SmtpSecurity.StartTls -> R.string.ext_identities_security_starttls
                                        SmtpSecurity.None -> R.string.ext_identities_security_none
                                    }
                                ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (config.inUseCount > 0) {
                            Text(
                                text = stringResource(R.string.ext_identities_server_config_in_use, config.inUseCount),
                                style = MaterialTheme.typography.bodySmall,
                                color = ProtonTheme.colors.textWeak
                            )
                        }
                    }
                }
                HorizontalDivider()
            }
        }

        Spacer(modifier = Modifier.padding(4.dp))
        OutlinedButton(
            onClick = {
                editing = null
                showEditor = true
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.ext_identities_server_config_add))
        }
    }

    if (showEditor) {
        ServerConfigEditorDialog(
            config = editing,
            onDismiss = { showEditor = false },
            onSave = { config ->
                onSave(config) { showEditor = false }
            },
            onDelete = { id ->
                onDelete(id) { showEditor = false }
            }
        )
    }
}

@Composable
private fun ServerConfigEditorDialog(
    config: SmtpServerConfigUiModel?,
    onDismiss: () -> Unit,
    onSave: (StoredSmtpServerConfig) -> Unit,
    onDelete: (Long) -> Unit
) {
    var name by remember { mutableStateOf(config?.name ?: "") }
    var host by remember { mutableStateOf(config?.host ?: "") }
    var port by remember { mutableStateOf((config?.port ?: 587).toString()) }
    var securityIndex by remember { mutableStateOf(config?.securityIndex ?: 1) }
    var authTypeIndex by remember { mutableStateOf(config?.authTypeIndex ?: 0) }
    var showDelete by remember { mutableStateOf(false) }

    /**
     * Switching security keeps a custom port, but updates it whenever the
     * current one is (or would be) the default of the previous selection.
     */
    fun selectSecurity(index: Int) {
        val previous = SmtpSecurity.entries.getOrElse(securityIndex) { SmtpSecurity.StartTls }
        val next = SmtpSecurity.entries.getOrElse(index) { SmtpSecurity.StartTls }
        val currentPort = port.toIntOrNull()
        if (currentPort == null || currentPort == defaultPortFor(previous)) {
            port = defaultPortFor(next).toString()
        }
        securityIndex = index
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text(stringResource(R.string.ext_identities_server_config_delete_title)) },
            text = { Text(stringResource(R.string.ext_identities_server_config_delete_message, config?.name.orEmpty())) },
            confirmButton = {
                TextButton(onClick = {
                    config?.let { onDelete(it.id) }
                }) {
                    Text(
                        stringResource(R.string.ext_identities_delete_confirm),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) {
                    Text(stringResource(R.string.ext_identities_cancel))
                }
            }
        )
        return
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            color = ProtonTheme.colors.backgroundNorm
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(
                        if (config == null) {
                            R.string.ext_identities_server_config_add
                        } else {
                            R.string.ext_identities_server_config_edit
                        }
                    ),
                    style = MaterialTheme.typography.titleLarge,
                    color = ProtonTheme.colors.textNorm
                )
                val nameError = name.trim().isEmpty()
                val hostError = host.trim().isEmpty()
                val portValue = port.toIntOrNull()
                val portError = portValue == null || portValue !in 1..65535
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.ext_identities_server_config_name_label)) },
                    isError = nameError,
                    supportingText = {
                        if (nameError) {
                            Text(stringResource(R.string.ext_identities_server_config_error_name))
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text(stringResource(R.string.ext_identities_host_label)) },
                    isError = hostError,
                    supportingText = {
                        if (hostError) {
                            Text(stringResource(R.string.ext_identities_error_host))
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it.filter(Char::isDigit) },
                        label = { Text(stringResource(R.string.ext_identities_port_label)) },
                        isError = portError,
                        supportingText = {
                            if (portError) {
                                Text(stringResource(R.string.ext_identities_error_port))
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(0.4f)
                    )
                    DropdownField(
                        label = stringResource(R.string.ext_identities_security_label),
                        options = SmtpSecurity.entries.map { security ->
                            stringResource(
                                when (security) {
                                    SmtpSecurity.SslTls -> R.string.ext_identities_security_ssl_tls
                                    SmtpSecurity.StartTls -> R.string.ext_identities_security_starttls
                                    SmtpSecurity.None -> R.string.ext_identities_security_none
                                }
                            )
                        },
                        selectedIndex = securityIndex,
                        onSelect = ::selectSecurity,
                        modifier = Modifier.weight(0.6f)
                    )
                }
                DropdownField(
                    label = stringResource(R.string.ext_identities_auth_label),
                    options = SmtpAuthType.entries.map { auth ->
                        stringResource(
                            when (auth) {
                                SmtpAuthType.Auto -> R.string.ext_identities_auth_auto
                                SmtpAuthType.Login -> R.string.ext_identities_auth_login
                                SmtpAuthType.Plain -> R.string.ext_identities_auth_plain
                                SmtpAuthType.CramMd5 -> R.string.ext_identities_auth_cram_md5
                                SmtpAuthType.None -> R.string.ext_identities_auth_none
                            }
                        )
                    },
                    selectedIndex = authTypeIndex,
                    onSelect = { authTypeIndex = it }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (config != null && config.inUseCount == 0) {
                        TextButton(onClick = { showDelete = true }) {
                            Text(
                                stringResource(R.string.ext_identities_delete_button),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.ext_identities_cancel))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onSave(
                                StoredSmtpServerConfig(
                                    id = config?.id ?: 0L,
                                    name = name.trim(),
                                    host = host.trim(),
                                    port = portValue ?: 0,
                                    security = SmtpSecurity.entries.getOrElse(securityIndex) { SmtpSecurity.StartTls },
                                    authType = SmtpAuthType.entries.getOrElse(authTypeIndex) { SmtpAuthType.Auto }
                                )
                            )
                        },
                        enabled = !nameError && !hostError && !portError
                    ) {
                        Text(stringResource(R.string.ext_identities_save_button))
                    }
                }
            }
        }
    }
}

private fun defaultPortFor(security: SmtpSecurity?): Int = when (security) {
    SmtpSecurity.SslTls -> 465
    SmtpSecurity.None -> 25
    else -> 587
}

@Composable
private fun IdentityRow(
    identity: ExternalIdentityUiModel,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = identity.displayName?.takeIf { it.isNotBlank() } ?: identity.email,
                style = MaterialTheme.typography.bodyLarge,
                color = if (identity.isEnabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${identity.email} - SMTP ${identity.smtpHost}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!identity.isEnabled) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.ext_identities_disabled),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_NO, showBackground = true)
@Composable
private fun ExternalIdentitiesScreenPreview() {
    ProtonTheme {
        ExternalIdentitiesScreen(onBackClick = {}, onAddIdentityClick = {}, onEditIdentityClick = {})
    }
}