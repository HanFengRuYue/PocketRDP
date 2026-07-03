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
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hanfengruyue.pocketrdp.R

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
    val oemName = stringResource(OemKeepAlive.displayNameRes(oem))
    val oemSteps = stringArrayResource(OemKeepAlive.stepsRes(oem))

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.keepalive_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
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
                stringResource(R.string.keepalive_question_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.keepalive_question_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            GuideCard(
                title = stringResource(R.string.keepalive_step1_title),
                body = if (ignoringBattery) {
                    stringResource(R.string.keepalive_battery_allowed)
                } else {
                    stringResource(R.string.keepalive_battery_not_allowed)
                },
            ) {
                if (!ignoringBattery) {
                    Button(onClick = { OemKeepAlive.requestIgnoreBatteryOptimizations(context) }) {
                        Text(stringResource(R.string.keepalive_battery_button))
                    }
                }
            }

            GuideCard(
                title = stringResource(R.string.keepalive_step2_title, oemName),
                body = stringResource(R.string.keepalive_step2_body) + "\n\n" +
                    oemSteps.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n"),
            ) {
                Button(onClick = { OemKeepAlive.openAutostartSettings(context) }) {
                    Text(stringResource(R.string.keepalive_open_autostart))
                }
                OutlinedButton(onClick = { OemKeepAlive.openAppDetailsSettings(context) }) {
                    Text(stringResource(R.string.keepalive_open_app_info))
                }
            }

            Text(
                stringResource(R.string.keepalive_tip),
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
