package com.hanfengruyue.pocketrdp.logs

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hanfengruyue.pocketrdp.R
import com.hanfengruyue.pocketrdp.core.logging.PocketLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val LogWarnColorLight = Color(0xFF9A5B00)
private val LogWarnColorDark = Color(0xFFFFB74D)
private const val DARK_SURFACE_LUMINANCE_THRESHOLD = 0.5f
private const val AUTO_SCROLL_BOTTOM_THRESHOLD = 3

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val entries by PocketLogger.entries.collectAsStateWithLifecycle()
    var levelFilter by remember { mutableStateOf<PocketLogger.Level?>(null) }
    val exportChooserTitle = stringResource(R.string.logs_export_chooser)
    val exportSubject = stringResource(R.string.logs_share_subject)
    val exportFailed = stringResource(R.string.logs_export_failed)
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    val warnColor = if (MaterialTheme.colorScheme.surface.luminance() < DARK_SURFACE_LUMINANCE_THRESHOLD) {
        LogWarnColorDark
    } else {
        LogWarnColorLight
    }

    val filtered = remember(entries, levelFilter) {
        val f = levelFilter
        if (f == null) entries else entries.filter { it.level == f }
    }
    val latestEntry = filtered.lastOrNull()

    val listState = rememberLazyListState()
    val isNearBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf true
            lastVisible >= info.totalItemsCount - AUTO_SCROLL_BOTTOM_THRESHOLD
        }
    }
    // Stick to bottom when new entries arrive (even past the 1000-entry ring cap), without
    // yanking the user back down while they are scrolled up reading older log entries.
    LaunchedEffect(filtered.size, latestEntry) {
        if (filtered.isNotEmpty() && isNearBottom) {
            listState.scrollToItem(filtered.lastIndex)
        }
    }
    LaunchedEffect(levelFilter) {
        if (filtered.isNotEmpty()) {
            listState.scrollToItem(filtered.lastIndex)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            LogTopBar(
                exporting = exporting,
                onClose = onClose,
                onExport = {
                    exporting = true
                    scope.launch {
                        try {
                            exportLogs(
                                context = context,
                                exportSubject = exportSubject,
                                exportChooserTitle = exportChooserTitle,
                                exportFailed = exportFailed,
                            )
                        } finally {
                            exporting = false
                        }
                    }
                },
                onClear = {
                    scope.launch(Dispatchers.IO) {
                        PocketLogger.clear()
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            LevelFilterRow(current = levelFilter, onPick = { levelFilter = it })
            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (entries.isEmpty()) {
                            stringResource(R.string.logs_empty)
                        } else {
                            stringResource(R.string.logs_empty_filter)
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    items(filtered, key = { it.id }) { entry ->
                        LogRow(entry = entry, warnColor = warnColor)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogTopBar(
    exporting: Boolean,
    onClose: () -> Unit,
    onExport: () -> Unit,
    onClear: () -> Unit,
) {
    TopAppBar(
        windowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Horizontal + WindowInsetsSides.Top,
        ),
        title = { Text(stringResource(R.string.logs_title)) },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.cd_back),
                )
            }
        },
        actions = {
            IconButton(enabled = !exporting, onClick = onExport) {
                Icon(Icons.Default.Share, contentDescription = stringResource(R.string.logs_cd_export))
            }
            IconButton(onClick = onClear) {
                Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.logs_cd_clear))
            }
        },
    )
}

private suspend fun exportLogs(
    context: Context,
    exportSubject: String,
    exportChooserTitle: String,
    exportFailed: String,
) {
    runCatching {
        val uri = withContext(Dispatchers.IO) {
            val file = PocketLogger.snapshotForExport(context)
            FileProvider.getUriForFile(
                context,
                context.packageName + ".fileprovider",
                file,
            )
        }
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, exportSubject)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(share, exportChooserTitle))
    }.onFailure { error ->
        if (error is CancellationException) throw error
        PocketLogger.w("LogScreen", "log export failed", error)
        Toast.makeText(context, exportFailed, Toast.LENGTH_LONG).show()
    }
}

@Composable
private fun LevelFilterRow(current: PocketLogger.Level?, onPick: (PocketLogger.Level?) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FilterChip(
            selected = current == null,
            onClick = { onPick(null) },
            label = { Text(stringResource(R.string.logs_filter_all)) },
        )
        PocketLogger.Level.entries.forEach { lvl ->
            FilterChip(
                selected = current == lvl,
                onClick = { onPick(lvl) },
                label = { Text(lvl.tag) },
            )
        }
    }
}

@Composable
private fun LogRow(entry: PocketLogger.Entry, warnColor: Color) {
    val color = when (entry.level) {
        PocketLogger.Level.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
        PocketLogger.Level.INFO -> MaterialTheme.colorScheme.onSurface
        PocketLogger.Level.WARN -> warnColor
        PocketLogger.Level.ERROR -> MaterialTheme.colorScheme.error
    }
    val text = remember(entry.id) { entry.format() }
    Text(
        text = text,
        color = color,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    )
}
