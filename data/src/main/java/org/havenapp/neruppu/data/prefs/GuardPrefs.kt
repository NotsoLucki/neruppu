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

package org.havenapp.neruppu.data.prefs

import android.content.Context
import android.content.SharedPreferences

/**
 * Single source of truth for the settings shared by the Settings screen and
 * MonitoringService. Both sides must read and write through these keys and
 * defaults; before this existed the two sides used different key names and
 * several settings had no effect.
 */
object GuardPrefs {
    const val FILE = "neruppu_prefs"

    const val KEY_MOTION_ENABLED = "motion_enabled"
    const val KEY_SOUND_ENABLED = "sound_enabled"
    const val KEY_LIGHT_ENABLED = "light_enabled"
    const val KEY_MOTION_SENSITIVITY = "motion_sensitivity"
    const val KEY_SOUND_THRESHOLD = "sound_threshold"
    const val KEY_PUSH_ALERTS = "push_alerts"
    const val KEY_SAVE_PHOTOS = "save_photos"
    const val KEY_USE_FRONT_CAMERA = "use_front_camera"
    const val KEY_AUDIO_CAPTURE_DURATION = "audio_capture_duration"

    /** Persisted so a system restart of the service can tell that a session was active. */
    const val KEY_IS_MONITORING = "is_monitoring"
    const val KEY_SESSION_START = "session_start_time"
    const val KEY_LAST_GUARDED = "last_guarded_time"

    const val DEFAULT_MOTION_ENABLED = true
    const val DEFAULT_SOUND_ENABLED = true
    const val DEFAULT_LIGHT_ENABLED = false
    const val DEFAULT_MOTION_SENSITIVITY = 0.5f
    const val DEFAULT_SOUND_THRESHOLD = 0.6f
    const val DEFAULT_PUSH_ALERTS = true
    const val DEFAULT_SAVE_PHOTOS = true
    const val DEFAULT_USE_FRONT_CAMERA = false
    const val DEFAULT_AUDIO_CAPTURE_SECONDS = 5f

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * Maps the 0..1 "Motion threshold" slider to the per-pixel difference
     * threshold used by MotionAnalyzer (higher = less sensitive).
     */
    fun motionPixelThreshold(slider: Float): Int =
        (slider.coerceIn(0f, 1f) * 30).toInt().coerceIn(5, 30)

    /** Maps the 0..1 "Sound threshold" slider to an RMS spike threshold. */
    fun soundSpikeThreshold(slider: Float): Float =
        50f + slider.coerceIn(0f, 1f) * 1950f
}
