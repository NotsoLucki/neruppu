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

package org.havenapp.neruppu.data.camera

import android.content.Context
import android.net.Uri
import android.util.Log
import android.util.Size
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.havenapp.neruppu.data.camera.analyzer.MotionAnalyzer
import org.havenapp.neruppu.data.camera.analyzer.ContextBatteryLevelProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * Thread-safety: every change to camera state (bind, unbind, the fields below)
 * happens on the main thread, inside the camera-provider callback, in the order
 * the calls were made. That ordering is what keeps an unbind() followed quickly
 * by bindCamera() from shutting down the executor the new analyzer uses.
 * Fields read from other threads are @Volatile.
 */
class CameraManager(private val context: Context) {

    @Volatile private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    @Volatile private var motionAnalyzer: MotionAnalyzer? = null

    private val mutex = Mutex()
    // Created per bind, shut down on unbind. Only touched on the main thread.
    private var cameraExecutor: ExecutorService? = null

    @Volatile var isBound = false
    @Volatile var currentCameraSide: Boolean? = null
    @Volatile var currentLifecycleOwner: LifecycleOwner? = null

    @Volatile var sensitivityPref = 15f
    @Volatile private var onMotionDetectedCallback: ((Double) -> Unit)? = null

    fun getCameraProvider(callback: (ProcessCameraProvider) -> Unit) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                callback(cameraProviderFuture.get())
            } catch (e: Exception) {
                Log.e("CameraManager", "Error getting camera provider", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    suspend fun capturePhoto(): Uri? = mutex.withLock {
        val capture = imageCapture ?: run {
            Log.e("CameraManager", "Cannot capture: imageCapture is null. Camera might not be bound to service.")
            return null
        }
        
        return suspendCancellableCoroutine { continuation ->
            val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
                .format(System.currentTimeMillis())
            
            val outputDir = File(context.filesDir, "captures").apply { mkdirs() }
            val file = File(outputDir, "$name.jpg")
            val outputOptions = ImageCapture.OutputFileOptions.Builder(file).build()

            Log.d("CameraManager", "Taking picture for security event: ${file.name}")
            capture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        Log.i("CameraManager", "PHOTO SAVED: ${file.absolutePath}")
                        if (continuation.isActive) continuation.resume(Uri.fromFile(file))
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.e("CameraManager", "CAPTURE FAILED: ${exception.message}", exception)
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
            )
        }
    }

    fun getDifferenceMap(): Nothing? = null

    fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        useFrontCamera: Boolean,
        sensitivity: Int = 15,
        onMotionDetected: (Double) -> Unit
    ) {
        getCameraProvider { cameraProvider ->
            // Runs on the main thread, after any earlier bind/unbind callbacks.
            sensitivityPref = sensitivity.toFloat()
            onMotionDetectedCallback = onMotionDetected

            if (isBound && currentCameraSide == useFrontCamera && currentLifecycleOwner == lifecycleOwner) {
                // Same camera: apply the new sensitivity without a rebind.
                motionAnalyzer?.sensitivity = sensitivity
                Log.d("CameraManager", "Camera already bound; sensitivity now $sensitivity")
                return@getCameraProvider
            }

            try {
                cameraProvider.unbindAll()
                imageAnalysis?.clearAnalyzer()
                cameraExecutor?.shutdown()
                val executor = Executors.newSingleThreadExecutor()
                cameraExecutor = executor

                val useCases = mutableListOf<androidx.camera.core.UseCase>()

                val resolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(640, 480),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER
                        )
                    )
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .build()

                val analysisResolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(320, 240),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER
                        )
                    )
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .build()

                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setResolutionSelector(resolutionSelector)
                    .build()
                useCases.add(capture)

                val analyzer = MotionAnalyzer(
                    { level -> onMotionDetectedCallback?.invoke(level) },
                    sensitivity,
                    ContextBatteryLevelProvider(context)
                )
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(analysisResolutionSelector)
                    .build()
                    .also { it.setAnalyzer(executor, analyzer) }
                useCases.add(analysis)

                val cameraSelector = if (useFrontCamera) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }

                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    *useCases.toTypedArray()
                )

                imageCapture = capture
                imageAnalysis = analysis
                motionAnalyzer = analyzer
                isBound = true
                currentCameraSide = useFrontCamera
                currentLifecycleOwner = lifecycleOwner
                Log.d("CameraManager", "Camera bound to $lifecycleOwner")
            } catch (exc: Exception) {
                Log.e("CameraManager", "CRITICAL: Service-side camera binding failed", exc)
                isBound = false
            }
        }
    }


    fun unbind() {
        getCameraProvider { cameraProvider ->
            cameraProvider.unbindAll()
            isBound = false
            currentCameraSide = null
            currentLifecycleOwner = null
            imageCapture = null
            imageAnalysis?.clearAnalyzer()
            imageAnalysis = null
            motionAnalyzer?.cleanup()
            motionAnalyzer = null
            cameraExecutor?.shutdown()
            cameraExecutor = null
            Log.d("CameraManager", "Camera system released")
        }
    }

    fun release() {
        ContextCompat.getMainExecutor(context).execute {
            cameraExecutor?.shutdown()
            cameraExecutor = null
        }
    }
}
