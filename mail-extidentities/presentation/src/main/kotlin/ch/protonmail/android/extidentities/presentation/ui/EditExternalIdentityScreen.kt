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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import ch.protonmail.android.design.compose.component.ProtonSettingsDetailsAppBar
import ch.protonmail.android.design.compose.theme.ProtonTheme
import ch.protonmail.android.extidentities.presentation.R
import ch.protonmail.android.extidentities.domain.SmtpAuthType
import ch.protonmail.android.extidentities.domain.SmtpSecurity
import ch.protonmail.android.extidentities.domain.StoredSmtpServerConfig
import ch.protonmail.android.extidentities.presentation.EditExternalIdentityState
import ch.protonmail.android.extidentities.presentation.EditIdentityFieldError
import ch.protonmail.android.extidentities.presentation.ExternalIdentityTestStatus
import ch.protonmail.android.extidentities.presentation.EditExternalIdentityViewModel

@Composable
fun EditExternalIdentityScreen(
    modifier: Modifier = Modifier,
    identityId: Long?,
    onBackClick: () -> Unit,
    viewModel: EditExternalIdentityViewModel = hiltViewModel()
) {
    LaunchedEffect(identityId) { viewModel.load(identityId) }

    val state = viewModel.state.collectAsState().value
    var showDeleteDialog by remember { mutableStateOf(false) }
    Scaffold(
        modifier = modifier,
        topBar = {
            ProtonSettingsDetailsAppBar(
                title = stringResource(
                    if (state.isNew) R.string.ext_identities_add_title else R.string.ext_identities_edit_title
                ),
                onBackClick = onBackClick
            )
        },
        containerColor = ProtonTheme.colors.backgroundInvertedNorm,
        content = { paddingValues ->
            EditExternalIdentityContent(
                modifier = Modifier
                    .padding(paddingValues)
                    .fillMaxSize(),
                state = state,
                serverConfigs = viewModel.serverConfigs.collectAsState().value,
                onUpdate = viewModel::update,
                onServerConfigSelected = viewModel::selectServerConfig,
                onTest = viewModel::testConnection,
                onSave = { viewModel.save(onDone = onBackClick) },
                onDeleteRequested = { showDeleteDialog = true }
            )
        }
    )

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.ext_identities_delete_title)) },
            text = {
                Text(stringResource(R.string.ext_identities_delete_message, state.email))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        viewModel.delete(onDone = onBackClick)
                    }
                ) {
                    Text(
                        stringResource(R.string.ext_identities_delete_confirm),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.ext_identities_cancel))
                }
            }
        )
    }
}

