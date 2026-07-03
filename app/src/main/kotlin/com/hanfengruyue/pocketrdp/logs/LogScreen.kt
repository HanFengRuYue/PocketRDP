package com.hanfengruyue.pocketrdp.logs

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hanfengruyue.pocketrdp.R
import com.hanfengruyue.pocketrdp.core.logging.PocketLogger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TIME_FMT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val entries by PocketLogger.entries.collectAsStateWithLifecycle()
    var levelFilter by remember { mutableStateOf<PocketLogger.Level?>(null) }
    val backDescription = stringResource(R.string.cd_back)
    val exportDescription = stringResource(R.string.logs_cd_export)
    val clearDescription = stringResource(R.string.logs_cd_clear)
    val exportChooserTitle = stringResource(R.string.logs_export_chooser)
    val exportSubject = stringResource(R.string.logs_share_subject)

    val filtered by remember(levelFilter) {
        derivedStateOf {
            val f = levelFilter
            if (f == null) entries else entries.filter { it.level == f }
        }
    }

    val listState = rememberLazyListState()
    // Stick to bottom when new entries arrive — typical log-viewer ergonomics.
    LaunchedEffect(filtered.size) {
        if (filtered.isNotEmpty()) listState.scrollToItem(filtered.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.logs_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = backDescription)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val file = PocketLogger.snapshotForExport(context)
                        val uri = FileProvider.getUriForFile(
                            context,
                            context.packageName + ".fileprovider",
                            file,
                        )
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            putExtra(Intent.EXTRA_SUBJECT, exportSubject)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(share, exportChooserTitle))
                    }) {
                        Icon(Icons.Default.Share, contentDescription = exportDescription)
                    }
                    IconButton(onClick = { PocketLogger.clear() }) {
                        Icon(Icons.Default.Clear, contentDescription = clearDescription)
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
                    items(filtered, key = { it.timestampMillis.toString() + it.tag + it.message.hashCode() }) { entry ->
                        LogRow(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun LevelFilterRow(current: PocketLogger.Level?, onPick: (PocketLogger.Level?) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
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
private fun LogRow(entry: PocketLogger.Entry) {
    val color = when (entry.level) {
        PocketLogger.Level.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
        PocketLogger.Level.INFO -> MaterialTheme.colorScheme.onSurface
        PocketLogger.Level.WARN -> Color(0xFFCC8800)
        PocketLogger.Level.ERROR -> MaterialTheme.colorScheme.error
    }
    val time = remember(entry.timestampMillis) { TIME_FMT.format(Date(entry.timestampMillis)) }
    val text = buildString {
        append(time); append(' ')
        append(entry.level.tag); append('/')
        append(entry.tag); append(": ")
        append(entry.message)
        entry.throwableText?.let { append('\n'); append(it) }
    }
    Text(
        text = text,
        color = color,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    )
}
