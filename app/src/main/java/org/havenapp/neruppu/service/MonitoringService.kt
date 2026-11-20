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

package org.havenapp.neruppu.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.util.Log
import org.havenapp.neruppu.BuildConfig
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleService
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.havenapp.neruppu.R
import org.havenapp.neruppu.data.audio.AudioRecorder
import org.havenapp.neruppu.data.camera.CameraManager
import org.havenapp.neruppu.data.prefs.GuardPrefs
import org.havenapp.neruppu.data.sensors.*
import org.havenapp.neruppu.domain.model.Event
import org.havenapp.neruppu.domain.model.SensorType
import org.havenapp.neruppu.domain.model.SensorEvent
import org.havenapp.neruppu.domain.model.AlertTarget
import org.havenapp.neruppu.domain.repository.AlertTargetRepository
import org.havenapp.neruppu.domain.repository.MediaUploadRepository
import org.havenapp.neruppu.domain.repository.SensorRepository
import org.havenapp.neruppu.domain.usecase.HandleSensorEventUseCase
import org.havenapp.neruppu.domain.usecase.AttachAudioToEventUseCase
import org.havenapp.neruppu.worker.EventNotificationWorker
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

@AndroidEntryPoint
class MonitoringService : LifecycleService() {

    @Inject
    lateinit var sensorRepository: SensorRepository

    @Inject
    lateinit var handleSensorEventUseCase: HandleSensorEventUseCase

    @Inject
    lateinit var attachAudioToEventUseCase: AttachAudioToEventUseCase

    @Inject
    lateinit var alertTargetRepository: AlertTargetRepository

    @Inject
    lateinit var mediaUploadRepository: MediaUploadRepository

    @Inject
    lateinit var cameraManager: CameraManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLockRefreshJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var sensorsJob: Job? = null
    private var heartbeatJob: Job? = null
    
    private lateinit var accelerometerDriver: AccelerometerDriver
    private lateinit var microphoneDriver: MicrophoneDriver
    private lateinit var lightSensorDriver: LightSensorDriver
    private lateinit var significantMotionDriver: SignificantMotionDriver
    private lateinit var audioRecorder: AudioRecorder

    private val _motionLevel = MutableStateFlow(0.0)
    val motionLevel: StateFlow<Double> = _motionLevel

    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel

    private val _lightLevel = MutableStateFlow(0f)
    val lightLevel: StateFlow<Float> = _lightLevel

    private val _isMonitoring = MutableStateFlow(false)
    val isMonitoring: StateFlow<Boolean> = _isMonitoring

    private val _sessionStartTime = MutableStateFlow(0L)
    val sessionStartTime: StateFlow<Long> = _sessionStartTime

    private val _lastGuardedTime = MutableStateFlow(0L)
    val lastGuardedTime: StateFlow<Long> = _lastGuardedTime

    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    inner class LocalBinder : Binder() {
        fun getService(): MonitoringService = this@MonitoringService
    }

    private val binder = LocalBinder()

