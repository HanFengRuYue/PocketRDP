package com.hanfengruyue.pocketrdp.feature.connections.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Domain
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionEditScreen(
    onClose: () -> Unit,
    viewModel: ConnectionEditViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(state.saved) {
        if (state.saved) onClose()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.id == null) "新建连接" else "编辑连接") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionTitle("基础信息", Icons.Default.Badge)
            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::updateName,
                label = { Text("名称（仅本地显示）") },
                leadingIcon = { Icon(Icons.Default.Badge, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = state.host,
                    onValueChange = viewModel::updateHost,
                    label = { Text("主机 IP 或域名") },
                    leadingIcon = { Icon(Icons.Default.Dns, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = state.port,
                    onValueChange = viewModel::updatePort,
                    label = { Text("端口") },
                    leadingIcon = { Icon(Icons.Default.Numbers, contentDescription = null) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.width(150.dp),
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = state.username,
                    onValueChange = viewModel::updateUsername,
                    label = { Text("用户名") },
                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = state.domain,
                    onValueChange = viewModel::updateDomain,
                    label = { Text("域（可选）") },
                    leadingIcon = { Icon(Icons.Default.Domain, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            OutlinedTextField(
                value = state.password,
                onValueChange = viewModel::updatePassword,
                label = {
                    Text(if (state.hasExistingPassword) "密码（留空保持不变）" else "密码")
                },
                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            SectionTitle("显示与画质", Icons.Default.Tune)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RowLabel(Icons.Default.Palette, "色深", Modifier.weight(1f))
                listOf(16, 24, 32).forEach { depth ->
                    AssistChip(
                        onClick = { viewModel.updateColorDepth(depth) },
                        label = { Text("${depth}bit") },
                        leadingIcon = null,
                        enabled = state.colorDepth != depth,
                    )
                }
            }

            Column {
                Text(
                    "远程桌面缩放：${state.desktopScaleFactor}%",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Slider(
                    value = state.desktopScaleFactor.toFloat(),
                    onValueChange = { viewModel.updateScaleFactor(it.toInt()) },
                    valueRange = 100f..300f,
                    steps = 7,
                )
                Text(
                    "远程 Windows 桌面字体/界面的显示倍率（非本地画面放大；本地放大用画面上的缩放按钮）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RowLabel(Icons.Default.Speed, "固定帧率", Modifier.weight(1f))
                frameRateOptions.forEach { fps ->
                    AssistChip(
                        onClick = { viewModel.updateFrameRate(fps) },
                        label = { Text(if (fps == 0) "自动" else "$fps") },
                        leadingIcon = null,
                        enabled = state.targetFrameRate != fps,
                    )
                }
            }
            Text(
                "画面以此帧率持续刷新（不再随内容静止归零），实际不超过本机屏幕刷新率。" +
                    "远端可提供的帧率仍取决于被控电脑（Windows 远程桌面默认约 30fps），请按其能力设上限。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RowLabel(Icons.Default.TouchApp, "默认输入模式", Modifier.weight(1f))
                inputModeOptions.forEach { (value, label) ->
                    AssistChip(
                        onClick = { viewModel.updateDefaultInputMode(value) },
                        label = { Text(label) },
                        enabled = state.defaultInputMode != value,
                    )
                }
            }
            Text(
                "模拟鼠标=虚拟光标+点按拖动；直接触屏=手指作为 Windows 原生触摸点（多点/捏合交给 Windows）。进入会话后仍可随时切换。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SwitchRow(
                title = "启用 H.264 视频加速",
                subtitle = "用 H.264 压缩远端画面，大幅降低视频/动画卡顿；优先用设备硬件解码（MediaCodec），" +
                    "不可用时回退 FFmpeg 软解。关闭则用 RemoteFX。需主机支持，不支持时自动回退",
                checked = state.useH264,
                onChange = viewModel::toggleH264,
                icon = Icons.Default.Videocam,
            )
            if (state.useH264) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RowLabel(Icons.Default.HighQuality, "H.264 画质档", Modifier.weight(1f))
                    codecTierOptions.forEach { (avc420, label) ->
                        AssistChip(
                            onClick = { viewModel.updatePreferAvc420(avc420) },
                            label = { Text(label) },
                            enabled = state.preferAvc420 != avc420,
                        )
                    }
                }
                Text(
                    "默认「流畅优先」：AVC420（4:2:0 单流），解码开销约减半、操控延迟最低，也是硬件解码最稳的路径，" +
                        "仅色度/文字边缘略软。「画质优先」：AVC444 全彩 4:4:4，文字最清晰，但解码更重、延迟更高，" +
                        "个别机型硬解可能花屏。追求跟手选流畅优先。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SwitchRow(
                title = "启用 GFX 图形管道",
                subtitle = "RemoteFX 渐进编码路径。开启 H.264 时图形管道会被强制启用（AVC444 只能走 GFX 通道）",
                // When H.264 is on, the H264 branch in buildCommandLine always emits /gfx:AVC444 and
                // this toggle is bypassed — so lock it ON+disabled to reflect reality instead of
                // showing a switch that silently does nothing (field bug: 关掉 GFX 却仍生效).
                checked = state.useGfx || state.useH264,
                enabled = !state.useH264,
                onChange = viewModel::toggleGfx,
                icon = Icons.Default.AutoAwesome,
            )
            SwitchRow(
                title = "动态分辨率",
                subtitle = "屏幕旋转/拉伸时远端跟随调整",
                checked = state.dynamicResolution,
                enabled = !state.useCustomResolution,
                onChange = viewModel::toggleDynamicRes,
                icon = Icons.Default.AspectRatio,
            )
            if (state.dynamicResolution && !state.useCustomResolution) {
                RowLabel(Icons.Default.AspectRatio, "动态分辨率上限")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    dynamicResMaxOptions.forEach { (value, label) ->
                        AssistChip(
                            onClick = { viewModel.updateDynamicResMax(value) },
                            label = { Text(label) },
                            enabled = state.dynamicResMax != value,
                        )
                    }
                }
                Text(
                    "限制远端最高分辨率，避免直接套用手机高分屏导致被控电脑渲染压力过大。" +
                        "跟随设备=按手机视图实际尺寸；其余按所选高度等比缩小（如 1080p ≈ 最高 1920×1080）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SwitchRow(
                title = "固定自定义分辨率",
                subtitle = "连接时远端固定为指定宽高（开启后将关闭动态分辨率）",
                checked = state.useCustomResolution,
                onChange = viewModel::toggleCustomResolution,
                icon = Icons.Default.FitScreen,
            )
            if (state.useCustomResolution) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = state.customWidth,
                        onValueChange = viewModel::updateCustomWidth,
                        label = { Text("宽度") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Text("×", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = state.customHeight,
                        onValueChange = viewModel::updateCustomHeight,
                        label = { Text("高度") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(1920 to 1080, 1280 to 720, 2560 to 1440, 3840 to 2160).forEach { (w, h) ->
                        AssistChip(
                            onClick = {
                                viewModel.updateCustomWidth(w.toString())
                                viewModel.updateCustomHeight(h.toString())
                            },
                            label = { Text("${w}×$h") },
                        )
                    }
                }
            }
            SwitchRow(
                title = "UDP 多路传输",
                subtitle = "RDP-UDP 通道，弱网/高延迟下更流畅；服务器不支持时自动回退 TCP",
                checked = state.useMultitransport,
                onChange = viewModel::toggleMultitransport,
                icon = Icons.Default.Bolt,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            SectionTitle("重定向", Icons.Default.Cable)

            SwitchRow(
                title = "剪贴板双向同步",
                subtitle = "复制的文字在手机与被控电脑之间双向共享（图片暂不支持）",
                checked = state.redirectClipboard,
                onChange = viewModel::toggleClipboard,
                icon = Icons.Default.ContentPaste,
            )
            SwitchRow(
                title = "文件夹重定向",
                subtitle = "把整个手机存储（/storage/emulated/0）挂载到被控电脑（资源管理器出现「PocketRDP」盘）；" +
                    "需授予「所有文件访问」权限，开启时会跳转系统设置授权",
                checked = state.redirectFiles,
                onChange = { on ->
                    viewModel.toggleFiles(on)
                    // Grant the special permission at setup time so the drive is already readable on the
                    // first connect (the /drive path is parsed once at connect — granting later needs a
                    // reconnect). minSdk 31 ≥ 30, so isExternalStorageManager is always available.
                    if (on && !android.os.Environment.isExternalStorageManager()) {
                        requestAllFilesAccess(context)
                    }
                },
                icon = Icons.Default.Folder,
            )

            RowLabel(Icons.AutoMirrored.Filled.VolumeUp, "远程音频")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                soundModeOptions.forEach { (value, label) ->
                    AssistChip(
                        onClick = { viewModel.updateSoundMode(value) },
                        label = { Text(label) },
                        enabled = state.soundMode != value,
                    )
                }
            }
            Text(
                "控制端播放=远端声音传到手机播放（占带宽，需主机允许音频重定向）；" +
                    "被控端播放=声音留在被控电脑自己的扬声器；停用=不传输音频。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state.errors.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        state.errors.forEach { msg ->
                            Text(
                                "• $msg",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.size(8.dp))
            Button(
                onClick = viewModel::save,
                enabled = !state.saving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.saving) "保存中…" else "保存")
            }
            Spacer(Modifier.size(40.dp))
        }
    }
}

/** Open the system "All files access" settings page for this app (folder redirection 整盘共享). */
private fun requestAllFilesAccess(context: android.content.Context) {
    runCatching {
        context.startActivity(
            android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                android.net.Uri.parse("package:${context.packageName}"),
            ),
        )
    }.onFailure {
        // Some ROMs reject the per-app intent — fall back to the generic all-files-access list.
        val action = android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
        runCatching { context.startActivity(android.content.Intent(action)) }
    }
}

@Composable
private fun SectionTitle(text: String, icon: ImageVector) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** A row label with a leading muted icon — used for the chip-row option labels. */
@Composable
private fun RowLabel(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** Fixed frame-rate presets shown as chips; 0 = 自动 (follow the device screen refresh rate). */
private val frameRateOptions = listOf(0, 30, 60, 120)

/** Default input-mode chips: value (entity.defaultInputMode) → label. 0 = TRACKPAD, 1 = TOUCH. */
private val inputModeOptions = listOf(0 to "模拟鼠标", 1 to "直接触屏")

/** Dynamic-resolution max-cap chips: short-edge px cap (entity.dynamicResMax) → label. 0 = 跟随设备. */
private val dynamicResMaxOptions = listOf(0 to "跟随设备", 720 to "720p", 1080 to "1080p", 1440 to "1440p")

/** H.264 codec-tier chips: preferAvc420 flag → label. false = 画质优先 (AVC444), true = 流畅优先 (AVC420). */
private val codecTierOptions = listOf(false to "画质优先", true to "流畅优先")

/**
 * 远程音频路由 chips: value (entity.soundMode) → label。
 * 1 = 控制端播放 (/audio-mode:redirect), 2 = 被控端播放 (/audio-mode:server), 0 = 停用 (/audio-mode:none)。
 */
private val soundModeOptions = listOf(1 to "控制端播放", 2 to "被控端播放", 0 to "停用音频")
