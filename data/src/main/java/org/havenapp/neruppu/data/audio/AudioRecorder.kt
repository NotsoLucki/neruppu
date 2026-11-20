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

package org.havenapp.neruppu.data.audio

import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class AudioRecorder(private val context: Context) {

    private var mediaRecorder: MediaRecorder? = null
    private var currentOutputFile: File? = null

    @Suppress("DEPRECATION")
    private fun createMediaRecorder(): MediaRecorder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }
    }

    suspend fun startRecording(): File? = withContext(Dispatchers.IO) {
        val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
            .format(System.currentTimeMillis())
        val outputDir = File(context.filesDir, "audio_captures").apply { mkdirs() }
        val file = File(outputDir, "$name.mp4")
        // Assign before configuring, so a failure in prepare()/start() can
        // still release the native recorder (which holds the microphone).
        val recorder = createMediaRecorder()
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setOutputFile(file.absolutePath)
            recorder.prepare()
            recorder.start()
            mediaRecorder = recorder
            currentOutputFile = file
            Log.d("AudioRecorder", "Started recording to ${file.absolutePath}")
            file
        } catch (e: Exception) {
            Log.e("AudioRecorder", "Failed to start recording", e)
            runCatching { recorder.release() }
            file.delete()
            null
        }
    }

    fun getMaxAmplitude(): Int {
        return try {
            mediaRecorder?.maxAmplitude ?: 0
        } catch (e: Exception) {
            0
        }
    }

    suspend fun stopRecording(): Uri? = withContext(Dispatchers.IO) {
        val recorder = mediaRecorder ?: return@withContext null
        val file = currentOutputFile
        mediaRecorder = null
        currentOutputFile = null
        var stopped = false
        try {
            // stop() throws if no valid audio was captured (very short clip,
            // or the mic was busy). release() must run either way.
            recorder.stop()
            stopped = true
            Log.d("AudioRecorder", "Stopped recording")
        } catch (e: Exception) {
            Log.e("AudioRecorder", "Failed to stop recording", e)
        } finally {
            runCatching { recorder.release() }
        }
        if (stopped) {
            file?.let { Uri.fromFile(it) }
        } else {
            file?.delete()
            null
        }
    }
}
