package com.freespeak.ime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import android.content.res.Resources
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.freespeak.ime.R
import com.freespeak.ime.model.BuiltinModelCatalog
import com.freespeak.ime.model.ManagedModelItem
import com.freespeak.ime.model.ModelInstallState
import com.freespeak.ime.model.ModelManifest
import com.freespeak.ime.model.hasTranslation
import com.freespeak.ime.model.isCanary
import com.freespeak.ime.model.matchedSystemLanguage
import com.freespeak.ime.model.supportedLanguageCount
import com.freespeak.ime.ui.theme.FreeSpeakType
import com.freespeak.ime.ui.theme.NothingRed
import java.util.Locale

// ---------------------------------------------------------------- primitives

@Composable
internal fun MonoLabel(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text = text.uppercase(Locale.ROOT),
        style = FreeSpeakType.mono.label,
        color = color,
        modifier = modifier,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
internal fun Readout(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text = text,
        style = FreeSpeakType.mono.readout,
        color = color,
        modifier = modifier,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
internal fun PageTitle(text: String, modifier: Modifier = Modifier, accentDot: Boolean = false) {
    Row(modifier = modifier, verticalAlignment = Alignment.Bottom) {
        Text(text = text, style = FreeSpeakType.mono.display, color = MaterialTheme.colorScheme.onBackground)
        if (accentDot) {
            Spacer(Modifier.width(4.dp))
            Box(
                Modifier
                    .padding(bottom = 9.dp)
                    .size(7.dp)
                    .background(NothingRed, CircleShape)
            )
        }
    }
}

/** Flat rounded container (no elevation), the Nothing "widget" look. */
@Composable
internal fun Panel(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(24.dp)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.padding(contentPadding), content = content)
    }
}

@Composable
internal fun Dot(color: Color, size: Dp = 8.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).background(color, CircleShape))
}

/** Selection indicator: hollow ring, or a filled red dot when selected. */
@Composable
internal fun SelectDot(selected: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(18.dp)
            .border(1.5.dp, if (selected) NothingRed else MaterialTheme.colorScheme.outline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Dot(NothingRed, 8.dp)
    }
}

@Composable
internal fun IconAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Icon(icon, contentDescription = contentDescription, tint = if (enabled) tint else tint.copy(alpha = 0.3f))
    }
}

@Composable
internal fun ThinProgress(progress: Float?, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.outlineVariant
    if (progress == null) {
        LinearProgressIndicator(
            modifier = modifier.fillMaxWidth().height(2.dp),
            color = NothingRed,
            trackColor = track,
            strokeCap = StrokeCap.Butt,
        )
    } else {
        LinearProgressIndicator(
            progress = { progress },
            modifier = modifier.fillMaxWidth().height(2.dp),
            color = NothingRed,
            trackColor = track,
            strokeCap = StrokeCap.Butt,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
    }
}

@Composable
internal fun HairlineDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier = modifier, thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
}

/** Fixed height shared by every list row so value rows and switch rows line up. */
internal val RowHeight = 56.dp

/** Rounded settings group: rows separated by inset hairlines. */
@Composable
internal fun RowGroup(
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 16.dp,
    rows: List<@Composable () -> Unit>,
) {
    if (rows.isEmpty()) return
    Panel(modifier = modifier.padding(horizontal = horizontalPadding), contentPadding = PaddingValues(0.dp)) {
        rows.forEachIndexed { i, row ->
            if (i > 0) HairlineDivider(Modifier.padding(start = 20.dp))
            row()
        }
    }
}

/**
 * Title / value / chevron row. The title takes the space first; the value is capped and
 * ellipsized, and value + chevron stay pinned to the end edge.
 */
@Composable
internal fun ValueRow(
    title: String,
    value: String?,
    onClick: () -> Unit,
    valueColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    leading: ImageVector? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RowHeight)
            .clickable(onClick = onClick)
            .padding(start = 20.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Icon(leading, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(16.dp))
        }
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!value.isNullOrEmpty()) {
            Spacer(Modifier.width(12.dp))
            Readout(value, color = valueColor, modifier = Modifier.widthIn(max = 136.dp))
        }
        Spacer(Modifier.width(4.dp))
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
internal fun ConfirmDeleteDialog(name: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(24.dp),
        title = { Text(stringResource(R.string.dialog_delete_title, name), style = MaterialTheme.typography.titleMedium) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_delete), color = NothingRed) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = MaterialTheme.colorScheme.onSurface) }
        },
    )
}

// ---------------------------------------------------------------- model row

