package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.LoadingIndicator

/** Only this small subtree observes decoded frames; the main player does not recompose for them. */
@Composable
internal fun SeekPreviewThumbnailHost(viewModel: PlayerViewModel, durationMs: Long) {
    val state by viewModel.seekPreviewState.collectAsState()
    val target = state.positionMs ?: return
    val image = remember(state.frame) { state.frame?.asImageBitmap() }
    val timestamp = formatTime(target)
    val progress = if (durationMs > 0) (target.toDouble() / durationMs).coerceIn(0.0, 1.0) else 0.0
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        val cardWidth = minOf(160.dp, maxWidth)
        val x = ((maxWidth * progress.toFloat()) - cardWidth / 2).coerceIn(0.dp, maxWidth - cardWidth)
        Column(
            modifier = Modifier
                .offset(x = x)
                .width(cardWidth)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = 0.9f)),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(modifier = Modifier.fillMaxWidth().height(90.dp), contentAlignment = Alignment.Center) {
                if (image != null) {
                    Image(
                        bitmap = image,
                        contentDescription = stringResource(R.string.cd_seek_preview, timestamp),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().height(90.dp)
                    )
                } else {
                    LoadingIndicator()
                }
            }
            Text(
                text = timestamp,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
    }
}
