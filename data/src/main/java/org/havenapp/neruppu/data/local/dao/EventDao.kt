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

package org.havenapp.neruppu.data.local.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.havenapp.neruppu.data.local.entity.EventEntity

/** Media paths of one event row, used when clearing events. */
data class EventMediaPaths(
    val mediaUri: String?,
    val audioUri: String?
)

@Dao
interface EventDao {
    @Query("""
        SELECT * FROM events WHERE (
            :filter = 'All'
            OR (:filter = 'Motion' AND sensorType IN ('CAMERA_MOTION', 'ACCELEROMETER'))
            OR (:filter = 'Sound'  AND sensorType = 'MICROPHONE')
            OR (:filter = 'Light'  AND sensorType = 'LIGHT')
            OR sensorType = :filter
        ) ORDER BY timestamp DESC
    """)
    fun getEventsPaging(filter: String): PagingSource<Int, EventEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEvent(event: EventEntity): Long

    @Query("SELECT * FROM events WHERE id = :eventId")
    suspend fun getEventById(eventId: Long): EventEntity?

    @androidx.room.Update
    suspend fun updateEvent(event: EventEntity)

    @Query("SELECT * FROM events")
    suspend fun getAllEvents(): List<EventEntity>

    @Query("SELECT * FROM events LIMIT :pageSize OFFSET :offset")
    suspend fun getEventsPage(offset: Int, pageSize: Int): List<EventEntity>

    @Query("DELETE FROM events")
    suspend fun clearEvents()

    /** Newest first, so a fresh intrusion is never queued behind old failures. */
    @Query("""
        SELECT * FROM events
        WHERE uploadStatusValue IN (1, 3) AND uploadAttempts < :maxAttempts
        ORDER BY timestamp DESC LIMIT :limit
    """)
    suspend fun getPendingUploadEvents(limit: Int, maxAttempts: Int): List<EventEntity>

    @Query("UPDATE events SET audioUri = :audioUri WHERE id = :eventId")
    suspend fun setAudioUri(eventId: Long, audioUri: String): Int

    @Query("""
        UPDATE events SET uploadStatusValue = 2, uploadTarget = :target,
            uploadedAt = :uploadedAt, failureReason = NULL
        WHERE id = :eventId
    """)
    suspend fun markUploaded(eventId: Long, target: String?, uploadedAt: Long?): Int

    @Query("""
        UPDATE events SET uploadStatusValue = 3, uploadTarget = :target,
            uploadedAt = NULL, failureReason = :reason, uploadAttempts = uploadAttempts + 1
        WHERE id = :eventId
    """)
    suspend fun markUploadFailed(eventId: Long, target: String?, reason: String?): Int

    @Query("""
        UPDATE events SET uploadStatusValue = 1, uploadTarget = :target, failureReason = NULL
        WHERE id = :eventId
    """)
    suspend fun markUploadPending(eventId: Long, target: String?): Int

    @Query("SELECT mediaUri, audioUri FROM events")
    suspend fun getAllMediaPaths(): List<EventMediaPaths>

    @Query("SELECT * FROM events ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastEventEntity(): EventEntity?

    suspend fun getLastEventTimestamp(): Long? {
        return getLastEventEntity()?.timestamp?.toEpochMilli()
    }

    companion object {
        const val MAX_UPLOAD_ATTEMPTS = 5
    }
}
