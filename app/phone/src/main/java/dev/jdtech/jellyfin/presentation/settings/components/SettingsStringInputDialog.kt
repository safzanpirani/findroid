package dev.jdtech.jellyfin.presentation.settings.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import dev.jdtech.jellyfin.presentation.components.BaseDialog
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.settings.R as SettingsR
import dev.jdtech.jellyfin.settings.domain.models.Preference as PreferenceBackend
import dev.jdtech.jellyfin.settings.presentation.models.PreferenceStringInput

@Composable
fun SettingsStringInputDialog(
    preference: PreferenceStringInput,
    onUpdate: (value: String?) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val initialValue = preference.value ?: ""
    
    var textFieldValue by remember {
        mutableStateOf(
            TextFieldValue(text = initialValue, selection = TextRange(initialValue.length))
        )
    }

    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(true) { focusRequester.requestFocus() }

    BaseDialog(
        title = stringResource(preference.nameStringResource),
        onDismiss = onDismissRequest,
        negativeButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(SettingsR.string.cancel))
            }
        },
        positiveButton = {
            TextButton(
                onClick = { 
                    onUpdate(textFieldValue.text.ifBlank { null })
                }
            ) {
                Text(text = stringResource(SettingsR.string.save))
            }
        },
    ) { contentPadding ->
        Column(modifier = Modifier.padding(contentPadding)) {
            preference.descriptionStringRes?.let {
                Text(
                    text = stringResource(it),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(MaterialTheme.spacings.medium))
            }
            OutlinedTextField(
                value = textFieldValue,
                onValueChange = { textFieldValue = it },
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = { onUpdate(textFieldValue.text.ifBlank { null }) }
                ),
                singleLine = true,
                placeholder = { Text(text = stringResource(SettingsR.string.sync_target_user_summary)) }
            )
        }
    }
}

@Preview
@Composable
private fun SettingsStringInputDialogPreview() {
    FindroidTheme {
        SettingsStringInputDialog(
            preference = PreferenceStringInput(
                nameStringResource = SettingsR.string.sync_target_user,
                descriptionStringRes = SettingsR.string.sync_target_user_summary,
                backendPreference = PreferenceBackend("", null),
                value = "Rakhi",
            ),
            onUpdate = {},
            onDismissRequest = {},
        )
    }
}
