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

package org.havenapp.neruppu.data.worker

import android.content.Context
import android.util.Log
import org.havenapp.neruppu.data.BuildConfig
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.CoroutineWorker
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import org.havenapp.neruppu.domain.repository.MediaUploadRepository
import java.util.concurrent.TimeUnit

@EntryPoint
@InstallIn(SingletonComponent::class)
interface MediaUploadRepositoryEntryPoint {
    fun mediaUploadRepository(): MediaUploadRepository
}

class MediaUploadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repository = EntryPointAccessors.fromApplication(
            applicationContext,
            MediaUploadRepositoryEntryPoint::class.java
        ).mediaUploadRepository()

        return try {
            if (repository.uploadPendingEvents()) {
                Result.success()
            } else {
                Result.retry()
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.w("MediaUploadWorker", "Upload worker failed; retrying", e)
            }
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "media_upload"
        private const val KEY_EVENT_ID = "event_id"

        fun enqueueUpload(context: Context, eventId: Long) {
            val data = androidx.work.Data.Builder()
                .putLong(KEY_EVENT_ID, eventId)
                .build()

            val request = OneTimeWorkRequestBuilder<MediaUploadWorker>()
                .setInputData(data)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()

            val workManager = WorkManager.getInstance(context)
            // A running worker drains everything pending, but this event may land
            // after its last read, so append one follow-up run behind it. A worker
            // that is only waiting (backoff after a failure, or no network yet) is
            // replaced, so a new intrusion alert never sits behind an old backoff.
            // Blocks briefly on the WorkManager database: call off the main thread.
            val running = runCatching {
                workManager.getWorkInfosForUniqueWork(UNIQUE_WORK_NAME).get()
                    .any { it.state == WorkInfo.State.RUNNING }
            }.getOrDefault(false)
            workManager.enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                if (running) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}