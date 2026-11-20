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

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.room.withTransaction
import org.havenapp.neruppu.data.BuildConfig
import org.havenapp.neruppu.data.local.NeruppuDatabase
import org.havenapp.neruppu.data.local.dao.EventDao
import org.havenapp.neruppu.data.local.dao.EventMediaPaths
import org.havenapp.neruppu.data.local.entity.toDomain
import org.havenapp.neruppu.data.local.entity.toEntity
import org.havenapp.neruppu.domain.model.Event
import org.havenapp.neruppu.domain.model.UploadStatus
import org.havenapp.neruppu.domain.repository.SensorRepository
import java.io.File

class SensorRepositoryImpl(
    private val context: Context,
    private val eventDao: EventDao,
    private val database: NeruppuDatabase? = null,
) : SensorRepository {
    override fun getEvents(filter: String): Flow<PagingData<Event>> {
        return Pager(
            config = PagingConfig(
                pageSize = 20,
                enablePlaceholders = false
            ),
            pagingSourceFactory = { eventDao.getEventsPaging(filter) }
        ).flow.map { pagingData ->
            pagingData.map { it.toDomain() }
        }
    }

    override suspend fun saveEvent(event: Event): Long {
        val id = eventDao.insertEvent(event.toEntity())
        if (BuildConfig.DEBUG) {
            Log.d("SensorRepository", "Event saved: ${event.sensorType} with ID $id")
        }
        return id
    }

    override suspend fun updateEventAudio(eventId: Long, audioUri: String) {
        // Targeted UPDATE: a read-modify-write of the whole row could overwrite
        // an upload-status change made concurrently by the upload worker.
        val updated = eventDao.setAudioUri(eventId, audioUri)
        if (BuildConfig.DEBUG) {
            if (updated > 0) {
                Log.d("SensorRepository", "Audio attached to event $eventId: $audioUri")
            } else {
                Log.e("SensorRepository", "Failed to attach audio: Event $eventId not found!")
            }
        }
    }

    override suspend fun updateEventUploadStatus(eventId: Long, status: UploadStatus, target: String?, uploadedAt: Long?, failureReason: String?) {
        val updated = when (status) {
            UploadStatus.UPLOADED -> eventDao.markUploaded(eventId, target, uploadedAt)
            UploadStatus.FAILED -> eventDao.markUploadFailed(eventId, target, failureReason)
            UploadStatus.PENDING -> eventDao.markUploadPending(eventId, target)
        }
        if (BuildConfig.DEBUG) {
            if (updated > 0) {
                Log.d("SensorRepository", "Upload status updated for event $eventId: $status")
            } else {
                Log.e("SensorRepository", "Failed to update upload status: Event $eventId not found!")
            }
        }
    }

    override suspend fun getPendingUploadEvents(limit: Int): List<Event> {
        return eventDao.getPendingUploadEvents(limit, EventDao.MAX_UPLOAD_ATTEMPTS).map { it.toDomain() }
    }

    override suspend fun getLastEventTimestamp(): Long? {
        return eventDao.getLastEventTimestamp()
    }

    override suspend fun clearEvents(deleteFiles: Boolean) = withContext(Dispatchers.IO) {
        val clearStartedAt = System.currentTimeMillis()
        // Read the paths and delete the rows in one transaction, so an event
        // inserted while clearing is either fully cleared or fully kept.
        val paths: List<EventMediaPaths> = if (database != null) {
            database.withTransaction {
                val p = if (deleteFiles) eventDao.getAllMediaPaths() else emptyList()
                eventDao.clearEvents()
                p
            }
        } else {
            val p = if (deleteFiles) eventDao.getAllMediaPaths() else emptyList()
            eventDao.clearEvents()
            p
        }
        if (deleteFiles) {
            // Files are deleted only after the rows are gone.
            paths.forEach { row ->
                row.mediaUri?.let { deleteMedia(it) }
                row.audioUri?.let { deleteMedia(it) }
            }
            // Sweep orphans (e.g. raw camera captures from older versions)
            // that existed before the clear started.
            sweepOrphans(File(context.filesDir, "captures"), clearStartedAt)
            sweepOrphans(File(context.filesDir, "audio_captures"), clearStartedAt)
        }
    }

    private fun sweepOrphans(dir: File, olderThan: Long) {
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < olderThan) {
                runCatching { file.delete() }
            }
        }
    }

    private fun deleteMedia(uriString: String) {
        try {
            val uri = Uri.parse(uriString)
            if (uri.scheme == "content") {
                context.contentResolver.delete(uri, null, null)
            } else if (uri.scheme == "file" || uriString.startsWith("/")) {
                val path = if (uri.scheme == "file") uri.path else uriString
                path?.let { File(it).delete() }
            }
        } catch (e: Exception) {
            // Ignore deletion errors to ensure db clearing continues
        }
    }
}