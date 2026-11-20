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

package org.havenapp.neruppu.domain.usecase

import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.havenapp.neruppu.domain.model.*
import org.havenapp.neruppu.domain.repository.MediaStorageRepository
import org.havenapp.neruppu.domain.repository.SensorRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Alert delivery moved out of this use case into the upload worker, so these
 * tests cover what it does now: save media locally and log the event.
 * (Updated from the earlier transport-based tests, which no longer compiled.)
 */
class HandleSensorEventUseCaseTest {

    private val mediaStorage = mockk<MediaStorageRepository>()
    private val sensorRepository = mockk<SensorRepository>()

    private lateinit var useCase: HandleSensorEventUseCase

    @Before
    fun setup() {
        useCase = HandleSensorEventUseCase(mediaStorage, sensorRepository)
        coEvery { sensorRepository.saveEvent(any()) } returns 1L
    }

    @Test
    fun `TC-ORCH-01 Image is saved locally if present`() = runBlocking {
        val sensorEvent = SensorEvent(
            sensorType = SensorType.CAMERA_MOTION,
            description = "Motion",
            timestamp = 1000L,
            imageBytes = byteArrayOf(1, 2, 3)
        )
        coEvery { mediaStorage.saveImage(any(), any()) } returns Result.success(MediaFile("path", "image/jpeg", 3L, 1000L))

        useCase.execute(sensorEvent)

        coVerify { mediaStorage.saveImage(byteArrayOf(1, 2, 3), 1000L) }
        coVerify { sensorRepository.saveEvent(match { it.mediaUri == "path" }) }
    }

    @Test
    fun `TC-ORCH-02 Event is logged in database and its id returned`() = runBlocking {
        val result = useCase.execute(SensorEvent(SensorType.LIGHT, "Bright", 1000L))

        assertEquals(1L, result.getOrNull())
        coVerify { sensorRepository.saveEvent(match { it.sensorType == SensorType.LIGHT && it.mediaUri == null }) }
    }

    @Test
    fun `TC-ORCH-03 Image and audio are both attached`() = runBlocking {
        val sensorEvent = SensorEvent(
            sensorType = SensorType.MICROPHONE,
            description = "Noise",
            timestamp = 1000L,
            imageBytes = byteArrayOf(1),
            audioFile = java.io.File("test.mp4")
        )
        coEvery { mediaStorage.saveImage(any(), any()) } returns Result.success(MediaFile("img", "image/jpeg", 1, 1000))
        coEvery { mediaStorage.saveAudio(any(), any()) } returns Result.success(MediaFile("aud", "audio/mp4", 1, 1000))

        useCase.execute(sensorEvent)

        coVerify { sensorRepository.saveEvent(match { it.mediaUri == "img" && it.audioUri == "aud" }) }
    }

    @Test
    fun `TC-ORCH-04 Failed image save still logs the event`() = runBlocking {
        val sensorEvent = SensorEvent(SensorType.CAMERA_MOTION, "Motion", 1000L, imageBytes = byteArrayOf(1))
        coEvery { mediaStorage.saveImage(any(), any()) } returns Result.failure(Exception("disk full"))

        val result = useCase.execute(sensorEvent)

        assertTrue(result.isSuccess)
        coVerify { sensorRepository.saveEvent(match { it.mediaUri == null }) }
    }

    @Test
    fun `TC-ORCH-05 Database failure is reported`() = runBlocking {
        coEvery { sensorRepository.saveEvent(any()) } throws IllegalStateException("db closed")

        val result = useCase.execute(SensorEvent(SensorType.ACCELEROMETER, "Shake", 1000L))

        assertTrue(result.isFailure)
        assertNull(result.getOrNull())
    }
}
