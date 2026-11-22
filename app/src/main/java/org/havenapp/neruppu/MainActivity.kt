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

package org.havenapp.neruppu

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import org.havenapp.neruppu.core.ui.theme.BackgroundSecondary
import org.havenapp.neruppu.core.ui.theme.NeruppuOrange
import org.havenapp.neruppu.core.ui.theme.NeruppuTheme
import org.havenapp.neruppu.core.ui.theme.TextPrimary
import org.havenapp.neruppu.core.ui.theme.TextSecondary
import org.havenapp.neruppu.data.prefs.GuardPrefs
import org.havenapp.neruppu.service.MonitoringService
import org.havenapp.neruppu.ui.components.LocalMediaLock
import org.havenapp.neruppu.ui.components.MediaLock
import org.havenapp.neruppu.ui.features.about.AboutScreen
import org.havenapp.neruppu.ui.features.dashboard.DashboardScreen
import org.havenapp.neruppu.ui.features.gallery.GalleryScreen
import org.havenapp.neruppu.ui.features.logs.LogsScreen
import org.havenapp.neruppu.ui.features.logs.LogsViewModel
import org.havenapp.neruppu.ui.features.permission.PermissionScreen
import org.havenapp.neruppu.ui.features.settings.SettingsScreen
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
// FragmentActivity (a ComponentActivity) is required by androidx.biometric's BiometricPrompt.
class MainActivity : FragmentActivity() {

    private val monitoringService = mutableStateOf<MonitoringService?>(null)
    private var isBound = false
    private var hasPermissions by mutableStateOf(false)

    // --- Recordings lock -------------------------------------------------
    // Photos and audio are shown only after biometric or device-credential
    // auth, and lock again whenever the app leaves the screen.
    private var mediaUnlocked by mutableStateOf(false)
    private lateinit var biometricPrompt: BiometricPrompt

