package com.freespeak.ime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.freespeak.ime.R
import com.freespeak.ime.data.SupportedLanguages
import com.freespeak.ime.model.BuiltinModelCatalog
import com.freespeak.ime.model.ManagedModelItem
import com.freespeak.ime.model.ModelInstallState
import com.freespeak.ime.model.ModelManifest
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
    iconSize: Dp = 24.dp,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Icon(icon, contentDescription = contentDescription, tint = if (enabled) tint else tint.copy(alpha = 0.3f), modifier = Modifier.size(iconSize))
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
 * Two-line model row:
 * - Line 1: name (+ trailing action)
 * - Line 2: mono readout "size · tag · tag"
 * [showSelector] reserves the selection-dot column so every row in a section lines up.
 * [dimmed] greys the row (model doesn't support the user's languages) but keeps it usable.
 */
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
    showSelector: Boolean = item.state is ModelInstallState.Installed,
    dimmed: Boolean = false,
) {
    val state = item.state
    val installed = state is ModelInstallState.Installed
    val readout = listOfNotNull(modelSizeLabel(item)).plus(modelFeatureTags(item.manifest)).joinToString(" · ")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (installed && !isActive) it.clickable(onClick = onSelect) else it }
            .alpha(if (dimmed) 0.38f else 1f)
            .padding(start = startPadding, end = 6.dp, top = 12.dp, bottom = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showSelector) {
                Box(Modifier.width(34.dp)) { if (installed) SelectDot(selected = isActive) }
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (readout.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Readout(readout)
                }
            }
            Spacer(Modifier.width(4.dp))
            val grey = MaterialTheme.colorScheme.outline
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
                    onDelete?.let { IconAction(Icons.Outlined.DeleteOutline, stringResource(R.string.action_delete), it, tint = grey, iconSize = 20.dp) }
                }
                is ModelInstallState.Error -> {
                    IconAction(Icons.Outlined.Refresh, stringResource(R.string.action_retry), onDownload, tint = NothingRed)
                    onDelete?.let { IconAction(Icons.Outlined.DeleteOutline, stringResource(R.string.action_delete), it, tint = grey, iconSize = 20.dp) }
                }
                is ModelInstallState.Installed ->
                    onDelete?.let { IconAction(Icons.Outlined.DeleteOutline, stringResource(R.string.action_delete), it, tint = grey, iconSize = 20.dp) }
            }
        }
        val progressPad = Modifier.padding(start = if (showSelector) 34.dp else 0.dp, end = 12.dp)
        when (state) {
            is ModelInstallState.Downloading -> {
                Spacer(Modifier.height(10.dp))
                ThinProgress(state.progress, progressPad)
                Spacer(Modifier.height(6.dp))
                Readout(
                    "${formatBytes(state.downloadedBytes)} / ${formatBytes(state.totalBytes)} · ${formatSpeedLabel(state.speedBytesPerSec)}",
                    progressPad,
                )
            }
            is ModelInstallState.Partial -> {
                if (state.totalBytes > 0L) {
                    Spacer(Modifier.height(10.dp))
                    ThinProgress((state.downloadedBytes.toFloat() / state.totalBytes).coerceIn(0f, 1f), progressPad)
                }
            }
            ModelInstallState.Extracting -> {
                Spacer(Modifier.height(10.dp))
                ThinProgress(null, progressPad)
            }
            is ModelInstallState.Error -> {
                Spacer(Modifier.height(4.dp))
                Readout(state.message, progressPad, color = NothingRed)
            }
            else -> Unit
        }
    }
}

/** Up to two feature tags (curated localized tags, else catalog tags). No language tags. */
@Composable
internal fun modelFeatureTags(manifest: ModelManifest): List<String> {
    val resIds = BuiltinModelCatalog.featureTagResIds(manifest.id)
    val tags = if (resIds.isNotEmpty()) resIds.map { stringResource(it) } else manifest.tags
    return tags.take(2)
}

/** Native name of a language code, e.g. "zh" -> "中文", "de" -> "Deutsch". */
internal fun nativeLanguageName(code: String): String {
    SupportedLanguages.all.firstOrNull { it.code == code }?.let { return it.nativeLabel }
    val locale = Locale.forLanguageTag(code)
    val name = locale.getDisplayLanguage(locale)
    return if (name.isBlank() || name.equals(code, ignoreCase = true)) code.uppercase(Locale.ROOT)
    else name.replaceFirstChar { it.titlecase(locale) }
}

/** Name of a language code in the UI language, e.g. "de" -> "德语". */
internal fun localizedLanguageName(code: String, uiLocale: Locale): String {
    val name = Locale.forLanguageTag(code).getDisplayLanguage(uiLocale)
    return if (name.isBlank()) code.uppercase(Locale.ROOT) else name.replaceFirstChar { it.titlecase(uiLocale) }
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