@Composable
private fun EditExternalIdentityContent(
    modifier: Modifier = Modifier,
    state: EditExternalIdentityState,
    serverConfigs: List<StoredSmtpServerConfig>,
    onUpdate: ((EditExternalIdentityState) -> EditExternalIdentityState) -> Unit,
    onServerConfigSelected: (Long) -> Unit,
    onTest: () -> Unit,
    onSave: () -> Unit,
    onDeleteRequested: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    var showPassword by remember { mutableStateOf(false) }
    val busy = state.isSaving || state.testStatus == ExternalIdentityTestStatus.Testing

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.ext_identities_section_identity),
            style = MaterialTheme.typography.titleMedium,
            color = ProtonTheme.colors.textNorm
        )

        OutlinedTextField(
            value = state.email,
            onValueChange = { value -> onUpdate { it.copy(email = value) } },
            label = { Text(stringResource(R.string.ext_identities_email_label)) },
            isError = EditIdentityFieldError.Email in state.fieldErrors,
            supportingText = {
                if (EditIdentityFieldError.Email in state.fieldErrors) {
                    Text(stringResource(R.string.ext_identities_error_email))
                }
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next
            ),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        val fromPreview = buildString {
            append(state.displayName.trim().ifBlank { state.email.trim() })
            if (state.displayName.isNotBlank() && state.email.contains('@')) {
                append(" <").append(state.email.trim()).append(">")
            }
        }
        if (fromPreview.isNotBlank()) {
            Text(
                text = stringResource(R.string.ext_identities_from_preview, fromPreview),
                style = MaterialTheme.typography.bodySmall,
                color = ProtonTheme.colors.textWeak
            )
        }

        OutlinedTextField(
            value = state.displayName,
            onValueChange = { value -> onUpdate { it.copy(displayName = value) } },
            label = { Text(stringResource(R.string.ext_identities_display_name_label)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = state.replyTo,
            onValueChange = { value -> onUpdate { it.copy(replyTo = value) } },
            label = { Text(stringResource(R.string.ext_identities_reply_to_label)) },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next
            ),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.ext_identities_enabled_label),
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(modifier = Modifier.weight(1f))
            Switch(
                checked = state.isEnabled,
                onCheckedChange = { checked -> onUpdate { it.copy(isEnabled = checked) } }
            )
        }
        Text(
            text = stringResource(R.string.ext_identities_enabled_hint),
            style = MaterialTheme.typography.bodySmall,
            color = ProtonTheme.colors.textWeak
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        Text(
            text = stringResource(R.string.ext_identities_section_smtp),
            style = MaterialTheme.typography.titleMedium,
            color = ProtonTheme.colors.textNorm
        )

        val selectedConfig = serverConfigs.firstOrNull { it.id == state.smtpServerConfigId }

        DropdownField(
            label = stringResource(R.string.ext_identities_server_config_label),
            options = serverConfigs.map { configLabel(it) },
            selectedIndex = serverConfigs.indexOfFirst { it.id == state.smtpServerConfigId },
            onSelect = { index -> serverConfigs.getOrNull(index)?.let { onServerConfigSelected(it.id) } }
        )

        if (EditIdentityFieldError.Host in state.fieldErrors) {
            Text(
                text = stringResource(R.string.ext_identities_error_server_config),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        if (serverConfigs.isEmpty()) {
            Text(
                text = stringResource(R.string.ext_identities_server_configs_empty),
                style = MaterialTheme.typography.bodySmall,
                color = ProtonTheme.colors.textWeak
            )
        }

        selectedConfig?.let { config ->
            Text(
                text = stringResource(
                    R.string.ext_identities_server_config_summary,
                    config.host,
                    config.port,
                    securityLabel(config.security),
                    authLabel(config.authType)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = ProtonTheme.colors.textWeak
            )
        }

        if (selectedConfig?.authType != SmtpAuthType.None) {
            OutlinedTextField(
                value = state.smtpUsername,
                onValueChange = { value -> onUpdate { it.copy(smtpUsername = value) } },
                label = { Text(stringResource(R.string.ext_identities_username_label)) },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next
                ),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = state.smtpPassword,
                onValueChange = { value -> onUpdate { it.copy(smtpPassword = value) } },
                label = {
                    Text(
                        stringResource(
                            if (state.isNew || !state.hasStoredPassword) {
                                R.string.ext_identities_password_label
                            } else {
                                R.string.ext_identities_password_keep_label
                            }
                        )
                    )
                },
                isError = EditIdentityFieldError.Password in state.fieldErrors,
                supportingText = {
                    if (EditIdentityFieldError.Password in state.fieldErrors) {
                        Text(stringResource(R.string.ext_identities_error_password))
                    }
                },
                visualTransformation = if (showPassword) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                singleLine = true,
                trailingIcon = {
                    TextButton(onClick = { showPassword = !showPassword }) {
                        Text(
                            stringResource(
                                if (showPassword) {
                                    R.string.ext_identities_hide_password
                                } else {
                                    R.string.ext_identities_show_password
                                }
                            )
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            Text(
                text = stringResource(R.string.ext_identities_no_auth_hint),
                style = MaterialTheme.typography.bodySmall,
                color = ProtonTheme.colors.textWeak
            )
        }

        TestStatusLine(state)

        if (state.saveFailed) {
            Text(
                text = stringResource(R.string.ext_identities_save_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        OutlinedButton(
            onClick = {
                focusManager.clearFocus()
                onTest()
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.ext_identities_test_button))
        }

        Button(
            onClick = {
                focusManager.clearFocus()
                onSave()
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (state.isSaving) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.size(8.dp))
                Text(stringResource(R.string.ext_identities_saving))
            } else {
                Text(
                    stringResource(
                        if (state.isNew) R.string.ext_identities_create_button else R.string.ext_identities_save_button
                    )
                )
            }
        }

        if (!state.isNew) {
            TextButton(
                onClick = onDeleteRequested,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.ext_identities_delete_button),
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        Spacer(modifier = Modifier.padding(bottom = 24.dp))
    }
}

@Composable
private fun TestStatusLine(state: EditExternalIdentityState) {
    when (state.testStatus) {
        ExternalIdentityTestStatus.Idle -> Unit
        ExternalIdentityTestStatus.Testing -> Text(
            text = stringResource(R.string.ext_identities_test_testing),
            style = MaterialTheme.typography.bodyMedium,
            color = ProtonTheme.colors.textWeak
        )

        ExternalIdentityTestStatus.Success -> Text(
            text = stringResource(R.string.ext_identities_test_ok),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )

        ExternalIdentityTestStatus.Failed -> Text(
            text = stringResource(R.string.ext_identities_test_failed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun configLabel(config: StoredSmtpServerConfig): String =
    config.name + " (" + config.host + ":" + config.port + ")"

@Composable
private fun securityLabel(security: SmtpSecurity): String = stringResource(
    when (security) {
        SmtpSecurity.SslTls -> R.string.ext_identities_security_ssl_tls
        SmtpSecurity.StartTls -> R.string.ext_identities_security_starttls
        SmtpSecurity.None -> R.string.ext_identities_security_none
    }
)

@Composable
private fun authLabel(authType: SmtpAuthType): String = stringResource(
    when (authType) {
        SmtpAuthType.Auto -> R.string.ext_identities_auth_auto
        SmtpAuthType.Login -> R.string.ext_identities_auth_login
        SmtpAuthType.Plain -> R.string.ext_identities_auth_plain
        SmtpAuthType.CramMd5 -> R.string.ext_identities_auth_cram_md5
        SmtpAuthType.None -> R.string.ext_identities_auth_none
    }
)

@Preview(uiMode = Configuration.UI_MODE_NIGHT_NO, showBackground = true)
@Composable
private fun EditExternalIdentityScreenPreview() {
    ProtonTheme {
        EditExternalIdentityScreen(identityId = null, onBackClick = {})
    }
}