    private var prefChangeJob: Job? = null
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        prefChangeJob?.cancel()
        // Control state (jobs, camera binding) is confined to the main thread.
        prefChangeJob = serviceScope.launch(Dispatchers.Main) {
            delay(500) // debounce
            updatePrefValues()
            when (key) {
                GuardPrefs.KEY_MOTION_SENSITIVITY, GuardPrefs.KEY_USE_FRONT_CAMERA -> {
                    Log.d("MonitoringService", "Prefs changed ($key), updating camera...")
                    // bindCamera applies a sensitivity change in place, or rebinds on a camera switch.
                    if (_isMonitoring.value || _uiActive.value) startMonitoring()
                }
            }
        }
    }

    // Read on sensor threads, written on the main thread.
    @Volatile private var savePhotosPref = GuardPrefs.DEFAULT_SAVE_PHOTOS
    @Volatile private var sensitivityPref = GuardPrefs.DEFAULT_MOTION_SENSITIVITY
    @Volatile private var useFrontCameraPref = GuardPrefs.DEFAULT_USE_FRONT_CAMERA
    @Volatile private var soundThresholdPref = GuardPrefs.DEFAULT_SOUND_THRESHOLD
    @Volatile private var motionEnabledPref = GuardPrefs.DEFAULT_MOTION_ENABLED
    @Volatile private var soundEnabledPref = GuardPrefs.DEFAULT_SOUND_ENABLED
    @Volatile private var lightEnabledPref = GuardPrefs.DEFAULT_LIGHT_ENABLED
    @Volatile private var pushAlertsPref = GuardPrefs.DEFAULT_PUSH_ALERTS

    private fun updatePrefValues() {
        val prefs = GuardPrefs.prefs(this)
        savePhotosPref = prefs.getBoolean(GuardPrefs.KEY_SAVE_PHOTOS, GuardPrefs.DEFAULT_SAVE_PHOTOS)
        sensitivityPref = prefs.getFloat(GuardPrefs.KEY_MOTION_SENSITIVITY, GuardPrefs.DEFAULT_MOTION_SENSITIVITY)
        useFrontCameraPref = prefs.getBoolean(GuardPrefs.KEY_USE_FRONT_CAMERA, GuardPrefs.DEFAULT_USE_FRONT_CAMERA)
        soundThresholdPref = prefs.getFloat(GuardPrefs.KEY_SOUND_THRESHOLD, GuardPrefs.DEFAULT_SOUND_THRESHOLD)
        motionEnabledPref = prefs.getBoolean(GuardPrefs.KEY_MOTION_ENABLED, GuardPrefs.DEFAULT_MOTION_ENABLED)
        soundEnabledPref = prefs.getBoolean(GuardPrefs.KEY_SOUND_ENABLED, GuardPrefs.DEFAULT_SOUND_ENABLED)
        lightEnabledPref = prefs.getBoolean(GuardPrefs.KEY_LIGHT_ENABLED, GuardPrefs.DEFAULT_LIGHT_ENABLED)
        pushAlertsPref = prefs.getBoolean(GuardPrefs.KEY_PUSH_ALERTS, GuardPrefs.DEFAULT_PUSH_ALERTS)
    }

    /** Whether the user has this detector switched on in Settings. */
    private fun isDetectorEnabled(type: SensorType): Boolean = when (type) {
        SensorType.CAMERA_MOTION, SensorType.ACCELEROMETER -> motionEnabledPref
        SensorType.MICROPHONE -> soundEnabledPref
        SensorType.LIGHT -> lightEnabledPref
        else -> true
    }

    private fun shouldSendRemoteAlert(): Boolean =
        pushAlertsPref && alertTargetRepository.activeTarget != AlertTarget.NONE

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return binder
    }

    private var isRecordingAudio = AtomicBoolean(false)
    private var lastNoiseTime = 0L
    private var lastEventTime = ConcurrentHashMap<SensorType, Long>()
    private var microphoneJob: Job? = null
    private var uiSensorsJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Log.d("MonitoringService", "onCreate [PID: ${Process.myPid()}]")
        
        lifecycle.addObserver(object : LifecycleEventObserver {
            override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                Log.d("MonitoringService", "Lifecycle event: $event")
            }
        })

        createNotificationChannel()
        updateNotification()

        accelerometerDriver = AccelerometerDriver(this)
        microphoneDriver = MicrophoneDriver()
        lightSensorDriver = LightSensorDriver(this)
        significantMotionDriver = SignificantMotionDriver(this)
        audioRecorder = AudioRecorder(this)
        
        updatePrefValues()
        val prefs = GuardPrefs.prefs(this)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)

        _sessionStartTime.value = prefs.getLong(GuardPrefs.KEY_SESSION_START, 0L)
        _lastGuardedTime.value = prefs.getLong(GuardPrefs.KEY_LAST_GUARDED, 0L)

        if (prefs.getBoolean(GuardPrefs.KEY_IS_MONITORING, false)) {
            // A session was active when the process died. A START_STICKY restart
            // delivers onStartCommand(null) right after this and resumes it. If that
            // doesn't happen (e.g. the app was force-stopped and reopened), record
            // that guarding was interrupted instead of silently showing "idle".
            Handler(Looper.getMainLooper()).postDelayed({
                if (!_isMonitoring.value &&
                    GuardPrefs.prefs(this).getBoolean(GuardPrefs.KEY_IS_MONITORING, false)
                ) {
                    markGuardingStopped(System.currentTimeMillis())
                    recordGuardEvent("Guarding was interrupted: Neruppu was stopped by the system. Open the app to start guarding again.")
                }
            }, 3000)
        }

        startHeartbeat()
        // Sensors will be started based on isMonitoring or UI visibility
        startMonitoringIfNeeded()
    }

    private fun startHeartbeat() {
        if (!BuildConfig.DEBUG) return
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            while (isActive) {
                Log.v("MonitoringService", "HEARTBEAT - Monitoring: ${_isMonitoring.value}, UI: ${_uiActive.value}, PID: ${Process.myPid()}")
                delay(10000)
            }
        }
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (wakeLock == null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Neruppu:MonitoringWakeLock")
        }
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire(10 * 60 * 1000L) // 10-minute timeout
            Log.d("MonitoringService", "WakeLock acquired")
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            Log.d("MonitoringService", "WakeLock released")
        }
    }

    private fun startWakeLockIfNeeded() {
        if (_isMonitoring.value) {
            acquireWakeLock()
            startWakeLockRefresh()
        }
    }

    private fun stopWakeLockIfNeeded() {
        if (!_isMonitoring.value) {
            wakeLockRefreshJob?.cancel()
            wakeLockRefreshJob = null
            releaseWakeLock()
        }
    }

    private fun startWakeLockRefresh() {
        wakeLockRefreshJob?.cancel()
        wakeLockRefreshJob = serviceScope.launch {
            while (isActive && _isMonitoring.value) {
                delay(480 * 1000L) // Refresh every ~8 minutes (before 10-min timeout)
                if (_isMonitoring.value) {
                    val timeout = calculateWakeLockTimeout()
                    val powerManager = getSystemService(POWER_SERVICE) as PowerManager
                    if (wakeLock?.isHeld == true) {
                        wakeLock?.release()
                        Log.d("MonitoringService", "WakeLock refreshed (adaptive timeout: ${timeout/1000/60}min)")
                    }
                    wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Neruppu:MonitoringWakeLock")
                    wakeLock?.acquire(timeout)
                }
            }
        }
    }

    private fun calculateWakeLockTimeout(): Long {
        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        val batteryLevel = batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val chargingStatus = batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_STATUS)
        val isCharging = chargingStatus == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                        chargingStatus == android.os.BatteryManager.BATTERY_STATUS_FULL

        return when {
            isCharging -> 10 * 60 * 1000L // 10 minutes when charging
            batteryLevel < 20 -> 5 * 60 * 1000L // 5 minutes when battery low
            batteryLevel < 50 -> 7 * 60 * 1000L // 7 minutes when medium battery
            else -> 10 * 60 * 1000L // 10 minutes when high battery
        }
    }

    private fun startMonitoringIfNeeded() {
        // We need the service to be "active" (startMonitoring) if:
        // 1. We are actually monitoring (background or foreground)
        // 2. The UI is active and needs a preview/sensor levels
        if (_isMonitoring.value || _uiActive.value) {
            startMonitoring()
        } else {
            stopMonitoring()
        }
    }

    private val _uiActive = MutableStateFlow(false)
    fun setUiActive(active: Boolean) {
        _uiActive.value = active
        if (active) {
            startMonitoring()
        } else {
            startMonitoringIfNeeded()
        }
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Log.d("MonitoringService", "onStartCommand: action=${intent?.action}")

        if (intent == null) {
            // START_STICKY restart after the system killed the process.
            resumeAfterRestartIfNeeded()
            return START_STICKY
        }

        when (intent.action) {
            ACTION_STOP_SERVICE -> {
                Log.i("MonitoringService", "Stopping service via notification action")
                if (_isMonitoring.value) {
                    setMonitoring(false, stopReason = "Guarding stopped from the notification.")
                }
                stopSelf()
            }
            "TRIGGER_MOTION_EVENT" -> {
                val level = intent.getDoubleExtra("motion_level", 0.0)
                serviceScope.launch {
                    handleEvent(SensorType.CAMERA_MOTION, "Camera motion detected: Level ${String.format("%.2f", level)}")
                }
            }
        }

        return START_STICKY
    }

    private fun resumeAfterRestartIfNeeded() {
        val prefs = GuardPrefs.prefs(this)
        if (_isMonitoring.value || !prefs.getBoolean(GuardPrefs.KEY_IS_MONITORING, false)) return

        Log.w("MonitoringService", "Resuming guarding after a system restart")
        _isMonitoring.value = true
        try {
            // Android may refuse a camera/microphone foreground service started
            // from the background. If so, say so rather than going dark silently.
            updateNotification()
        } catch (e: Exception) {
            Log.e("MonitoringService", "Could not resume guarding in the background", e)
            _isMonitoring.value = false
            markGuardingStopped(System.currentTimeMillis())
            recordGuardEvent(
                "Guarding stopped: the system restarted Neruppu and it could not resume in the background. Open the app to start guarding again.",
                thenStopSelf = true
            )
            return
        }
        startWakeLockIfNeeded()
        startMonitoringIfNeeded()
        recordGuardEvent("Guarding resumed after the system restarted Neruppu. Camera and microphone may be unavailable until the app is opened.")
    }

    fun toggleMonitoring() {
        setMonitoring(!_isMonitoring.value, stopReason = "Guarding stopped from the app.")
    }

    private fun setMonitoring(active: Boolean, stopReason: String) {
        val now = System.currentTimeMillis()
        if (active == _isMonitoring.value) return
        _isMonitoring.value = active
        if (active) {
            Log.d("MonitoringService", "Monitoring ACTIVATED")
            _sessionStartTime.value = now
            GuardPrefs.prefs(this)
                .edit()
                .putBoolean(GuardPrefs.KEY_IS_MONITORING, true)
                .putLong(GuardPrefs.KEY_SESSION_START, now)
                .remove(GuardPrefs.KEY_LAST_GUARDED)
                .apply()
            startWakeLockIfNeeded()
            updateNotification()
        } else {
            Log.d("MonitoringService", "Monitoring DEACTIVATED")
            markGuardingStopped(now)
            stopWakeLockIfNeeded()
            updateNotification()
            recordGuardEvent(stopReason)
        }
        startMonitoringIfNeeded()
    }

    private fun markGuardingStopped(now: Long) {
        _lastGuardedTime.value = now
        GuardPrefs.prefs(this)
            .edit()
            .putBoolean(GuardPrefs.KEY_IS_MONITORING, false)
            .remove(GuardPrefs.KEY_SESSION_START)
            .putLong(GuardPrefs.KEY_LAST_GUARDED, now)
            .apply()
    }

    /**
     * Logs a guard lifecycle event and sends it as a remote alert, so the owner
     * learns when guarding stops (including from the lock screen) or restarts.
     */
    private fun recordGuardEvent(description: String, thenStopSelf: Boolean = false) {
        serviceScope.launch {
            try {
                val id = sensorRepository.saveEvent(
                    Event(
                        timestamp = java.time.Instant.now(),
                        sensorType = SensorType.SYSTEM,
                        description = description
                    )
                )
                if (id > 0 && shouldSendRemoteAlert()) {
                    mediaUploadRepository.enqueueUpload(id)
                }
            } catch (e: Exception) {
                Log.e("MonitoringService", "Failed to record guard event", e)
            } finally {
                if (thenStopSelf) withContext(Dispatchers.Main) { stopSelf() }
            }
        }
    }

    private fun stopMonitoring() {
        Log.d("MonitoringService", "stopMonitoring() called - Monitoring: ${_isMonitoring.value}, UI: ${_uiActive.value}")
        
        // ONLY unbind camera if NO ONE needs it (not monitoring AND UI is not visible)
        if (!_isMonitoring.value && !_uiActive.value) {
            Log.d("MonitoringService", "Releasing camera system (Idle & UI hidden)")
            cameraManager.unbind()
        } else {
            Log.d("MonitoringService", "Keeping camera bound for Preview/UI")
        }

        microphoneJob?.cancel()
        microphoneJob = null
        uiSensorsJob?.cancel()
        uiSensorsJob = null
        sensorsJob?.cancel()
        sensorsJob = null
    }


    private fun startMonitoring() {
        Log.d("MonitoringService", "startMonitoring() called - Monitoring: ${_isMonitoring.value}, UI: ${_uiActive.value}")

        // Bind camera if we are either Monitoring OR UI is active. A repeat call
        // with the same camera only updates the sensitivity.
        if (_isMonitoring.value || _uiActive.value) {
            cameraManager.bindCamera(
                lifecycleOwner = this,
                useFrontCamera = useFrontCameraPref,
                sensitivity = GuardPrefs.motionPixelThreshold(sensitivityPref),
                onMotionDetected = { level ->
                    _motionLevel.value = level
                    if (_isMonitoring.value && level > 15.0) {
                        Log.v("MonitoringService", "THRESHOLD EXCEEDED: $level")
                        serviceScope.launch {
                            handleEvent(SensorType.CAMERA_MOTION, "Camera motion detected: Level ${String.format("%.2f", level)}")
                        }
                    }
                }
            )
        }

        if (_isMonitoring.value) {
            // Guard mode owns the sensors. The preview-only readers must stop,
            // or two AudioRecord instances compete for the microphone.
            uiSensorsJob?.cancel()
            uiSensorsJob = null

            if (sensorsJob?.isActive == true) {
                Log.d("MonitoringService", "Sensors already running. Skipping re-initialization.")
                return
            }

            sensorsJob = serviceScope.launch {
                Log.v("MonitoringService", "Launching sensor flows...")

                val accelerometerFlow = accelerometerDriver.observeMotion()
                    .buffer()
                    .onEach { magnitude ->
                        Log.v("MonitoringService", "Motion event received")
                        serviceScope.launch {
                            handleEvent(SensorType.ACCELEROMETER, "Physical motion detected: Magnitude $magnitude")
                        }
                    }

                val lightFlow = lightSensorDriver.observeLightChanges()
                    .buffer()
                    .onEach { lux ->
                        _lightLevel.value = lux
                        Log.v("MonitoringService", "Light event received")
                        serviceScope.launch {
                            handleEvent(SensorType.LIGHT, "Light change detected: $lux lux")
                        }
                    }

                launch { accelerometerFlow.collect() }
                launch { lightFlow.collect() }

                // Significant Motion Monitoring (Ultra-low power wakeups)
                launch {
                    significantMotionDriver.observeSignificantMotion().collect {
                        handleEvent(SensorType.ACCELEROMETER, "Significant motion detected (hardware trigger)")
                    }
                }

                startMicrophoneMonitoring()
            }
        } else {
            // Not guarding: guard sensors must not keep firing events. Before,
            // stopping with the dashboard open left them running.
            sensorsJob?.cancel()
            sensorsJob = null
            microphoneJob?.cancel()
            microphoneJob = null

            if (_uiActive.value && uiSensorsJob?.isActive != true) {
                // Preview mode: show live levels only.
                uiSensorsJob = serviceScope.launch {
                    launch {
                        lightSensorDriver.observeLightChanges()
                            .conflate()
                            .collect { _lightLevel.value = it }
                    }
                    launch {
                        microphoneDriver.observeNoise()
                            .conflate()
                            .collect { _audioLevel.value = it.toFloat() }
                    }
                }
            }
        }
    }

    private var fastBaseline = 0f
    private var slowBaseline = 0f

    private fun startMicrophoneMonitoring() {
        microphoneJob?.cancel()
        
        microphoneJob = serviceScope.launch {
            microphoneDriver.observeNoise()
                .conflate()
                .collect { amplitude ->
                    _audioLevel.value = amplitude.toFloat()
                    
                    val amp = amplitude.toFloat()
                    if (fastBaseline == 0f) {
                        fastBaseline = amp
                        slowBaseline = amp
                    }
                    
                    // Dual EMA: Fast adapts in ~0.2s (sensitive), Slow adapts in ~1000s (stable noise floor)
                    fastBaseline = 0.5f * fastBaseline + 0.5f * amp
                    slowBaseline = 0.999f * slowBaseline + 0.001f * amp
                    
                    val spike = fastBaseline - slowBaseline
                    
                    // Settings "Sound threshold" slider (0..1), read live.
                    val threshold = GuardPrefs.soundSpikeThreshold(soundThresholdPref)

                    // CRITICAL: Check threshold BEFORE compareAndSet to avoid blocking
                    if (_isMonitoring.value && soundEnabledPref && spike > threshold &&
                        isRecordingAudio.compareAndSet(false, true)
                    ) {
                        Log.i("MonitoringService", "AUDIO TRIGGER (SPIKE): $spike (Threshold: $threshold, Fast: $fastBaseline, Slow: $slowBaseline)")
                        serviceScope.launch {
                            val eventId = handleEvent(SensorType.MICROPHONE, "Acoustic spike detected: ${spike.toInt()}")
                            startAudioRecording(eventId)
                        }
                    } else if (spike > (threshold * 0.5f)) {
                        // Log near-misses to help user calibrate
                        Log.v("MonitoringService", "Near-miss spike: $spike (Threshold: $threshold)")
                    }
                }
        }
    }

    private fun startAudioRecording(eventId: Long = -1) {
        // isRecordingAudio is already set to true in the trigger block

        val captureDuration = (GuardPrefs.prefs(this)
            .getFloat(GuardPrefs.KEY_AUDIO_CAPTURE_DURATION, GuardPrefs.DEFAULT_AUDIO_CAPTURE_SECONDS) * 1000).toLong()

        serviceScope.launch {
            var audioFile: File? = null
            try {
                // STOP microphone monitoring to release the AudioRecord instance
                Log.d("MonitoringService", "Stopping microphone monitoring to allow recording...")
                withContext(Dispatchers.Main) {
                    microphoneJob?.cancel()
                    microphoneJob = null
                }
                // Wait a bit for the driver to close the AudioRecord
                delay(400)

                Log.d("MonitoringService", "Capturing ${captureDuration/1000}s audio clip of the event...")
                audioFile = audioRecorder.startRecording()
                if (audioFile == null) {
                    Log.e("MonitoringService", "Failed to start AudioRecorder")
                } else {
                    delay(captureDuration)
                }
            } catch (e: Exception) {
                Log.e("MonitoringService", "Audio recording failed", e)
            } finally {
                // stopRecording MUST be called to finalize the file and release MediaRecorder
                val uri = withContext(NonCancellable) { audioRecorder.stopRecording() }

                // RESUME monitoring ASAP
                isRecordingAudio.set(false)
                Log.d("MonitoringService", "Resetting audio recording flag and restarting monitoring.")
                withContext(NonCancellable + Dispatchers.Main) {
                    if (_isMonitoring.value && sensorsJob?.isActive == true) startMicrophoneMonitoring()
                }

                // Process the clip and the alert outside the monitoring pause.
                val tempFile = audioFile
                serviceScope.launch {
                    if (uri != null && tempFile != null && eventId > 0) {
                        Log.i("MonitoringService", "AUDIO CLIP CAPTURED: $uri. Attaching to event $eventId...")
                        // Copies the clip to permanent storage.
                        attachAudioToEventUseCase.execute(eventId, tempFile, System.currentTimeMillis())
                            .onFailure { Log.w("MonitoringService", "Failed to attach audio to event $eventId", it) }
                    }
                    // Delete the temp clip on every path (attached, failed, or no event).
                    tempFile?.let { f ->
                        if (f.exists() && !f.delete()) Log.w("MonitoringService", "Failed to delete temp audio file")
                    }
                    // Alert whether or not the clip was captured: a failed recording
                    // must not mean a missed alert.
                    if (eventId > 0 && shouldSendRemoteAlert()) {
                        mediaUploadRepository.enqueueUpload(eventId)
                    }
                }
            }
        }
    }

    private suspend fun handleEvent(type: SensorType, description: String): Long {
        println("Neruppu: handleEvent type=$type")
        if (!isDetectorEnabled(type)) {
            Log.d("MonitoringService", "Event ignored, detector disabled in settings: $type")
            return -1
        }

        val now = System.currentTimeMillis()

        // Cooldown: 3 seconds for the same type, unless it's a microphone event which has its own logic.
        // Checked and claimed atomically, so two concurrent triggers can't both pass.
        if (type != SensorType.MICROPHONE) {
            var allowed = false
            lastEventTime.compute(type) { _, last ->
                if (last == null || now - last >= 3000) {
                    allowed = true
                    now
                } else {
                    last
                }
            }
            if (!allowed) {
                Log.d("MonitoringService", "Event suppressed by cooldown: $type")
                return -1
            }
        } else {
            lastEventTime[type] = now
        }

        Log.i("MonitoringService", ">>> TRIGGERING EVENT: $type - $description")
        
        // 1. Capture photo bytes if possible
        val imageBytes: ByteArray? = if (savePhotosPref) {
            try {
                Log.d("MonitoringService", "Attempting to capture photo for event...")
                val uri = cameraManager.capturePhoto()
                withContext(Dispatchers.IO) {
                    uri?.let {
                        val bytes = contentResolver.openInputStream(it)?.use { stream -> stream.readBytes() }
                        // The use case saves its own copy; drop the raw capture so
                        // photos aren't stored twice (and missed by "clear events").
                        it.path?.let { path -> File(path).delete() }
                        bytes
                    }
                }
            } catch (e: Exception) {
                Log.e("MonitoringService", "CRITICAL FAILURE: Photo capture threw exception", e)
                null
            }
        } else null

        // 2. Prepare SensorEvent for Use Case
        val sensorEvent = SensorEvent(
            sensorType = type,
            description = description,
            timestamp = now,
            imageBytes = imageBytes
        )

        // 3. Orchestrate via Use Case (saves locally + logs in DB + sends Matrix alert)
        println("Neruppu: Calling HandleSensorEventUseCase for $type")
        Log.d("MonitoringService", "Calling HandleSensorEventUseCase for $type")
        val result = handleSensorEventUseCase.execute(sensorEvent)
        println("Neruppu: UseCase result=$result")
        val id = result.getOrDefault(-1L)
        
        if (result.isFailure) {
            Log.e("MonitoringService", "UseCase failed", result.exceptionOrNull())
        }

        if (id > 0 && type != SensorType.MICROPHONE && shouldSendRemoteAlert()) {
            serviceScope.launch {
                try {
                    mediaUploadRepository.enqueueUpload(id)
                } catch (e: Exception) {
                    Log.w("MonitoringService", "Failed to enqueue media upload for event $id", e)
                }
            }
        }
        
        // Schedule deferred notification via WorkManager (Best Practice)
        val workData = Data.Builder()
            .putString("event_type", type.name)
            .putString("description", description)
            .build()
        
        val notificationWork = OneTimeWorkRequestBuilder<EventNotificationWorker>()
            .setInputData(workData)
            .build()
            
        WorkManager.getInstance(this).enqueueUniqueWork(
            "security_notification",
            androidx.work.ExistingWorkPolicy.REPLACE,
            notificationWork
        )

        Log.i("MonitoringService", "EVENT PROCESSED: $type (Local ID: $id)")
        return id
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Monitoring Service",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun updateNotification() {
        val notification = createNotification()
        if (_isMonitoring.value) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                           ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                startForeground(NOTIFICATION_ID, notification, type)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            // Still show a simple notification if UI is active? 
            // Or just remove it. Let's remove it for now when not monitoring.
        }
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, MonitoringService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = if (_isMonitoring.value) {
            "System is active and monitoring sensors"
        } else {
            "System is idle. Tap START to begin monitoring."
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Neruppu Security")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_stat_security)
            .setOngoing(true)
            .addAction(
                NotificationCompat.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Stop Service",
                    stopPendingIntent
                )
                    // API 31+: the device must be unlocked to run this action, so
                    // someone at the lock screen can't switch guarding off.
                    .setAuthenticationRequired(true)
                    .build()
            )
            // Hide the action entirely from the lock screen on older versions.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("Neruppu Security")
                    .setContentText(contentText)
                    .setSmallIcon(R.drawable.ic_stat_security)
                    .build()
            )
            .build()
    }

    override fun onDestroy() {
        Log.d("MonitoringService", "onDestroy [PID: ${Process.myPid()}]")
        super.onDestroy()
        GuardPrefs.prefs(this)
            .unregisterOnSharedPreferenceChangeListener(prefsListener)
        stopWakeLockIfNeeded()
        heartbeatJob?.cancel()
        sensorsJob?.cancel()
        uiSensorsJob?.cancel()
        serviceScope.cancel()
        cameraManager.release()
    }

    companion object {
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "monitoring_channel"
        const val ACTION_STOP_SERVICE = "org.havenapp.neruppu.STOP_SERVICE"
    }
}