    private val mediaAuthenticators: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            // Android 9-10 don't support STRONG combined with device credential.
            BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        }

    private fun createBiometricPrompt(): BiometricPrompt = BiometricPrompt(
        this,
        ContextCompat.getMainExecutor(this),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                // Keep recordings out of screenshots and the recents thumbnail while visible.
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                mediaUnlocked = true
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                val userCancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                    errorCode == BiometricPrompt.ERROR_CANCELED
                if (!userCancelled) {
                    Toast.makeText(this@MainActivity, errString, Toast.LENGTH_SHORT).show()
                }
            }
        }
    )

    private fun requestMediaUnlock() {
        if (mediaUnlocked) return
        val canAuth = BiometricManager.from(this).canAuthenticate(mediaAuthenticators)
        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            // No screen lock means nothing to verify against: keep recordings
            // locked and point the user at setting one up.
            Toast.makeText(
                this,
                "Set up a screen lock (fingerprint, face, PIN or pattern) to view recordings.",
                Toast.LENGTH_LONG
            ).show()
            runCatching { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
            return
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock recordings")
            .setSubtitle("Confirm it's you to view photos and audio")
            .setAllowedAuthenticators(mediaAuthenticators)
            .build()
        biometricPrompt.authenticate(info)
    }

    override fun onStart() {
        super.onStart()
        // FLAG_SECURE stays on while the app goes to the background, so the
        // recents thumbnail is blank; drop it once we're visible and locked.
        if (!mediaUnlocked) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onStop() {
        super.onStop()
        // Relock whenever the app is no longer visible (Home, screen off,
        // switching apps). The device-credential screen also triggers this,
        // but its success callback arrives afterwards and unlocks again.
        mediaUnlocked = false
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName?, service: IBinder?) {
            val binder = service as MonitoringService.LocalBinder
            monitoringService.value = binder.getService()
            isBound = true
        }

        override fun onServiceDisconnected(arg0: ComponentName?) {
            monitoringService.value = null
            isBound = false
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (!isBound) {
                    bindService(Intent(this@MainActivity, MonitoringService::class.java), this, BIND_AUTO_CREATE)
                }
            }, 2000)
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        hasPermissions = allGranted
        if (allGranted) {
            bindMonitoringService()
        }
    }

    private fun bindMonitoringService() {
        if (!isBound) {
            bindService(Intent(this, MonitoringService::class.java), connection, BIND_AUTO_CREATE)
        }
    }

    private fun getRequiredPermissions(): Array<String> {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return permissions.toTypedArray()
    }

    private fun hasAllPermissions(): Boolean {
        return getRequiredPermissions().all { permission ->
            ContextCompat.checkSelfPermission(this, permission) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    private fun checkAndRequestPermissions() {
        if (!hasAllPermissions()) {
            requestPermissionLauncher.launch(getRequiredPermissions())
        } else {
            hasPermissions = true
            bindMonitoringService()
        }
    }

    override fun onResume() {
        super.onResume()
        val currentlyHasPermissions = hasAllPermissions()
        if (hasPermissions != currentlyHasPermissions) {
            hasPermissions = currentlyHasPermissions
            if (hasPermissions) {
                bindMonitoringService()
            }
        }
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen().setKeepOnScreenCondition { !hasPermissions }
        super.onCreate(savedInstanceState)
        // Android 15 (targetSdk 35) enforces edge-to-edge; opt in explicitly so
        // system bar icons follow the theme and Scaffold insets do the padding.
        enableEdgeToEdge()
        biometricPrompt = createBiometricPrompt()

        checkAndRequestPermissions()

        setContent {
            CompositionLocalProvider(
                LocalMediaLock provides MediaLock(isUnlocked = mediaUnlocked, requestUnlock = ::requestMediaUnlock)
            ) {
            NeruppuTheme {
                if (!hasPermissions) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    PermissionScreen(
                        onPermissionsGranted = {
                            hasPermissions = true
                            bindMonitoringService()
                        },
                        onOpenSettings = {
                            startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", packageName, null)
                                )
                            )
                        }
                    )
                    }
                } else {
                    MainAppScreen()
                }
            }
            }
        }
    }

    @Composable
    private fun MainAppScreen() {
        var selectedTab by remember { mutableIntStateOf(0) }
        // About is a sub-page of Settings; Back returns to Settings.
        var showAbout by rememberSaveable { mutableStateOf(false) }
        val logsViewModel: LogsViewModel = hiltViewModel()

        val context = LocalContext.current
        // Keys and defaults come from GuardPrefs, which MonitoringService reads too.
        val prefs = GuardPrefs.prefs(context)

        var motionEnabled by remember { mutableStateOf(prefs.getBoolean(GuardPrefs.KEY_MOTION_ENABLED, GuardPrefs.DEFAULT_MOTION_ENABLED)) }
        var soundEnabled by remember { mutableStateOf(prefs.getBoolean(GuardPrefs.KEY_SOUND_ENABLED, GuardPrefs.DEFAULT_SOUND_ENABLED)) }
        var lightEnabled by remember { mutableStateOf(prefs.getBoolean(GuardPrefs.KEY_LIGHT_ENABLED, GuardPrefs.DEFAULT_LIGHT_ENABLED)) }
        var motionSensitivity by remember { mutableStateOf(prefs.getFloat(GuardPrefs.KEY_MOTION_SENSITIVITY, GuardPrefs.DEFAULT_MOTION_SENSITIVITY)) }
        var soundThreshold by remember { mutableStateOf(prefs.getFloat(GuardPrefs.KEY_SOUND_THRESHOLD, GuardPrefs.DEFAULT_SOUND_THRESHOLD)) }
        var pushAlerts by remember { mutableStateOf(prefs.getBoolean(GuardPrefs.KEY_PUSH_ALERTS, GuardPrefs.DEFAULT_PUSH_ALERTS)) }
        var savePhotos by remember { mutableStateOf(prefs.getBoolean(GuardPrefs.KEY_SAVE_PHOTOS, GuardPrefs.DEFAULT_SAVE_PHOTOS)) }
        var useFrontCamera by remember { mutableStateOf(prefs.getBoolean(GuardPrefs.KEY_USE_FRONT_CAMERA, GuardPrefs.DEFAULT_USE_FRONT_CAMERA)) }

        Scaffold(
            bottomBar = {
                NavigationBar(
                    // No fixed height: NavigationBar adds the system navigation
                    // inset itself, which a fixed 80dp would squeeze.
                    containerColor = BackgroundSecondary,
                    tonalElevation = 0.dp
                ) {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Icon(Icons.Default.Home, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_home), style = MaterialTheme.typography.labelMedium) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = TextPrimary,
                            selectedTextColor = TextPrimary,
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary,
                            indicatorColor = NeruppuOrange.copy(alpha = 0.1f)
                        )
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_events), style = MaterialTheme.typography.labelMedium) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = TextPrimary,
                            selectedTextColor = TextPrimary,
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary,
                            indicatorColor = NeruppuOrange.copy(alpha = 0.1f)
                        )
                    )
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        icon = { Icon(Icons.Default.PhotoLibrary, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_media), style = MaterialTheme.typography.labelMedium) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = TextPrimary,
                            selectedTextColor = TextPrimary,
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary,
                            indicatorColor = NeruppuOrange.copy(alpha = 0.1f)
                        )
                    )
                    NavigationBarItem(
                        selected = selectedTab == 3,
                        onClick = { selectedTab = 3 },
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_settings), style = MaterialTheme.typography.labelMedium) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = TextPrimary,
                            selectedTextColor = TextPrimary,
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary,
                            indicatorColor = NeruppuOrange.copy(alpha = 0.1f)
                        )
                    )
                }
            }
        ) { paddingValues ->
            // consumeWindowInsets stops the Logs/Settings screens' own Scaffolds
            // from padding for the system bars a second time under edge-to-edge.
            Box(
                modifier = Modifier
                    .padding(paddingValues)
                    .consumeWindowInsets(paddingValues)
            ) {
                when (selectedTab) {
                    0 -> {
                        val service = monitoringService.value
                        if (service != null) {
                            val isMonitoring by service.isMonitoring.collectAsState()
                            val motionLevel by service.motionLevel.collectAsState()
                            val audioLevel by service.audioLevel.collectAsState()
                            val lightLevel by service.lightLevel.collectAsState()
                            val sessionStartTime by service.sessionStartTime.collectAsState()
                            val lastGuardedTime by service.lastGuardedTime.collectAsState()

                            DashboardScreen(
                                isMonitoring = isMonitoring,
                                motionLevel = motionLevel,
                                audioLevel = audioLevel,
                                lightLevel = lightLevel,
                                accelerometerStable = true,
                                    onToggleMonitoring = {
                                        if (!isMonitoring) {
                                            val hasCamera = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                                            val hasAudio = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                                            if (!hasCamera || !hasAudio) {
                                                hasPermissions = false
                                                return@DashboardScreen
                                            }
                                            val intent = Intent(context, MonitoringService::class.java)
                                            context.startForegroundService(intent)
                                        }
                                        service.toggleMonitoring()
                                    },
                                useFrontCamera = useFrontCamera,
                                sessionStartTime = sessionStartTime,
                                lastGuardedTime = lastGuardedTime
                            )

                            val lifecycleOwner = LocalLifecycleOwner.current
                            // Preview is active only while the dashboard is on screen:
                            // pressing Home must release the camera too, not just
                            // switching tabs.
                            DisposableEffect(lifecycleOwner, service) {
                                val observer = LifecycleEventObserver { _, event ->
                                    when (event) {
                                        Lifecycle.Event.ON_START -> service.setUiActive(true)
                                        Lifecycle.Event.ON_STOP -> service.setUiActive(false)
                                        else -> Unit
                                    }
                                }
                                // Delivers ON_START immediately if already started.
                                lifecycleOwner.lifecycle.addObserver(observer)
                                onDispose {
                                    lifecycleOwner.lifecycle.removeObserver(observer)
                                    service.setUiActive(false)
                                }
                            }
                        } else {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = NeruppuOrange)
                            }
                        }
                    }
                    1 -> {
                        LogsScreen(
                            viewModel = logsViewModel,
                            onRequestDelete = { logsViewModel.requestDelete() }
                        )
                    }
                    2 -> {
                        GalleryScreen()
                    }
                    3 -> if (showAbout) {
                        AboutScreen(onBack = { showAbout = false })
                    } else {
                        SettingsScreen(
                            onAboutClick = { showAbout = true },
                            motionEnabled = motionEnabled,
                            onMotionToggle = {
                                motionEnabled = it
                                prefs.edit().putBoolean(GuardPrefs.KEY_MOTION_ENABLED, it).apply()
                            },
                            soundEnabled = soundEnabled,
                            onSoundToggle = {
                                soundEnabled = it
                                prefs.edit().putBoolean(GuardPrefs.KEY_SOUND_ENABLED, it).apply()
                            },
                            lightEnabled = lightEnabled,
                            onLightToggle = {
                                lightEnabled = it
                                prefs.edit().putBoolean(GuardPrefs.KEY_LIGHT_ENABLED, it).apply()
                            },
                            motionSensitivity = motionSensitivity,
                            onMotionSensitivityChange = {
                                motionSensitivity = it
                                prefs.edit().putFloat(GuardPrefs.KEY_MOTION_SENSITIVITY, it).apply()
                            },
                            soundThreshold = soundThreshold,
                            onSoundThresholdChange = {
                                soundThreshold = it
                                prefs.edit().putFloat(GuardPrefs.KEY_SOUND_THRESHOLD, it).apply()
                            },
                            pushAlerts = pushAlerts,
                            onPushAlertsToggle = {
                                pushAlerts = it
                                prefs.edit().putBoolean(GuardPrefs.KEY_PUSH_ALERTS, it).apply()
                            },
                            savePhotos = savePhotos,
                            onSavePhotosToggle = {
                                savePhotos = it
                                prefs.edit().putBoolean(GuardPrefs.KEY_SAVE_PHOTOS, it).apply()
                            }
                        )
                    }
                }
            }
        }
    }
}
