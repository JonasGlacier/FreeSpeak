package com.freespeak.ime.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.freespeak.ime.model.LanguageFilter
import com.freespeak.ime.model.supportsAll
import java.util.Locale
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

internal class ModelActions(
    val onDownload: (ModelManifest) -> Unit,
    val onCancel: (String) -> Unit,
    val onSelect: (String) -> Unit,
    val onDelete: (ModelManifest) -> Unit,
)

// ---------------------------------------------------------------- model list

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
    onOpenLanguages: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ModelManifest?>(null) }
    val activeId = resolveActiveModelId(models, settings)
    val preferred = settings.preferredLanguages

    val isCurated: (ManagedModelItem) -> Boolean = { it.manifest.sourceTag == "builtin" || it.manifest.sourceTag == "catalog" }
    // Installed models, plus imported/custom models in any state (they have no catalog section).
    val installed = models.filter {
        it.state is ModelInstallState.Installed || (!isCurated(it) && it.state !is ModelInstallState.NotInstalled)
    }
    val available = models.filter { isCurated(it) && it.state !is ModelInstallState.Installed }
    // Within a section, models that don't support the user's languages sink to the bottom.
    val bySupport = compareBy<ManagedModelItem> { if (it.manifest.supportsAll(preferred)) 0 else 1 }
    val featured = available.filter { !it.manifest.optional }.sortedWith(bySupport)
    val optional = available.filter { it.manifest.optional }.sortedWith(bySupport)

    Column(
        Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .statusBarsPadding()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 4.dp, top = 28.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PageTitle(stringResource(R.string.nav_models), Modifier.weight(1f))
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

        LanguageFilterChip(preferred, onOpenLanguages)

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            section("installed", R.string.hub_installed, installed) { item ->
                ModelRow(
                    item = item,
                    isActive = item.manifest.id == activeId,
                    onDownload = { actions.onDownload(item.manifest) },
                    onCancel = { actions.onCancel(item.manifest.id) },
                    onSelect = { actions.onSelect(item.manifest.id) },
                    onDelete = { pendingDelete = item.manifest },
                    showSelector = true,
                    dimmed = !item.manifest.supportsAll(preferred),
                )
            }
            section("featured", R.string.hub_featured, featured) { item -> AvailableRow(item, actions, preferred) { pendingDelete = it } }
            section("optional", R.string.hub_optional_models, optional) { item -> AvailableRow(item, actions, preferred) { pendingDelete = it } }
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

@Composable
private fun AvailableRow(item: ManagedModelItem, actions: ModelActions, preferred: Set<String>, onDelete: (ModelManifest) -> Unit) {
    ModelRow(
        item = item,
        isActive = false,
        onDownload = { actions.onDownload(item.manifest) },
        onCancel = { actions.onCancel(item.manifest.id) },
        onSelect = { actions.onSelect(item.manifest.id) },
        onDelete = { onDelete(item.manifest) },
        showSelector = false,
        dimmed = !item.manifest.supportsAll(preferred),
    )
}

private fun LazyListScope.section(
    key: String,
    labelRes: Int,
    items: List<ManagedModelItem>,
    row: @Composable (ManagedModelItem) -> Unit,
) {
    if (items.isEmpty()) return
    item(key = "${key}_label") { SectionLabel(stringResource(labelRes)) }
    items(items, key = { "${key}_" + it.manifest.id }) { item ->
        row(item)
        HairlineDivider(Modifier.padding(horizontal = 20.dp))
    }
}

/** Pill showing the user's languages; opens the multi-select language page. */
@Composable
private fun LanguageFilterChip(preferred: Set<String>, onClick: () -> Unit) {
    val label = stringResource(R.string.hub_languages)
    val summary = preferred.sortedBy { if (it == "en") 1 else 0 }.joinToString(" · ") { nativeLanguageName(it) }
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .semantics { contentDescription = "$label: $summary" },
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Translate, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Readout(summary, Modifier.widthIn(max = 240.dp), color = MaterialTheme.colorScheme.onSurface)
            Icon(
                Icons.Outlined.ArrowDropDown,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** Multi-select list of every language offered by the catalog. At least one stays selected. */
@Composable
internal fun PreferredLanguagesScreen(
    models: List<ManagedModelItem>,
    preferred: Set<String>,
    onToggle: (String) -> Unit,
    onBack: () -> Unit,
) {
    val uiLocale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val system = LanguageFilter.codeFor(Locale.getDefault())
    // Order is fixed while the page is open so rows don't jump when toggled.
    val codes = remember(models) {
        val all = LanguageFilter.availableLanguages(models.map { it.manifest }) + preferred
        val pinned = listOf(system, "en").filter { it in all }.distinct()
        pinned + (all - pinned.toSet()).sortedBy { localizedLanguageName(it, uiLocale) }
    }
    SubPageScaffold(stringResource(R.string.hub_languages), onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(codes, key = { it }) { code ->
                val native = nativeLanguageName(code)
                val localized = localizedLanguageName(code, uiLocale)
                val checked = code in preferred
                CheckRow(
                    title = native,
                    trailing = localized.takeIf { !it.equals(native, ignoreCase = true) },
                    checked = checked,
                    enabled = !(checked && preferred.size == 1),
                    onClick = { onToggle(code) },
                )
            }
        }
    }
}

@Composable
private fun CheckRow(title: String, trailing: String?, checked: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = RowHeight)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CheckBox(checked)
        Spacer(Modifier.width(16.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            Readout(trailing)
        }
    }
}

/** Square counterpart of [SelectDot] for multi-select. */
@Composable
private fun CheckBox(checked: Boolean) {
    val shape = RoundedCornerShape(4.dp)
    val ink = MaterialTheme.colorScheme.onSurface
    Box(
        Modifier
            .size(18.dp)
            .clip(shape)
            .background(if (checked) ink else Color.Transparent)
            .border(1.5.dp, if (checked) ink else MaterialTheme.colorScheme.outline, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.surface)
        }
    }
}

internal fun resolveActiveModelId(models: List<ManagedModelItem>, settings: UserSettings): String? {
    val installed = models.filter { it.state is ModelInstallState.Installed }
    return (installed.firstOrNull { it.manifest.id == settings.selectedModelId } ?: installed.firstOrNull())?.manifest?.id
}

@Composable
private fun MenuItem(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onClick: () -> Unit) {
    DropdownMenuItem(
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp)) },
        text = { Text(text, style = MaterialTheme.typography.bodyMedium) },
        onClick = onClick,
    )
}
