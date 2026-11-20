/*
 * Copyright (C) 2026 thamizh-root
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.havenapp.neruppu.ui.features.gallery

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import coil.compose.rememberAsyncImagePainter
import org.havenapp.neruppu.core.ui.theme.BackgroundPrimary
import org.havenapp.neruppu.core.ui.theme.BackgroundSecondary
import org.havenapp.neruppu.core.ui.theme.NeruppuOrange
import org.havenapp.neruppu.core.ui.theme.TextPrimary
import org.havenapp.neruppu.core.ui.theme.TextSecondary
import org.havenapp.neruppu.domain.model.Event
import org.havenapp.neruppu.domain.model.SensorType
import org.havenapp.neruppu.ui.components.LocalMediaLock
import org.havenapp.neruppu.ui.components.LockedMediaScreen
import org.havenapp.neruppu.ui.components.ScreenHeader
import org.havenapp.neruppu.ui.features.logs.AudioPlayer
import java.io.File
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val tileTimeFormatter = DateTimeFormatter.ofPattern("MMM d, h:mm a").withZone(ZoneId.systemDefault())
private val viewerTimeFormatter = DateTimeFormatter.ofPattern("EEE, MMM d yyyy, h:mm:ss a").withZone(ZoneId.systemDefault())

/** Local file for a stored media path, or null if it's missing on disk. */
private fun existingFile(uri: String?): File? =
    uri?.let { File(Uri.parse(it).path ?: it) }?.takeIf { it.exists() }

private fun eventTitle(event: Event): String = when (event.sensorType) {
    SensorType.CAMERA_MOTION -> "Motion detected"
    SensorType.ACCELEROMETER -> "Device moved"
    SensorType.MICROPHONE -> "Loud noise burst"
    SensorType.LIGHT -> "Light change"
    else -> event.sensorType.name.lowercase().replaceFirstChar { it.uppercase() }
}

/**
 * Grid of every event that has a photo or audio clip, with a full-screen
 * viewer. Nothing is shown until the user unlocks with biometrics or their
 * device credential (see [LocalMediaLock]).
 */
@Composable
fun GalleryScreen(viewModel: GalleryViewModel = hiltViewModel()) {
    val lock = LocalMediaLock.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundPrimary)
    ) {
        ScreenHeader(title = "Recordings")
        if (!lock.isUnlocked) {
            LockedMediaScreen(onUnlock = lock.requestUnlock, modifier = Modifier.fillMaxSize())
        } else {
            GalleryGrid(viewModel.media.collectAsLazyPagingItems())
        }
    }
}

@Composable
private fun GalleryGrid(items: LazyPagingItems<Event>) {
    var viewerIndex by rememberSaveable { mutableStateOf<Int?>(null) }

    when {
        items.loadState.refresh is LoadState.Loading && items.itemCount == 0 -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = NeruppuOrange)
            }
        }
        items.itemCount == 0 -> EmptyGallery()
        else -> {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 110.dp),
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(count = items.itemCount, key = items.itemKey { it.id }) { index ->
                    items[index]?.let { event ->
                        MediaTile(event = event, onClick = { viewerIndex = index })
                    }
                }
            }
        }
    }

    viewerIndex?.takeIf { it < items.itemCount }?.let { start ->
        MediaViewer(items = items, startIndex = start, onClose = { viewerIndex = null })
    }
}

@Composable
private fun EmptyGallery() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
    ) {
        Icon(Icons.Default.PhotoLibrary, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(48.dp))
        Text("No recordings yet", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
        Text(
            "Photos and audio clips captured while guarding will appear here.",
            color = TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun MediaTile(event: Event, onClick: () -> Unit) {
    val photo = remember(event.mediaUri) { existingFile(event.mediaUri) }
    val hasAudio = remember(event.audioUri) { existingFile(event.audioUri) != null }
    val time = tileTimeFormatter.format(event.timestamp)
    val kind = buildString {
        append(if (photo != null) "Photo" else if (hasAudio) "Audio clip" else "Missing recording")
        if (photo != null && hasAudio) append(" with audio")
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(BackgroundSecondary)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = "$kind, ${eventTitle(event)}, $time"
            }
    ) {
        when {
            photo != null -> Image(
                painter = rememberAsyncImagePainter(photo),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            hasAudio -> Icon(
                Icons.Default.Mic,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier
                    .size(36.dp)
                    .align(Alignment.Center)
            )
            else -> Icon(
                Icons.Default.BrokenImage,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier
                    .size(32.dp)
                    .align(Alignment.Center)
            )
        }

        // Bottom scrim with time and an audio badge.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))))
                .padding(horizontal = 6.dp, vertical = 4.dp)
        ) {
            Text(
                time,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            if (photo != null && hasAudio) {
                Icon(Icons.Default.Mic, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
private fun MediaViewer(items: LazyPagingItems<Event>, startIndex: Int, onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            // A dialog is its own window, so the activity's FLAG_SECURE doesn't
            // cover it: block screenshots and recents thumbnails here too.
            securePolicy = SecureFlagPolicy.SecureOn
        )
    ) {
        Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
            val pagerState = rememberPagerState(initialPage = startIndex) { items.itemCount }
            var zoomed by remember { mutableStateOf(false) }

            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                HorizontalPager(
                    state = pagerState,
                    // Swiping between items is off while zoomed in, so a pan moves the photo.
                    userScrollEnabled = !zoomed,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    items[page]?.let { event ->
                        ViewerPage(
                            event = event,
                            isCurrent = page == pagerState.currentPage,
                            onZoomChange = { if (page == pagerState.currentPage) zoomed = it }
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(4.dp)
                ) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${pagerState.currentPage + 1} / ${items.itemCount}",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(end = 12.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ViewerPage(event: Event, isCurrent: Boolean, onZoomChange: (Boolean) -> Unit) {
    val photo = remember(event.mediaUri) { existingFile(event.mediaUri) }
    val audio = remember(event.audioUri) { existingFile(event.audioUri) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 56.dp)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            when {
                photo != null -> ZoomableImage(photo, onZoomChange)
                audio != null -> Icon(Icons.Default.Mic, contentDescription = null, tint = Color.White, modifier = Modifier.size(72.dp))
                else -> Text(
                    "This recording is no longer on the device.",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                eventTitle(event),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(viewerTimeFormatter.format(event.timestamp), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyMedium)
            Text(event.description, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
            if (audio != null && event.audioUri != null) {
                Spacer(Modifier.height(8.dp))
                // Only the visible page gets a player, so neighbouring pages
                // don't each prepare a MediaPlayer.
                if (isCurrent) AudioPlayer(uriString = event.audioUri!!)
            }
        }
    }
}

/**
 * Pinch to zoom, pan while zoomed, double-tap to toggle. One-finger drags at
 * normal size are left unconsumed so the pager can still swipe.
 */
@Composable
private fun ZoomableImage(file: File, onZoomChange: (Boolean) -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    fun setScale(value: Float) {
        scale = value.coerceIn(1f, 5f)
        if (scale == 1f) offset = Offset.Zero
        onZoomChange(scale > 1f)
    }

    Image(
        painter = rememberAsyncImagePainter(file),
        contentDescription = "Captured photo",
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { setScale(if (scale > 1f) 1f else 2.5f) })
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val multiTouch = event.changes.size > 1
                        if (multiTouch || scale > 1f) {
                            setScale(scale * event.calculateZoom())
                            if (scale > 1f) offset += event.calculatePan()
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offset.x,
                translationY = offset.y
            )
    )
}
