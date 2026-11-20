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

import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.havenapp.neruppu.domain.model.AlertPayload
import org.havenapp.neruppu.domain.model.AlertTarget
import org.havenapp.neruppu.domain.model.Event
import org.havenapp.neruppu.domain.model.SensorType
import org.havenapp.neruppu.domain.model.UploadStatus
import org.havenapp.neruppu.domain.repository.AlertTargetRepository
import org.havenapp.neruppu.domain.repository.MediaUploadRepository
import org.havenapp.neruppu.domain.repository.SensorRepository
import org.havenapp.neruppu.domain.transport.AlertTransport
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MediaUploadRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var sensorRepository: SensorRepository
    private lateinit var alertTargetRepository: AlertTargetRepository
    private lateinit var telegramTransport: AlertTransport
    private lateinit var matrixTransport: AlertTransport
    private lateinit var mediaUploadRepository: MediaUploadRepository

    private lateinit var testFile: File

    @Before
    fun setup() {
        sensorRepository = mockk()
        alertTargetRepository = mockk(relaxed = true)
        telegramTransport = mockk()
        matrixTransport = mockk()
        mediaUploadRepository = MediaUploadRepositoryImpl(
            context = mockk(),
            sensorRepository = sensorRepository,
            alertTargetRepository = alertTargetRepository,
            telegramTransport = telegramTransport,
            matrixTransport = matrixTransport
        )
        testFile = tempFolder.newFile("test.jpg")
        testFile.writeBytes(byteArrayOf(1, 2, 3))
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `TC-MUP-01 No active target does not upload`() = runBlocking {
        every { alertTargetRepository.activeTarget } returns AlertTarget.NONE
        coEvery { sensorRepository.getPendingUploadEvents(any()) } returns emptyList()

        val result = mediaUploadRepository.uploadPendingEvents()

        assertTrue(result)
        coVerify(exactly = 0) { telegramTransport.send(any()) }
        coVerify(exactly = 0) { matrixTransport.send(any()) }
    }

    @Test
    fun `TC-MUP-02 Telegram success updates status and keeps local evidence`() = runBlocking {
        val event = Event(
            id = 1L,
            sensorType = SensorType.CAMERA_MOTION,
            description = "Motion detected",
            mediaUri = testFile.absolutePath,
            uploadStatus = UploadStatus.PENDING
        )
        every { alertTargetRepository.activeTarget } returns AlertTarget.TELEGRAM
        coEvery { sensorRepository.getPendingUploadEvents(any()) } returns listOf(event)
        val payloadSlot = slot<AlertPayload>()
        coEvery { telegramTransport.send(capture(payloadSlot)) } returns Result.success(Unit)

        coEvery { sensorRepository.updateEventUploadStatus(any(), any(), any(), any(), any()) } just Runs

        val result = mediaUploadRepository.uploadPendingEvents()

        assertTrue(result)
        coVerify { sensorRepository.updateEventUploadStatus(
            eventId = 1L,
            status = UploadStatus.UPLOADED,
            target = "TELEGRAM",
            uploadedAt = any(),
            failureReason = null
        ) }
        assertEquals(testFile.absolutePath, (payloadSlot.captured as AlertPayload.MediaAlert).mediaFile.absolutePath)
        assertTrue(testFile.exists())
    }

    @Test
    fun `TC-MUP-03 Telegram failure keeps file and marks failed`() = runBlocking {
        val event = Event(
            id = 1L,
            sensorType = SensorType.CAMERA_MOTION,
            description = "Motion detected",
            mediaUri = testFile.absolutePath,
            uploadStatus = UploadStatus.PENDING
        )
        every { alertTargetRepository.activeTarget } returns AlertTarget.TELEGRAM
        coEvery { sensorRepository.getPendingUploadEvents(any()) } returns listOf(event)
        coEvery { telegramTransport.send(any()) } returns Result.failure(RuntimeException("Network error"))

        coEvery { sensorRepository.updateEventUploadStatus(any(), any(), any(), any(), any()) } just Runs

        val result = mediaUploadRepository.uploadPendingEvents()

        assertFalse(result)
        coVerify { sensorRepository.updateEventUploadStatus(
            eventId = 1L,
            status = UploadStatus.FAILED,
            target = "TELEGRAM",
            uploadedAt = null,
            failureReason = "Image upload failed: Network error"
        ) }
        assertTrue(testFile.exists())
    }

    @Test
    fun `TC-MUP-04 Matrix success uploads via matrix transport`() = runBlocking {
        val event = Event(
            id = 1L,
            sensorType = SensorType.MICROPHONE,
            description = "Sound detected",
            audioUri = testFile.absolutePath,
            uploadStatus = UploadStatus.PENDING
        )
        every { alertTargetRepository.activeTarget } returns AlertTarget.MATRIX
        coEvery { sensorRepository.getPendingUploadEvents(any()) } returns listOf(event)
        coEvery { matrixTransport.send(any()) } returns Result.success(Unit)

        coEvery { sensorRepository.updateEventUploadStatus(any(), any(), any(), any(), any()) } just Runs

        val result = mediaUploadRepository.uploadPendingEvents()

        assertTrue(result)
        coVerify(exactly = 0) { telegramTransport.send(any()) }
        coVerify { sensorRepository.updateEventUploadStatus(
            eventId = 1L,
            status = UploadStatus.UPLOADED,
            target = "MATRIX",
            uploadedAt = any(),
            failureReason = null
        ) }
        assertTrue(testFile.exists())
    }

    @Test
    fun `TC-MUP-05 Text event without media is sent and marked uploaded`() = runBlocking {
        val event = Event(
            id = 1L,
            sensorType = SensorType.ACCELEROMETER,
            description = "Motion detected",
            mediaUri = null,
            audioUri = null,
            uploadStatus = UploadStatus.PENDING
        )
        every { alertTargetRepository.activeTarget } returns AlertTarget.TELEGRAM
        coEvery { sensorRepository.getPendingUploadEvents(any()) } returns listOf(event)
        coEvery { telegramTransport.send(any()) } returns Result.success(Unit)

        coEvery { sensorRepository.updateEventUploadStatus(any(), any(), any(), any(), any()) } just Runs

        val result = mediaUploadRepository.uploadPendingEvents()

        assertTrue(result)
        coVerify { sensorRepository.updateEventUploadStatus(
            eventId = 1L,
            status = UploadStatus.UPLOADED,
            target = "TELEGRAM",
            uploadedAt = any(),
            failureReason = null
        ) }
    }

    @Test
    fun `TC-MUP-06 Event with both image and audio uploads both media files`() = runBlocking {
        val imageFile = File(tempFolder.root, "neruppu_motion_123.jpg").apply { createNewFile() }
        imageFile.writeBytes(byteArrayOf(1, 2, 3))
        val audioFile = File(tempFolder.root, "neruppu_sound_123.mp4").apply { createNewFile() }
        audioFile.writeBytes(byteArrayOf(4, 5, 6))

        val event = Event(
            id = 1L,
            sensorType = SensorType.MICROPHONE,
            description = "Sound detected",
            mediaUri = imageFile.absolutePath,
            audioUri = audioFile.absolutePath,
            uploadStatus = UploadStatus.PENDING
        )
        every { alertTargetRepository.activeTarget } returns AlertTarget.TELEGRAM
        coEvery { sensorRepository.getPendingUploadEvents(any()) } returns listOf(event)
        coEvery { telegramTransport.send(any()) } returns Result.success(Unit)
        coEvery { sensorRepository.updateEventUploadStatus(any(), any(), any(), any(), any()) } just Runs

        val result = mediaUploadRepository.uploadPendingEvents()

        assertTrue(result)
        coVerify(exactly = 2) { telegramTransport.send(any()) }
        coVerify { sensorRepository.updateEventUploadStatus(
            eventId = 1L,
            status = UploadStatus.UPLOADED,
            target = "TELEGRAM",
            uploadedAt = any(),
            failureReason = null
        ) }
        assertTrue(imageFile.exists())
        assertTrue(audioFile.exists())
    }

    @Test
    fun `TC-MUP-07 Failed text alert is marked failed, not uploaded`() = runBlocking {
        val event = Event(
            id = 1L,
            sensorType = SensorType.ACCELEROMETER,
            description = "Motion detected",
            uploadStatus = UploadStatus.PENDING
        )
        every { alertTargetRepository.activeTarget } returns AlertTarget.TELEGRAM
        coEvery { sensorRepository.getPendingUploadEvents(any()) } returns listOf(event)
        coEvery { telegramTransport.send(any()) } returns Result.failure(RuntimeException("Network error"))
        coEvery { sensorRepository.updateEventUploadStatus(any(), any(), any(), any(), any()) } just Runs

        val result = mediaUploadRepository.uploadPendingEvents()

        assertFalse(result)
        coVerify { sensorRepository.updateEventUploadStatus(
            eventId = 1L,
            status = UploadStatus.FAILED,
            target = "TELEGRAM",
            uploadedAt = null,
            failureReason = "Text alert failed: Network error"
        ) }
        coVerify(exactly = 0) { sensorRepository.updateEventUploadStatus(any(), UploadStatus.UPLOADED, any(), any(), any()) }
    }

    @Test
    fun `TC-MUP-08 Image ok then audio fails, retry resends both instead of sticking`() = runBlocking {
        val imageFile = File(tempFolder.root, "neruppu_motion_9.jpg").apply { writeBytes(byteArrayOf(1)) }
        val audioFile = File(tempFolder.root, "neruppu_sound_9.mp4").apply { writeBytes(byteArrayOf(2)) }
        val event = Event(
            id = 9L,
            sensorType = SensorType.MICROPHONE,
            description = "Sound detected",
            mediaUri = imageFile.absolutePath,
            audioUri = audioFile.absolutePath,
            uploadStatus = UploadStatus.PENDING
        )
        every { alertTargetRepository.activeTarget } returns AlertTarget.TELEGRAM
        coEvery { sensorRepository.getPendingUploadEvents(any()) } returns listOf(event)
        coEvery { sensorRepository.updateEventUploadStatus(any(), any(), any(), any(), any()) } just Runs
        coEvery { telegramTransport.send(any()) } returnsMany listOf(
            Result.success(Unit),                         // run 1: image
            Result.failure(RuntimeException("timeout")),  // run 1: audio
            Result.success(Unit),                         // run 2: image again
            Result.success(Unit)                          // run 2: audio
        )

        assertFalse(mediaUploadRepository.uploadPendingEvents())
        coVerify { sensorRepository.updateEventUploadStatus(9L, UploadStatus.FAILED, "TELEGRAM", null, "Audio upload failed: timeout") }
        assertTrue(imageFile.exists())

        assertTrue(mediaUploadRepository.uploadPendingEvents())
        coVerify { sensorRepository.updateEventUploadStatus(9L, UploadStatus.UPLOADED, "TELEGRAM", any(), null) }
    }

    @Test
    fun `TC-MUP-09 Missing media falls back to a text alert`() = runBlocking {
        val event = Event(
            id = 3L,
            sensorType = SensorType.CAMERA_MOTION,
            description = "Motion detected",
            mediaUri = File(tempFolder.root, "gone.jpg").absolutePath,
            uploadStatus = UploadStatus.PENDING
        )
        every { alertTargetRepository.activeTarget } returns AlertTarget.MATRIX
        coEvery { sensorRepository.getPendingUploadEvents(any()) } returns listOf(event)
        val payloadSlot = slot<AlertPayload>()
        coEvery { matrixTransport.send(capture(payloadSlot)) } returns Result.success(Unit)
        coEvery { sensorRepository.updateEventUploadStatus(any(), any(), any(), any(), any()) } just Runs

        assertTrue(mediaUploadRepository.uploadPendingEvents())
        assertTrue(payloadSlot.captured is AlertPayload.TextAlert)
        coVerify { sensorRepository.updateEventUploadStatus(3L, UploadStatus.UPLOADED, "MATRIX", any(), null) }
    }

    @Test
    fun `TC-MUP-10 Bot token in an error URL is not persisted`() = runBlocking {
        val event = Event(id = 4L, sensorType = SensorType.LIGHT, description = "Light", uploadStatus = UploadStatus.PENDING)
        every { alertTargetRepository.activeTarget } returns AlertTarget.TELEGRAM
        coEvery { sensorRepository.getPendingUploadEvents(any()) } returns listOf(event)
        coEvery { telegramTransport.send(any()) } returns Result.failure(
            RuntimeException("Request timeout has expired [url=https://api.telegram.org/bot123:SECRET/sendMessage, timeout=60000]")
        )
        coEvery { sensorRepository.updateEventUploadStatus(any(), any(), any(), any(), any()) } just Runs

        mediaUploadRepository.uploadPendingEvents()

        coVerify { sensorRepository.updateEventUploadStatus(
            4L, UploadStatus.FAILED, "TELEGRAM", null,
            match { it != null && it.startsWith("Text alert failed") && !it.contains("SECRET") }
        ) }
    }
}