package com.hanfengruyue.pocketrdp.feature.connections.list

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hanfengruyue.pocketrdp.feature.connections.R

private val runningBadgeSizeDp = 62.dp
private val runningBadgeCoreDp = 42.dp
private val runningBadgeIconDp = 28.dp
private val runningBadgeIconPaddingDp = 7.dp
private const val RUNNING_PULSE_MS = 620
private const val RUNNING_HALO_ALPHA_MIN = 0.22f
private const val RUNNING_HALO_ALPHA_MAX = 0.82f
private const val RUNNING_HALO_SCALE_MIN = 0.82f
private const val RUNNING_HALO_SCALE_MAX = 1.18f
private const val RUNNING_CORE_SCALE_MIN = 0.94f
private const val RUNNING_CORE_SCALE_MAX = 1.08f
private const val RUNNING_BADGE_GREEN = 0xFF00A83B

@Composable
internal fun RunningBadge(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "running-session")
    val haloAlpha by transition.animateFloat(
        initialValue = RUNNING_HALO_ALPHA_MIN,
        targetValue = RUNNING_HALO_ALPHA_MAX,
        animationSpec = infiniteRepeatable(
            animation = tween(RUNNING_PULSE_MS),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "running-session-halo",
    )
    val haloScale by transition.animateFloat(
        initialValue = RUNNING_HALO_SCALE_MIN,
        targetValue = RUNNING_HALO_SCALE_MAX,
        animationSpec = infiniteRepeatable(
            animation = tween(RUNNING_PULSE_MS),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "running-session-halo-scale",
    )
    val coreScale by transition.animateFloat(
        initialValue = RUNNING_CORE_SCALE_MIN,
        targetValue = RUNNING_CORE_SCALE_MAX,
        animationSpec = infiniteRepeatable(
            animation = tween(RUNNING_PULSE_MS),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "running-session-core-scale",
    )
    Box(modifier = modifier.size(runningBadgeSizeDp), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = haloScale
                    scaleY = haloScale
                },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary.copy(alpha = haloAlpha),
            contentColor = Color.White,
        ) {}
        Surface(
            modifier = Modifier
                .size(runningBadgeCoreDp)
                .graphicsLayer {
                    scaleX = coreScale
                    scaleY = coreScale
                },
            shape = CircleShape,
            color = Color(RUNNING_BADGE_GREEN),
            contentColor = Color.White,
        ) {
            Icon(
                imageVector = Icons.Default.SettingsEthernet,
                contentDescription = stringResource(R.string.connection_running),
                modifier = Modifier.padding(runningBadgeIconPaddingDp).size(runningBadgeIconDp),
            )
        }
    }
}
