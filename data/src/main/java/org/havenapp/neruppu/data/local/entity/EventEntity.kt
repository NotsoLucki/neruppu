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

package org.havenapp.neruppu.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import org.havenapp.neruppu.domain.model.Event
import org.havenapp.neruppu.domain.model.SensorType
import org.havenapp.neruppu.domain.model.UploadStatus
import java.time.Instant

@Entity(
    tableName = "events",
    // Name matches the index MIGRATION_2_3 creates, so migrated and fresh
    // databases have the same schema and Room's validation passes.
    indices = [Index(value = ["uploadStatusValue"], name = "index_events_upload_status")]
)
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Instant,
    val sensorType: SensorType,
    val description: String,
    val mediaUri: String?,
    val audioUri: String? = null,
    val uploadStatusValue: Int = 1,
    val uploadTarget: String? = null,
    val uploadedAt: Long? = null,
    val failureReason: String? = null,
    /** Failed delivery attempts; rows at the cap stop being retried. */
    @ColumnInfo(defaultValue = "0") val uploadAttempts: Int = 0
) {
    val uploadStatus: UploadStatus get() = when (uploadStatusValue) {
        1 -> UploadStatus.PENDING
        2 -> UploadStatus.UPLOADED
        3 -> UploadStatus.FAILED
        else -> UploadStatus.PENDING
    }
}

fun EventEntity.toDomain(): Event = Event(
    id = id,
    timestamp = timestamp,
    sensorType = sensorType,
    description = description,
    mediaUri = mediaUri,
    audioUri = audioUri,
    uploadStatus = uploadStatus,
    uploadTarget = uploadTarget,
    uploadedAt = uploadedAt,
    failureReason = failureReason
)

fun Event.toEntity(): EventEntity = EventEntity(
    id = id,
    timestamp = timestamp,
    sensorType = sensorType,
    description = description,
    mediaUri = mediaUri,
    audioUri = audioUri,
    uploadStatusValue = when (uploadStatus) {
        UploadStatus.PENDING -> 1
        UploadStatus.UPLOADED -> 2
        UploadStatus.FAILED -> 3
    },
    uploadTarget = uploadTarget,
    uploadedAt = uploadedAt,
    failureReason = failureReason
)
