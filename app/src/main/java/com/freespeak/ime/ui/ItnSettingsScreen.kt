package com.freespeak.ime.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.freespeak.ime.R
import com.freespeak.ime.data.UserSettings
import com.freespeak.ime.itn.ItnPackCatalog
import java.util.Locale

@Composable
internal fun ItnSettingsScreen(settings: UserSettings, viewModel: MainViewModel, onBack: () -> Unit) {
    val state by viewModel.itnPacks.collectAsStateWithLifecycle()
    var url by rememberSaveable(settings.itnCatalogUrl) { mutableStateOf(settings.itnCatalogUrl) }
    SubPageScaffold(stringResource(R.string.itn_title), onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            SwitchRow(stringResource(R.string.itn_enable), settings.itnEnabled, onChange = viewModel::setItnEnabled)
            Text(stringResource(R.string.itn_description), Modifier.padding(20.dp), style = MaterialTheme.typography.bodySmall)
            SectionLabel(stringResource(R.string.itn_language))
            ChoiceRow(stringResource(R.string.itn_auto), settings.itnLanguage == "auto", onClick = { viewModel.setItnLanguage("auto") })
            ItnPackCatalog.languages.forEach { language ->
                val locale = Locale.forLanguageTag(language)
                ChoiceRow(locale.getDisplayLanguage(locale), settings.itnLanguage == language,
                    onClick = { viewModel.setItnLanguage(language) })
            }
            SectionLabel(stringResource(R.string.itn_packs))
            OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                label = { Text(stringResource(R.string.itn_catalog)) }, singleLine = true, enabled = !state.busy)
            TextButton(onClick = { viewModel.refreshItnCatalog(url) }, enabled = !state.busy && url.isNotBlank()) {
                Text(stringResource(R.string.itn_refresh))
            }
            if (state.packs.isEmpty()) Text(stringResource(R.string.itn_no_catalog), Modifier.padding(20.dp))
            if (state.busy) {
                state.progress?.let { progress -> LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(20.dp)) }
                    ?: LinearProgressIndicator(Modifier.fillMaxWidth().padding(20.dp))
                TextButton(onClick = viewModel::cancelItnDownload) { Text(stringResource(R.string.itn_cancel)) }
            }
            state.error?.let { Text(it, Modifier.padding(20.dp), color = MaterialTheme.colorScheme.error) }
            (state.packs.map { it.language } + state.installed).distinct().forEach { language ->
                val pack = state.packs.find { it.language == language }
                val installed = language in state.installed
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text(Locale.forLanguageTag(language).getDisplayLanguage(Locale.forLanguageTag(language)), style = MaterialTheme.typography.titleMedium)
                    Text(if (installed) stringResource(R.string.itn_installed) else stringResource(R.string.itn_pack_size, (pack?.diskBytes ?: 0L) / 1_048_576.0),
                        style = MaterialTheme.typography.bodySmall)
                    if (installed) TextButton(onClick = { viewModel.deleteItnPack(language) }, enabled = !state.busy) { Text(stringResource(R.string.itn_delete)) }
                    else if (pack != null) TextButton(onClick = { viewModel.downloadItnPack(pack) }, enabled = !state.busy) { Text(stringResource(R.string.itn_download)) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
