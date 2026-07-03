package com.hanfengruyue.pocketrdp.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hanfengruyue.pocketrdp.R
import com.hanfengruyue.pocketrdp.core.data.preferences.LANGUAGE_SYSTEM
import com.hanfengruyue.pocketrdp.core.data.preferences.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onClose: () -> Unit,
    onOpenKeepAlive: () -> Unit,
    onOpenLogs: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
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
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SettingsEntry(
                icon = Icons.Default.BatterySaver,
                title = stringResource(R.string.settings_keepalive_title),
                subtitle = stringResource(R.string.settings_keepalive_subtitle),
                onClick = onOpenKeepAlive,
            )
            SettingsEntry(
                icon = Icons.AutoMirrored.Filled.ReceiptLong,
                title = stringResource(R.string.settings_logs_title),
                subtitle = stringResource(R.string.settings_logs_subtitle),
                onClick = onOpenLogs,
            )

            SettingCard(title = stringResource(R.string.settings_theme_title), icon = Icons.Default.DarkMode) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        AssistChip(
                            onClick = { viewModel.setThemeMode(mode) },
                            label = { Text(stringResource(themeLabelRes(mode))) },
                            enabled = prefs.themeMode != mode,
                        )
                    }
                }
            }

            SettingCard(title = stringResource(R.string.settings_language_title), icon = Icons.Default.Language) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    languageOptions.take(4).forEach { option ->
                        AssistChip(
                            onClick = { viewModel.setLanguageTag(option.tag) },
                            label = { Text(stringResource(option.labelRes)) },
                            enabled = prefs.languageTag != option.tag,
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    languageOptions.drop(4).forEach { option ->
                        AssistChip(
                            onClick = { viewModel.setLanguageTag(option.tag) },
                            label = { Text(stringResource(option.labelRes)) },
                            enabled = prefs.languageTag != option.tag,
                        )
                    }
                }
            }

            SettingCard(title = stringResource(R.string.settings_chrome_alpha_title), icon = Icons.Default.Opacity) {
                AlphaSlider(
                    title = stringResource(R.string.settings_toolbar_alpha_title),
                    value = prefs.toolbarAlpha,
                    onChange = viewModel::setToolbarAlpha,
                )
                AlphaSlider(
                    title = stringResource(R.string.settings_controls_alpha_title),
                    value = prefs.controlAlpha,
                    onChange = viewModel::setControlAlpha,
                )
            }
        }
    }
}

@Composable
private fun SettingsEntry(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        ListItem(
            leadingContent = { Icon(icon, contentDescription = null) },
            headlineContent = { Text(title) },
            supportingContent = { Text(subtitle) },
        )
    }
}

@Composable
private fun SettingCard(title: String, icon: ImageVector, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, contentDescription = null)
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}

@Composable
private fun AlphaSlider(title: String, value: Float, onChange: (Float) -> Unit) {
    Text(stringResource(R.string.settings_alpha_percent, title, (value * 100).toInt()))
    Slider(value = value, onValueChange = onChange, valueRange = 0.35f..1f)
}

@StringRes
private fun themeLabelRes(mode: ThemeMode): Int = when (mode) {
    ThemeMode.SYSTEM -> R.string.settings_theme_system
    ThemeMode.LIGHT -> R.string.settings_theme_light
    ThemeMode.DARK -> R.string.settings_theme_dark
}

private data class LanguageOption(val tag: String, @StringRes val labelRes: Int)

private val languageOptions = listOf(
    LanguageOption(LANGUAGE_SYSTEM, R.string.settings_language_system),
    LanguageOption("zh-CN", R.string.settings_language_zh_cn),
    LanguageOption("en", R.string.settings_language_en),
    LanguageOption("es", R.string.settings_language_es),
    LanguageOption("fr", R.string.settings_language_fr),
    LanguageOption("de", R.string.settings_language_de),
    LanguageOption("ja", R.string.settings_language_ja),
    LanguageOption("ko", R.string.settings_language_ko),
    LanguageOption("pt-BR", R.string.settings_language_pt_br),
)