/**
 * Compact model row:
 * - Line 1: model name + formatted size
 * - Line 2: feature tags
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ModelRow(
    item: ManagedModelItem,
    isActive: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onSelect: () -> Unit,
    onDelete: (() -> Unit)?,
    title: String = item.manifest.name,
    enabled: Boolean = true,
    startPadding: Dp = 20.dp,
) {
    val state = item.state
    val installed = state is ModelInstallState.Installed
    val sizeLabel = modelSizeLabel(item)
    val tags = resolveModelTags(item.manifest)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (installed && !isActive) it.clickable(onClick = onSelect) else it }
            .padding(start = startPadding, end = 8.dp, top = 12.dp, bottom = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (installed) {
                SelectDot(selected = isActive)
                Spacer(Modifier.width(16.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (sizeLabel != null) {
                        Spacer(Modifier.width(8.dp))
                        Readout(sizeLabel)
                    }
                }
                if (tags.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        tags.forEach { tag -> ModelTagChip(tag) }
                    }
                }
            }
            Spacer(Modifier.width(4.dp))
            when (state) {
                ModelInstallState.NotInstalled ->
                    IconAction(Icons.Outlined.Download, stringResource(R.string.action_download), onDownload, enabled = enabled)
                is ModelInstallState.Downloading ->
                    IconAction(Icons.Outlined.Pause, stringResource(R.string.action_pause), onCancel)
                ModelInstallState.Extracting ->
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 1.5.dp, color = NothingRed)
                    }
                is ModelInstallState.Partial -> {
                    IconAction(Icons.Outlined.Download, stringResource(R.string.action_resume), onDownload, enabled = enabled)
                    onDelete?.let { IconAction(Icons.Outlined.DeleteOutline, stringResource(R.string.action_delete), it) }
                }
                is ModelInstallState.Error -> {
                    IconAction(Icons.Outlined.Refresh, stringResource(R.string.action_retry), onDownload, tint = NothingRed)
                    onDelete?.let { IconAction(Icons.Outlined.DeleteOutline, stringResource(R.string.action_delete), it) }
                }
                is ModelInstallState.Installed ->
                    onDelete?.let {
                        IconAction(
                            Icons.Outlined.DeleteOutline,
                            stringResource(R.string.action_delete),
                            it,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
            }
        }
        when (state) {
            is ModelInstallState.Downloading -> {
                Spacer(Modifier.height(10.dp))
                ThinProgress(state.progress, Modifier.padding(end = 12.dp))
                Spacer(Modifier.height(6.dp))
                Readout(
                    "${formatBytes(state.downloadedBytes)} / ${formatBytes(state.totalBytes)} · ${formatSpeedLabel(state.speedBytesPerSec)}"
                )
            }
            is ModelInstallState.Partial -> {
                if (state.totalBytes > 0L) {
                    Spacer(Modifier.height(10.dp))
                    ThinProgress((state.downloadedBytes.toFloat() / state.totalBytes).coerceIn(0f, 1f), Modifier.padding(end = 12.dp))
                }
            }
            ModelInstallState.Extracting -> {
                Spacer(Modifier.height(10.dp))
                ThinProgress(null, Modifier.padding(end = 12.dp))
            }
            is ModelInstallState.Error -> {
                Spacer(Modifier.height(4.dp))
                Readout(state.message, color = NothingRed)
            }
            else -> Unit
        }
    }
}

@Composable
private fun ModelTagChip(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

@Composable
internal fun resolveModelTags(manifest: ModelManifest): List<String> {
    val uiLocale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val sysLocale = Resources.getSystem().configuration.locales[0] ?: uiLocale
    val hasKnownLanguages = manifest.sourceTag == "builtin" || manifest.sourceTag == "catalog" || manifest.engineType.isCanary
    val matchedLang = if (hasKnownLanguages) manifest.matchedSystemLanguage(sysLocale) else null
    val featureResIds = BuiltinModelCatalog.featureTagResIds(manifest.id)
    val features = if (featureResIds.isNotEmpty()) {
        featureResIds.map { stringResource(it) }
    } else {
        manifest.tags
    }
    return buildList {
        if (matchedLang != null) {
            add(stringResource(R.string.tag_supports_language, displayLanguageForTag(matchedLang, uiLocale)))
        }
        if (manifest.hasTranslation && manifest.supportedLanguageCount > 0) {
            add(stringResource(R.string.tag_n_languages, manifest.supportedLanguageCount))
        }
        addAll(features)
    }
}

internal fun displayLanguageForTag(matchedCode: String, uiLocale: Locale): String = when (uiLocale.language.lowercase(Locale.ROOT)) {
    "zh" -> if (matchedCode == "yue") {
        if (uiLocale.country.uppercase(Locale.ROOT) in setOf("TW", "HK", "MO") || uiLocale.script == "Hant") "粵語" else "粤语"
    } else {
        "中文"
    }
    "yue" -> if (matchedCode == "yue") "粵語" else "中文"
    "ja" -> Locale.forLanguageTag(matchedCode).getDisplayLanguage(Locale.JAPANESE)
    "ko" -> Locale.forLanguageTag(matchedCode).getDisplayLanguage(Locale.KOREAN)
    else -> Locale.forLanguageTag(matchedCode).getDisplayLanguage(Locale.ENGLISH)
}

internal fun modelSizeLabel(item: ManagedModelItem): String? {
    val bytes = when (val s = item.state) {
        is ModelInstallState.Installed -> s.diskBytes
        else -> item.manifest.sizeBytes
    }
    return bytes.takeIf { it > 0L }?.let { formatBytes(it) }
}

internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "—"
    val mb = bytes / (1024.0 * 1024.0)
    return when {
        mb >= 1024.0 -> String.format(Locale.ROOT, "%.2f GB", mb / 1024.0)
        mb >= 10.0 -> String.format(Locale.ROOT, "%.0f MB", mb)
        mb >= 1.0 -> String.format(Locale.ROOT, "%.1f MB", mb)
        else -> String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0)
    }
}

internal fun formatSpeedLabel(bytesPerSec: Long): String {
    if (bytesPerSec <= 0L) return "…"
    val kb = bytesPerSec / 1024.0
    return if (kb >= 1024.0) String.format(Locale.ROOT, "%.1f MB/s", kb / 1024.0) else String.format(Locale.ROOT, "%.0f KB/s", kb)
}
