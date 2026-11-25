package com.freespeak.ime.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.freespeak.ime.R
import com.freespeak.ime.data.UserSettings
import com.freespeak.ime.model.ManagedModelItem
import com.freespeak.ime.model.ModelInstallState
import com.freespeak.ime.model.ModelManifest
import com.freespeak.ime.model.hf.HfIssue
import com.freespeak.ime.model.hf.HfModelSummary
import com.freespeak.ime.model.hf.HfRepoAnalysis
import com.freespeak.ime.model.hf.HfVariant
import com.freespeak.ime.ui.theme.FreeSpeakType
import com.freespeak.ime.ui.theme.NothingRed

enum class HubTab { MINE, DISCOVER }

private val QUICK_QUERIES = listOf("whisper.cpp", "qwen3-asr", "sense-voice", "funasr", "sherpa-onnx")

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
    search: HubSearchUi,
    contentPadding: PaddingValues,
    actions: ModelActions,
    onQueryChange: (String) -> Unit,
    onSubmitSearch: (String) -> Unit,
    onLoadMore: () -> Unit,
    onOpenRepo: (String) -> Unit,
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
                search = search,
                actions = actions,
                onQueryChange = onQueryChange,
                onSubmitSearch = onSubmitSearch,
                onLoadMore = onLoadMore,
                onOpenRepo = onOpenRepo,
                onOpenMirror = onOpenMirror,
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
            IconAction(Icons.Outlined.Search, stringResource(R.string.hub_discover), onGoDiscover)
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
    search: HubSearchUi,
    actions: ModelActions,
    onQueryChange: (String) -> Unit,
    onSubmitSearch: (String) -> Unit,
    onLoadMore: () -> Unit,
    onOpenRepo: (String) -> Unit,
    onOpenMirror: () -> Unit,
) {
    val focus = LocalFocusManager.current
    val listState = rememberLazyListState()
    val featured = models.filter { it.manifest.sourceTag == "builtin" || it.manifest.sourceTag == "catalog" }

    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 4
        }
    }
    LaunchedEffect(nearEnd, search.cursor, search.submitted) {
        if (nearEnd && search.cursor != null && search.submitted.isNotBlank()) onLoadMore()
    }

    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "search") {
            OutlinedTextField(
                value = search.query,
                onValueChange = onQueryChange,
                placeholder = { Text(stringResource(R.string.hub_search_hint), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = if (search.query.isNotEmpty()) {
                    { IconAction(Icons.Outlined.Close, stringResource(R.string.action_clear), { onQueryChange("") }) }
                } else null,
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    focus.clearFocus()
                    onSubmitSearch(search.query)
                }),
                colors = monoFieldColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        item(key = "chips") {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                items(QUICK_QUERIES) { q ->
                    val selected = search.submitted.equals(q, ignoreCase = true)
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.clickable {
                            focus.clearFocus()
                            onQueryChange(q)
                            onSubmitSearch(q)
                        },
                    ) {
                        Text(
                            q,
                            style = FreeSpeakType.mono.readout,
                            color = if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }

        if (search.submitted.isBlank()) {
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
        } else {
            when {
                search.loading -> item(key = "loading") { ThinProgress(null, Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) }
                search.error != null -> item(key = "error") {
                    ErrorRow(search.error, onRetry = { onSubmitSearch(search.submitted) }, onOpenMirror = onOpenMirror)
                }
                search.results.isEmpty() -> item(key = "empty") {
                    MonoLabel(stringResource(R.string.hub_no_results), Modifier.padding(20.dp))
                }
                else -> {
                    items(search.results, key = { "r_" + it.id }) { r ->
                        SearchResultRow(r) { onOpenRepo(r.id) }
                        HairlineDivider(Modifier.padding(horizontal = 20.dp))
                    }
                    if (search.loadingMore) {
                        item(key = "more") { ThinProgress(null, Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorRow(message: String, onRetry: () -> Unit, onOpenMirror: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Readout(message, Modifier.weight(1f), color = NothingRed)
        IconAction(Icons.Outlined.Public, stringResource(R.string.pref_download_mirror), onOpenMirror)
        IconAction(Icons.Outlined.Refresh, stringResource(R.string.action_retry), onRetry)
    }
}

@Composable
private fun SearchResultRow(r: HfModelSummary, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            MonoLabel(r.owner)
            Spacer(Modifier.height(2.dp))
            Text(r.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Readout(stringResource(R.string.hub_downloads_likes, formatCount(r.downloads), formatCount(r.likes.toLong())))
        }
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(20.dp),
        )
    }
}

// ---------------------------------------------------------------- repo detail

@Composable
internal fun HuggingFaceRepoDetailScreen(
    detail: HubDetailUi,
    models: List<ManagedModelItem>,
    settings: UserSettings,
    manifestFor: (HfRepoAnalysis, HfVariant) -> ModelManifest,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onDownloadVariant: (HfRepoAnalysis, HfVariant) -> Unit,
    onOpenMirror: () -> Unit,
    actions: ModelActions,
) {
    val ctx = LocalContext.current
    var pendingDelete by remember { mutableStateOf<ModelManifest?>(null) }
    val activeId = resolveActiveModelId(models, settings)
    val byId = remember(models) { models.associateBy { it.manifest.id } }

    SubPageScaffold(
        title = detail.repoId.substringAfter('/'),
        onBack = onBack,
        actions = {
            IconAction(Icons.Outlined.OpenInNew, stringResource(R.string.action_open_browser), {
                runCatching {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://huggingface.co/${detail.repoId}")))
                }
            })
        },
    ) { padding ->
        when (detail) {
            is HubDetailUi.Loading -> Box(Modifier.fillMaxSize().padding(padding)) {
                ThinProgress(null, Modifier.padding(horizontal = 20.dp, vertical = 16.dp))
            }
            is HubDetailUi.Failed -> Column(Modifier.fillMaxSize().padding(padding)) {
                ErrorRow(detail.message, onRetry = onRetry, onOpenMirror = onOpenMirror)
            }
            is HubDetailUi.Ready -> {
                val a = detail.analysis
                val info = a.info
                LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
                    item(key = "header") {
                        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                            MonoLabel(info.id.substringBefore('/'))
                            Spacer(Modifier.height(6.dp))
                            Readout(
                                listOfNotNull(
                                    stringResource(R.string.hub_downloads_likes, formatCount(info.downloads), formatCount(info.likes.toLong())),
                                    (info.lastModified ?: info.createdAt)?.take(10),
                                    info.sha?.take(7),
                                ).joinToString("  ·  ")
                            )
                        }
                    }
                    if (a.blocked) {
                        item(key = "blocked") {
                            IssueRow(
                                Icons.Outlined.Lock,
                                stringResource(if (HfIssue.GATED in a.issues) R.string.hf_issue_gated else R.string.hf_issue_disabled),
                                highlight = true,
                            )
                        }
                    }
                    variantSection(
                        key = "ok",
                        label = { stringResource(R.string.hub_compatible) },
                        variants = a.supported,
                        analysis = a,
                        byId = byId,
                        activeId = activeId,
                        manifestFor = manifestFor,
                        onDownloadVariant = onDownloadVariant,
                        actions = actions,
                        onDelete = { pendingDelete = it },
                    )
                    variantSection(
                        key = "unverified",
                        label = { stringResource(R.string.hub_unverified) },
                        variants = a.unverified,
                        analysis = a,
                        byId = byId,
                        activeId = activeId,
                        manifestFor = manifestFor,
                        onDownloadVariant = onDownloadVariant,
                        actions = actions,
                        onDelete = { pendingDelete = it },
                    )
                    val issues = a.issues - HfIssue.GATED - HfIssue.DISABLED
                    if (a.variants.isEmpty() && issues.isNotEmpty()) {
                        item(key = "issues_label") { SectionLabel(stringResource(R.string.hub_unsupported)) }
                        items(issues.toList(), key = { "i_" + it.name }) { issue ->
                            IssueRow(Icons.Outlined.Block, stringResource(issue.labelRes()))
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { m ->
        ConfirmDeleteDialog(m.name, onDismiss = { pendingDelete = null }, onConfirm = {
            actions.onDelete(m)
            pendingDelete = null
        })
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.variantSection(
    key: String,
    label: @Composable () -> String,
    variants: List<HfVariant>,
    analysis: HfRepoAnalysis,
    byId: Map<String, ManagedModelItem>,
    activeId: String?,
    manifestFor: (HfRepoAnalysis, HfVariant) -> ModelManifest,
    onDownloadVariant: (HfRepoAnalysis, HfVariant) -> Unit,
    actions: ModelActions,
    onDelete: (ModelManifest) -> Unit,
) {
    if (variants.isEmpty()) return
    item(key = "${key}_label") { SectionLabel(label()) }
    items(variants, key = { "${key}_" + it.key }) { v ->
        val manifest = manifestFor(analysis, v)
        val item = byId[manifest.id] ?: ManagedModelItem(manifest, ModelInstallState.NotInstalled, java.io.File(""))
        ModelRow(
            item = item,
            isActive = manifest.id == activeId,
            title = v.label,
            readout = listOf(formatBytes(v.totalBytes), v.quantization, v.engineType.shortName).joinToString(" · "),
            onDownload = { onDownloadVariant(analysis, v) },
            onCancel = { actions.onCancel(manifest.id) },
            onSelect = { actions.onSelect(manifest.id) },
            onDelete = { onDelete(item.manifest) },
            enabled = !analysis.blocked,
        )
        HairlineDivider(Modifier.padding(horizontal = 20.dp))
    }
}

@Composable
private fun IssueRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, highlight: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (highlight) NothingRed else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (highlight) FontWeight.Medium else FontWeight.Normal,
            color = if (highlight) NothingRed else MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun HfIssue.labelRes(): Int = when (this) {
    HfIssue.GATED -> R.string.hf_issue_gated
    HfIssue.DISABLED -> R.string.hf_issue_disabled
    HfIssue.GGUF -> R.string.hf_issue_gguf
    HfIssue.CTRANSLATE2 -> R.string.hf_issue_ct2
    HfIssue.PYTORCH -> R.string.hf_issue_pytorch
    HfIssue.WHISPER_ONNX -> R.string.hf_issue_whisper_onnx
    HfIssue.TRANSDUCER -> R.string.hf_issue_transducer
    HfIssue.OTHER_RUNTIME -> R.string.hf_issue_other_runtime
    HfIssue.UNKNOWN_ONNX -> R.string.hf_issue_unknown_onnx
    HfIssue.NO_WEIGHTS -> R.string.hf_issue_no_weights
}
