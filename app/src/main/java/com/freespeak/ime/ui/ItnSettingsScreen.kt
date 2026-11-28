package com.freespeak.ime.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.freespeak.ime.R
import com.freespeak.ime.data.UserSettings
import com.freespeak.ime.ui.theme.NothingRed

@Composable
internal fun ItnSettingsScreen(settings: UserSettings, viewModel: MainViewModel, onBack: () -> Unit) {
    val state by viewModel.itnPacks.collectAsStateWithLifecycle()
    var pendingDelete by rememberSaveable { mutableStateOf<String?>(null) }
    var languageMenuOpen by rememberSaveable { mutableStateOf(false) }
    val installed = state.packs.filter { it.language in state.installed }
    val available = state.packs.filterNot { it.language in state.installed }
        .sortedBy { it.language !in settings.preferredLanguages }
    val automatic = stringResource(R.string.itn_auto)

    SubPageScaffold(stringResource(R.string.itn_title), onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "settings") {
                RowGroup(rows = listOf(
                    { SwitchRow(stringResource(R.string.itn_enable), settings.itnEnabled, onChange = viewModel::setItnEnabled) },
                    {
                        Box {
                            ValueRow(
                                title = stringResource(R.string.itn_language),
                                value = if (settings.itnLanguage == "auto") automatic else nativeLanguageName(settings.itnLanguage),
                                onClick = { languageMenuOpen = true },
                            )
                            DropdownMenu(
                                expanded = languageMenuOpen,
                                onDismissRequest = { languageMenuOpen = false },
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                shape = RoundedCornerShape(16.dp),
                            ) {
                                (listOf("auto") + state.packs.map { it.language }).forEach { language ->
                                    DropdownMenuItem(
                                        text = { Text(if (language == "auto") automatic else nativeLanguageName(language)) },
                                        onClick = {
                                            languageMenuOpen = false
                                            viewModel.setItnLanguage(language)
                                        },
                                    )
                                }
                            }
                        }
                    },
                ))
                Text(
                    stringResource(R.string.itn_description),
                    Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            listOf(R.string.hub_installed to installed, R.string.itn_packs to available).forEach { (title, packs) ->
                if (packs.isNotEmpty()) item(key = title) { SectionLabel(stringResource(title)) }
                items(packs, key = { it.language }) { pack ->
                    val language = pack.language
                    val installedBytes = state.installed[language]
                    val downloadedBytes = state.downloadedBytes.takeIf { state.operationLanguage == language }
                    ItnPackRow(
                        title = nativeLanguageName(language),
                        bytes = installedBytes ?: pack.diskBytes,
                        installed = installedBytes != null,
                        downloadedBytes = downloadedBytes,
                        error = state.errors[language],
                        enabled = !state.busy,
                        onDownload = { viewModel.downloadItnPack(language) },
                        onCancel = viewModel::cancelItnDownload,
                        onDelete = { pendingDelete = language },
                    )
                    HairlineDivider(Modifier.padding(horizontal = 20.dp))
                }
            }
        }
    }

    pendingDelete?.let { language ->
        ConfirmDeleteDialog(
            name = nativeLanguageName(language),
            onDismiss = { pendingDelete = null },
            onConfirm = {
                pendingDelete = null
                viewModel.deleteItnPack(language)
            },
        )
    }
}

@Composable
private fun ItnPackRow(
    title: String,
    bytes: Long,
    installed: Boolean,
    downloadedBytes: Long?,
    error: String?,
    enabled: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    DownloadListRow(title = title, readout = formatBytes(bytes), actions = {
        when {
            downloadedBytes != null -> IconAction(Icons.Outlined.Close, stringResource(R.string.action_cancel), onCancel)
            installed -> IconAction(Icons.Outlined.DeleteOutline, stringResource(R.string.action_delete), onDelete,
                tint = MaterialTheme.colorScheme.outline, enabled = enabled, iconSize = 20.dp)
            error != null -> IconAction(Icons.Outlined.Refresh, stringResource(R.string.action_retry), onDownload,
                tint = NothingRed, enabled = enabled)
            else -> IconAction(Icons.Outlined.Download, stringResource(R.string.action_download), onDownload, enabled = enabled)
        }
    }) {
        val inset = Modifier.padding(end = 12.dp)
        if (downloadedBytes != null) {
            Spacer(Modifier.height(10.dp))
            ThinProgress((downloadedBytes.toFloat() / bytes).coerceIn(0f, 1f), inset)
            Spacer(Modifier.height(6.dp))
            Readout("${formatBytes(downloadedBytes)} / ${formatBytes(bytes)}", inset)
        } else if (error != null) {
            Spacer(Modifier.height(4.dp))
            Readout(error, inset, color = NothingRed)
        }
    }
}
