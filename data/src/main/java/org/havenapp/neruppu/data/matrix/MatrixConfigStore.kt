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

package org.havenapp.neruppu.data.matrix

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import org.havenapp.neruppu.domain.repository.AlertTargetRepository
import org.havenapp.neruppu.domain.repository.MatrixConfigRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MatrixConfigStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val alertTargetRepository: AlertTargetRepository
) : MatrixConfigRepository {
    private val prefs by lazy {
        // Older versions fell back to a plaintext file when the Keystore failed.
        // Remove any such copy so the access token is never left unencrypted.
        context.deleteSharedPreferences(LEGACY_PLAINTEXT_FILE)
        runCatching { createEncryptedPrefs() }.getOrElse { first ->
            // Usually a Keystore entry that no longer matches the file (after a
            // restore or OS upgrade). Drop the unreadable file and start clean;
            // the user re-enters credentials. Never fall back to plaintext.
            Log.e("MatrixConfigStore", "Encrypted prefs unreadable, resetting Matrix credentials", first)
            context.deleteSharedPreferences(PREFS_FILE)
            createEncryptedPrefs()
        }
    }

    private fun createEncryptedPrefs(): android.content.SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override var homeserverUrl: String
        get() = prefs.getString(KEY_HOMESERVER, "") ?: ""
        set(value) = prefs.edit().putString(KEY_HOMESERVER, value.trim()).apply()

    override var roomId: String
        get() = prefs.getString(KEY_ROOM_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ROOM_ID, value.trim()).apply()

    override var accessToken: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TOKEN, value.trim()).apply()

    override val isComplete: Boolean
        get() = homeserverUrl.isNotBlank() && roomId.isNotBlank() && accessToken.isNotBlank()

    override fun clear() {
        prefs.edit().clear().apply()
        alertTargetRepository.clearActiveTarget()
        Log.d("MatrixConfigStore", "Matrix config cleared, target reset to NONE")
    }

    fun setAsActiveTarget() {
        alertTargetRepository.setActiveTarget(org.havenapp.neruppu.domain.model.AlertTarget.MATRIX)
    }

    companion object {
        private const val PREFS_FILE = "neruppu_matrix_config"
        private const val LEGACY_PLAINTEXT_FILE = "neruppu_matrix_fallback"
        private const val KEY_HOMESERVER = "homeserver_url"
        private const val KEY_ROOM_ID = "room_id"
        private const val KEY_TOKEN = "access_token"
    }
}
