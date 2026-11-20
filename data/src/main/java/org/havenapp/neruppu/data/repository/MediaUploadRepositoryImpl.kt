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

package org.havenapp.neruppu.data.repository

import android.util.Log
import org.havenapp.neruppu.data.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.havenapp.neruppu.data.worker.MediaUploadWorker
import org.havenapp.neruppu.domain.di.MatrixTransport
import org.havenapp.neruppu.domain.di.TelegramTransport
import org.havenapp.neruppu.domain.model.AlertPayload
import org.havenapp.neruppu.domain.model.AlertTarget
import org.havenapp.neruppu.domain.model.Event
import org.havenapp.neruppu.domain.model.MediaFile
import org.havenapp.neruppu.domain.model.UploadStatus
import org.havenapp.neruppu.domain.repository.AlertTargetRepository
import org.havenapp.neruppu.domain.repository.MediaUploadRepository
import org.havenapp.neruppu.domain.repository.SensorRepository
import org.havenapp.neruppu.domain.transport.AlertTransport
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaUploadRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: android.content.Context,
    private val sensorRepository: SensorRepository,
    private val alertTargetRepository: AlertTargetRepository,
    @TelegramTransport private val telegramTransport: AlertTransport,
    @MatrixTransport private val matrixTransport: AlertTransport
) : MediaUploadRepository {

    override suspend fun enqueueUpload(eventId: Long) {
        withContext(Dispatchers.IO) {
            MediaUploadWorker.enqueueUpload(context, eventId)
        }
        if (BuildConfig.DEBUG) {
            Log.d("MediaUploadRepository", "Enqueued upload for event $eventId")
        }
    }

    override suspend fun getPendingUploadEvents(limit: Int): List<Event> = withContext(Dispatchers.IO) {
        sensorRepository.getPendingUploadEvents(limit)
    }

    override suspend fun uploadPendingEvents(): Boolean = withContext(Dispatchers.IO) {
        val target = alertTargetRepository.activeTarget
        val transport = when (target) {
            AlertTarget.TELEGRAM -> telegramTransport
            AlertTarget.MATRIX -> matrixTransport
            AlertTarget.NONE -> return@withContext true
        }

        var hasFailure = false
        val seen = mutableSetOf<Long>()
        // Keep draining until no unseen pending events remain, so events that
        // arrive while this worker runs are not left waiting for the next trigger.
        repeat(MAX_BATCHES) {
            val events = sensorRepository.getPendingUploadEvents(limit = BATCH_SIZE)
                .filter { it.id !in seen }
            if (events.isEmpty()) return@withContext !hasFailure
            events.forEach { event ->
                seen += event.id
                val failure = deliver(event, transport)
                if (failure == null) {
                    sensorRepository.updateEventUploadStatus(
                        eventId = event.id,
                        status = UploadStatus.UPLOADED,
                        target = target.name,
                        uploadedAt = System.currentTimeMillis(),
                        failureReason = null
                    )
                } else {
                    hasFailure = true
                    sensorRepository.updateEventUploadStatus(
                        eventId = event.id,
                        status = UploadStatus.FAILED,
                        target = target.name,
                        uploadedAt = null,
                        failureReason = failure
                    )
                }
            }
        }
        !hasFailure
    }

    /**
     * Sends everything for one event. Returns null on success or a failure
     * reason. Local media is never deleted here: it is the on-device evidence,
     * and keeping it means a retry can always resend the whole event.
     */
    private suspend fun deliver(event: Event, transport: AlertTransport): String? {
        val mediaPaths = listOfNotNull(event.mediaUri, event.audioUri)
        val available = mediaPaths.filter { File(it).exists() }
        val missing = mediaPaths.size - available.size

        available.forEach { path ->
            val mimeType = guessMimeType(path)
            val kind = if (mimeType.startsWith("image/")) "Image" else "Audio"
            val payload = AlertPayload.MediaAlert(
                sensorType = event.sensorType,
                message = event.description,
                mediaFile = MediaFile(
                    absolutePath = path,
                    mimeType = mimeType,
                    sizeBytes = File(path).length(),
                    timestamp = event.timestamp.toEpochMilli()
                ),
                timestamp = event.timestamp.toEpochMilli()
            )
            val result = transport.send(payload)
            if (result.isFailure) {
                return "$kind upload failed: ${errorMessage(result.exceptionOrNull())}"
            }
        }

        if (available.isEmpty()) {
            // No media to attach (photos off, capture failed, or files gone):
            // still deliver the alert as text, and check that it went out.
            val note = if (missing > 0) " (media unavailable on device)" else ""
            val payload = AlertPayload.TextAlert(
                sensorType = event.sensorType,
                message = event.description + note,
                timestamp = event.timestamp.toEpochMilli()
            )
            val result = transport.send(payload)
            if (result.isFailure) {
                return "Text alert failed: ${errorMessage(result.exceptionOrNull())}"
            }
        }
        return null
    }

    /** Never persist request URLs: Telegram puts the bot token in the path. */
    private fun errorMessage(error: Throwable?): String {
        val message = error?.message ?: return "Unknown error"
        return message.replace(Regex("https?://\\S+"), "<url>")
    }

    private fun guessMimeType(path: String): String {
        return when {
            path.endsWith(".jpg", ignoreCase = true) || path.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
            path.endsWith(".png", ignoreCase = true) -> "image/png"
            path.endsWith(".mp4", ignoreCase = true) -> "audio/mp4"
            path.endsWith(".m4a", ignoreCase = true) -> "audio/mp4"
            else -> "application/octet-stream"
        }
    }

    companion object {
        private const val BATCH_SIZE = 50
        private const val MAX_BATCHES = 10
    }
}
