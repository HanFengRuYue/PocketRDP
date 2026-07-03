package com.hanfengruyue.pocketrdp.feature.connections.list

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hanfengruyue.pocketrdp.core.data.model.ConnectionEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Card geometry / styling tunables (kept as property declarations so detekt's MagicNumber rule,
// which ignores property initializers, doesn't flag them).
private val cardCornerDp = 20.dp
private val landscapeCardCornerDp = 14.dp
private val bannerPlaySizeDp = 44.dp
private val placeholderIconDp = 40.dp
private val emptyIconDp = 72.dp
private val emptyLandscapeGapDp = 20.dp
private val landscapeLayoutMinWidthDp = 560.dp
private val landscapeListHorizontalPaddingDp = 24.dp
private val landscapeListVerticalPaddingDp = 12.dp
private val landscapeCardSpacingDp = 12.dp
private val landscapeCardPaddingDp = 12.dp
private val landscapeThumbnailWidthDp = 220.dp
private val landscapeThumbnailHeightDp = 124.dp
private val landscapeThumbnailCornerDp = 10.dp
private val landscapeContentGapDp = 12.dp
private val landscapeMetaPillCornerDp = 8.dp
private const val DESKTOP_ASPECT = 16f / 9f
private const val SCRIM_ALPHA = 0.55f
private const val META_PILL_ALPHA = 0.46f
private const val PLACEHOLDER_ICON_ALPHA = 0.7f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionListScreen(
    onAddNew: () -> Unit,
    onEdit: (Long) -> Unit,
    onConnect: (Long) -> Unit,
    onOpenLogs: () -> Unit,
    onOpenKeepAliveGuide: () -> Unit = {},
    showKeepAliveHint: Boolean = false,
    viewModel: ConnectionListViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // The kill hint is shown at most once per visit — track local dismissal here so we don't need a
    // separate dismiss callback param (keeps the parameter list under detekt's threshold).
    var keepAliveHintDismissed by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PocketRDP") },
                actions = {
                    IconButton(onClick = onOpenKeepAliveGuide) {
                        Icon(Icons.Default.Settings, contentDescription = "后台保活设置")
                    }
                    IconButton(onClick = onOpenLogs) {
                        Icon(Icons.AutoMirrored.Filled.ReceiptLong, contentDescription = "日志")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddNew,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("新建连接") },
            )
        },
    ) { padding ->
        when {
            state.isLoading -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            state.connections.isEmpty() -> EmptyState(padding)
            else -> ConnectionList(
                items = state.connections,
                padding = padding,
                loadThumbnail = viewModel::loadThumbnail,
                onEdit = onEdit,
                onConnect = onConnect,
                onDelete = viewModel::delete,
            )
        }
    }

    // Surfaced once after a previous session appears to have been killed in the background (the
    // keep-alive flag was still set + the last process exit looks like an OS/OEM kill, not a clean
    // exit or app crash). Points the user at the OEM allow-list guide — the real fix on those ROMs.
    if (showKeepAliveHint && !keepAliveHintDismissed) {
        AlertDialog(
            onDismissRequest = { keepAliveHintDismissed = true },
            title = { Text("远程连接被系统中断了？") },
            text = {
                Text(
                    "检测到上次的远程会话可能被系统在后台清理掉了。要在切到后台 / 锁屏时保持连接不断，" +
                        "需要在系统设置里允许 PocketRDP 自启动并取消后台限制。是否查看保活设置？",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    keepAliveHintDismissed = true
                    onOpenKeepAliveGuide()
                }) { Text("查看保活设置") }
            },
            dismissButton = {
                TextButton(onClick = { keepAliveHintDismissed = true }) { Text("忽略") }
            },
        )
    }
}

