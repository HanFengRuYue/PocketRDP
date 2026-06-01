package com.hanfengruyue.pocketrdp.keepalive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * In-app guide for keeping the remote-desktop connection alive in the background, focused on the
 * OEM allow-listing that AOSP code cannot do for the user (see [OemKeepAlive]). Reached from the
 * connection-list top bar, and auto-suggested after a background process-kill is detected.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeepAliveGuideScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val oem = OemKeepAlive.detect()
    val ignoringBattery = OemKeepAlive.isIgnoringBatteryOptimizations(context)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("后台保活设置") },
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "为什么切到后台会断开？",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "PocketRDP 已经用前台服务在后台维持连接。但系统省电策略——尤其是国产定制系统——可能仍会" +
                    "在你切换应用或锁屏后清理后台进程，导致连接中断。按下面两步把它加入白名单，可大幅提升后台" +
                    "保活成功率。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            GuideCard(
                title = "第 1 步 · 电池优化白名单",
                body = if (ignoringBattery) {
                    "✓ 已加入电池优化白名单。系统在省电 / 息屏时不会限制本应用的网络与后台运行。"
                } else {
                    "尚未加入白名单。点击下方按钮，在系统弹窗中选择「允许」，让本应用在息屏 / 深度省电（Doze）时" +
                        "也能保持网络连接。"
                },
            ) {
                if (!ignoringBattery) {
                    Button(onClick = { OemKeepAlive.requestIgnoreBatteryOptimizations(context) }) {
                        Text("加入电池优化白名单")
                    }
                }
            }

            GuideCard(
                title = "第 2 步 · ${OemKeepAlive.displayName(oem)} 自启动 / 后台",
                body = "在系统设置里为 PocketRDP 打开自启动并允许后台运行，建议步骤：\n\n" +
                    OemKeepAlive.steps(oem).mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n"),
            ) {
                Button(onClick = { OemKeepAlive.openAutostartSettings(context) }) {
                    Text("打开自启动 / 后台设置")
                }
                OutlinedButton(onClick = { OemKeepAlive.openAppDetailsSettings(context) }) {
                    Text("打开应用信息页")
                }
            }

            Text(
                "提示：不同系统版本的菜单位置可能略有差异；若上面的按钮没有直接跳到对应页面，请用「打开应用信息页」" +
                    "手动进入设置。部分系统在系统更新后会重置这些开关，断连后可回到此页重新检查。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GuideCard(
    title: String,
    body: String,
    actions: @Composable () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(body, style = MaterialTheme.typography.bodyMedium)
            actions()
        }
    }
}
