package com.freespeak.ime.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.freespeak.ime.R
import com.freespeak.ime.data.UserSettings
import com.freespeak.ime.model.ManagedModelItem
import com.freespeak.ime.model.ModelInstallState
import com.freespeak.ime.model.ModelManifest
import com.freespeak.ime.ui.theme.FreeSpeakType
import com.freespeak.ime.ui.theme.NothingRed

enum class HubTab { MINE, DISCOVER }

internal class ModelActions(
    val onDownload: (ModelManifest) -> Unit,
    val onCancel: (String) -> Unit,
    val onSelect: (String) -> Unit,
    val onDelete: (ModelManifest) -> Unit,
)

// ---------------------------------------------------------------- hub tab

@Composable
internal fun ModelHubScreen(
    models: List<ManagedModelItem>,
    settings: UserSettings,
    contentPadding: PaddingValues,
    actions: ModelActions,
    onImportLocal: () -> Unit,
    onAddDirectUrl: () -> Unit,
    onOpenCatalogs: () -> Unit,
    onOpenMirror: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(HubTab.MINE) }
    var menuOpen by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ModelManifest?>(null) }
    val activeId = resolveActiveModelId(models, settings)
    val mine = models.filter { it.state !is ModelInstallState.NotInstalled }

    // Land on Discover when nothing is installed yet.
    LaunchedEffect(Unit) { if (mine.isEmpty()) tab = HubTab.DISCOVER }

    val deleteAction: (ModelManifest) -> Unit = { pendingDelete = it }

    Column(
        Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .statusBarsPadding()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 4.dp, top = 28.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HubTabLabel(stringResource(R.string.hub_mine), tab == HubTab.MINE) { tab = HubTab.MINE }
            Spacer(Modifier.width(20.dp))
            HubTabLabel(stringResource(R.string.hub_discover), tab == HubTab.DISCOVER) { tab = HubTab.DISCOVER }
            Spacer(Modifier.weight(1f))
            IconAction(Icons.Outlined.UploadFile, stringResource(R.string.action_import_local_file), onImportLocal)
            Box {
                IconAction(Icons.Outlined.MoreVert, stringResource(R.string.action_more), { menuOpen = true })
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(16.dp),
                ) {
                    MenuItem(Icons.Outlined.Link, stringResource(R.string.action_add_direct_url)) { menuOpen = false; onAddDirectUrl() }
                    MenuItem(Icons.Outlined.RssFeed, stringResource(R.string.catalog_section_title)) { menuOpen = false; onOpenCatalogs() }
                    MenuItem(Icons.Outlined.Public, stringResource(R.string.pref_download_mirror)) { menuOpen = false; onOpenMirror() }
                }
            }
        }

        when (tab) {
            HubTab.MINE -> MineList(mine, activeId, actions, deleteAction) { tab = HubTab.DISCOVER }
            HubTab.DISCOVER -> DiscoverList(
                models = models,
                activeId = activeId,
                actions = actions,
            )
        }
    }

    pendingDelete?.let { m ->
        ConfirmDeleteDialog(
            name = m.name,
            onDismiss = { pendingDelete = null },
            onConfirm = {
                actions.onDelete(m)
                pendingDelete = null
            },
        )
    }
}

internal fun resolveActiveModelId(models: List<ManagedModelItem>, settings: UserSettings): String? {
    val installed = models.filter { it.state is ModelInstallState.Installed }
    return (installed.firstOrNull { it.manifest.id == settings.selectedModelId } ?: installed.firstOrNull())?.manifest?.id
}

@Composable
private fun HubTabLabel(text: String, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick)) {
        Text(
            text,
            style = FreeSpeakType.mono.display.copy(fontSize = MaterialTheme.typography.headlineSmall.fontSize),
            color = if (selected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(4.dp))
        Dot(if (selected) NothingRed else androidx.compose.ui.graphics.Color.Transparent, 5.dp)
    }
}

@Composable
private fun MenuItem(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onClick: () -> Unit) {
    DropdownMenuItem(
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp)) },
        text = { Text(text, style = MaterialTheme.typography.bodyMedium) },
        onClick = onClick,
    )
}

@Composable
private fun MineList(
    mine: List<ManagedModelItem>,
    activeId: String?,
    actions: ModelActions,
    onDelete: (ModelManifest) -> Unit,
    onGoDiscover: () -> Unit,
) {
    if (mine.isEmpty()) {
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MonoLabel(stringResource(R.string.hub_no_models))
            Spacer(Modifier.height(8.dp))
            IconAction(Icons.Outlined.Explore, stringResource(R.string.hub_discover), onGoDiscover)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        items(mine, key = { it.manifest.id }) { item ->
            ModelRow(
                item = item,
                isActive = item.manifest.id == activeId,
                onDownload = { actions.onDownload(item.manifest) },
                onCancel = { actions.onCancel(item.manifest.id) },
                onSelect = { actions.onSelect(item.manifest.id) },
                onDelete = { onDelete(item.manifest) },
            )
            HairlineDivider(Modifier.padding(horizontal = 20.dp))
        }
    }
}

@Composable
private fun DiscoverList(
    models: List<ManagedModelItem>,
    activeId: String?,
    actions: ModelActions,
) {
    val curated = models.filter { item ->
        val source = item.manifest.sourceTag
        source == "builtin" || source == "catalog"
    }
    val featured = curated.filter { !it.manifest.optional }
    val optional = curated.filter { it.manifest.optional }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (featured.isNotEmpty()) {
            item(key = "featured_label") { SectionLabel(stringResource(R.string.hub_featured)) }
            items(featured, key = { "f_" + it.manifest.id }) { item ->
                ModelRow(
                    item = item,
                    isActive = item.manifest.id == activeId,
                    onDownload = { actions.onDownload(item.manifest) },
                    onCancel = { actions.onCancel(item.manifest.id) },
                    onSelect = { actions.onSelect(item.manifest.id) },
                    onDelete = null,
                )
                HairlineDivider(Modifier.padding(horizontal = 20.dp))
            }
        }
        if (optional.isNotEmpty()) {
            item(key = "optional_label") { SectionLabel(stringResource(R.string.hub_optional_models)) }
            items(optional, key = { "o_" + it.manifest.id }) { item ->
                ModelRow(
                    item = item,
                    isActive = item.manifest.id == activeId,
                    onDownload = { actions.onDownload(item.manifest) },
                    onCancel = { actions.onCancel(item.manifest.id) },
                    onSelect = { actions.onSelect(item.manifest.id) },
                    onDelete = null,
                )
                HairlineDivider(Modifier.padding(horizontal = 20.dp))
            }
        }
    }
}