@Composable
private fun EmptyState(padding: PaddingValues) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
        val useLandscapeLayout = maxWidth >= landscapeLayoutMinWidthDp && maxWidth > maxHeight
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (useLandscapeLayout) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(emptyLandscapeGapDp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Computer,
                        contentDescription = null,
                        modifier = Modifier.size(emptyIconDp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column(horizontalAlignment = Alignment.Start) {
                        Text(
                            "还没有保存的远程电脑",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.size(4.dp))
                        Text(
                            "点右下角“新建连接”来添加你的第一台 Windows 主机",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Computer,
                        contentDescription = null,
                        modifier = Modifier.size(emptyIconDp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.size(16.dp))
                    Text(
                        "还没有保存的远程电脑",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        "点右下角“新建连接”来添加你的第一台 Windows 主机",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectionList(
    items: List<ConnectionEntity>,
    padding: PaddingValues,
    loadThumbnail: suspend (Long) -> Bitmap?,
    onEdit: (Long) -> Unit,
    onConnect: (Long) -> Unit,
    onDelete: (ConnectionEntity) -> Unit,
) {
    // Bump on every ON_RESUME so cards reload their thumbnails when the user returns from a session
    // (the session just saved a fresh snapshot, but the DB row didn't change so the list flow won't
    // re-emit on its own).
    val refreshKey = rememberThumbnailRefreshKey()
    BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
        val useLandscapeLayout = maxWidth >= landscapeLayoutMinWidthDp && maxWidth > maxHeight
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = if (useLandscapeLayout) {
                PaddingValues(
                    horizontal = landscapeListHorizontalPaddingDp,
                    vertical = landscapeListVerticalPaddingDp,
                )
            } else {
                PaddingValues(horizontal = 16.dp, vertical = 16.dp)
            },
            verticalArrangement = Arrangement.spacedBy(
                if (useLandscapeLayout) landscapeCardSpacingDp else 16.dp,
            ),
        ) {
            items(items, key = { it.id }) { conn ->
                ConnectionCard(
                    entity = conn,
                    refreshKey = refreshKey,
                    useLandscapeLayout = useLandscapeLayout,
                    loadThumbnail = loadThumbnail,
                    onEdit = { onEdit(conn.id) },
                    onConnect = { onConnect(conn.id) },
                    onDelete = { onDelete(conn) },
                )
            }
        }
    }
}

/** Counter that increments every time the host lifecycle resumes — keys thumbnail reloads. */
@Composable
private fun rememberThumbnailRefreshKey(): Int {
    val lifecycleOwner = LocalLifecycleOwner.current
    var key by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) key++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return key
}

@Composable
private fun ConnectionCard(
    entity: ConnectionEntity,
    refreshKey: Int,
    useLandscapeLayout: Boolean,
    loadThumbnail: suspend (Long) -> Bitmap?,
    onEdit: () -> Unit,
    onConnect: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    val thumbnail by produceState<Bitmap?>(initialValue = null, entity.id, refreshKey) {
        value = loadThumbnail(entity.id)
    }

    ElevatedCard(
        onClick = onConnect,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(if (useLandscapeLayout) landscapeCardCornerDp else cardCornerDp),
        elevation = CardDefaults.elevatedCardElevation(),
    ) {
        if (useLandscapeLayout) {
            LandscapeConnectionCardContent(
                entity = entity,
                thumbnail = thumbnail,
                onConnect = onConnect,
                onEdit = onEdit,
                onDelete = { confirmDelete = true },
            )
        } else {
            DesktopThumbnail(
                bitmap = thumbnail,
                host = entity.host,
                modifier = Modifier.fillMaxWidth().aspectRatio(DESKTOP_ASPECT),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ConnectionSummary(entity = entity, modifier = Modifier.weight(1f))
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "编辑")
                }
                IconButton(onClick = { confirmDelete = true }) {
                    Icon(Icons.Default.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (confirmDelete) {
        DeleteConfirmDialog(
            name = entity.name.ifBlank { entity.host },
            onConfirm = { confirmDelete = false; onDelete() },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun LandscapeConnectionCardContent(
    entity: ConnectionEntity,
    thumbnail: Bitmap?,
    onConnect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(landscapeCardPaddingDp),
        horizontalArrangement = Arrangement.spacedBy(landscapeContentGapDp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DesktopThumbnail(
            bitmap = thumbnail,
            host = entity.host,
            modifier = Modifier
                .size(width = landscapeThumbnailWidthDp, height = landscapeThumbnailHeightDp)
                .clip(RoundedCornerShape(landscapeThumbnailCornerDp)),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ConnectionSummary(entity = entity, showLastUsed = false)
            Surface(
                shape = RoundedCornerShape(landscapeMetaPillCornerDp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = META_PILL_ALPHA),
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Text(
                    text = "上次使用 · ${relativeLastUsed(entity.lastUsedAt)}",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = onConnect) {
                Icon(Icons.Default.PlayArrow, contentDescription = "连接")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "编辑")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun ConnectionSummary(
    entity: ConnectionEntity,
    modifier: Modifier = Modifier,
    showLastUsed: Boolean = true,
) {
    Column(modifier = modifier) {
        Text(
            text = entity.name.ifBlank { entity.host },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "${entity.username.ifBlank { "(no user)" }} @ ${entity.host}:${entity.port}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (showLastUsed) {
            Text(
                text = "上次使用 · ${relativeLastUsed(entity.lastUsedAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/**
 * 16:9 desktop preview banner: the captured remote-screen thumbnail when available, otherwise a
 * tinted gradient placeholder. A translucent play badge overlays the bottom-end as the "tap to
 * connect" affordance (the whole card is clickable).
 */
@Composable
private fun DesktopThumbnail(bitmap: Bitmap?, host: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "远程桌面预览",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primaryContainer,
                                MaterialTheme.colorScheme.tertiaryContainer,
                            ),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Computer,
                        contentDescription = null,
                        modifier = Modifier.size(placeholderIconDp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = PLACEHOLDER_ICON_ALPHA),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = host,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Surface(
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).size(bannerPlaySizeDp),
            shape = CircleShape,
            color = Color.Black.copy(alpha = SCRIM_ALPHA),
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                modifier = Modifier.padding(8.dp),
                tint = Color.White,
            )
        }
    }
}

@Composable
private fun DeleteConfirmDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除连接") },
        text = { Text("确定删除“$name”吗？此操作无法撤销。") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private const val MIN_AGO = 60_000L
private const val HOUR_AGO = 3_600_000L
private const val DAY_AGO = 86_400_000L
private const val WEEK_AGO = 7 * 86_400_000L

/** Human-friendly "last used" label: 刚刚 / N 分钟前 / N 小时前 / N 天前 / yyyy-MM-dd. */
private fun relativeLastUsed(ms: Long): String {
    if (ms <= 0L) return "尚未使用"
    val diff = System.currentTimeMillis() - ms
    return when {
        diff < 0L -> "刚刚"
        diff < MIN_AGO -> "刚刚"
        diff < HOUR_AGO -> "${diff / MIN_AGO} 分钟前"
        diff < DAY_AGO -> "${diff / HOUR_AGO} 小时前"
        diff < WEEK_AGO -> "${diff / DAY_AGO} 天前"
        else -> SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(ms))
    }
}
