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

package org.havenapp.neruppu.ui.features.about

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.havenapp.neruppu.core.ui.theme.BackgroundPrimary
import org.havenapp.neruppu.core.ui.theme.BackgroundSecondary
import org.havenapp.neruppu.core.ui.theme.BorderTertiary
import org.havenapp.neruppu.core.ui.theme.NeruppuOrange
import org.havenapp.neruppu.core.ui.theme.TextPrimary
import org.havenapp.neruppu.core.ui.theme.TextSecondary

/** One entry of the version history, from a changelogs/<versionCode>.txt file. */
data class ChangelogEntry(
    val versionCode: Long,
    val title: String,
    val lines: List<String>
)

/** Installed app version, read from the package so it always matches the build. */
data class AppVersion(
    val name: String,
    val code: Long,
    val isDebug: Boolean
)

private val CHANGELOG_FILE = Regex("""^(\d+)\.txt$""")

/**
 * Parses one changelog. The first line is the release title (e.g.
 * "v26.47.1 - Fork release"); the rest are the notes, with bullet markers
 * stripped. These are the same files F-Droid shows as release notes.
 */
fun parseChangelog(versionCode: Long, text: String): ChangelogEntry {
    val all = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    val title = all.firstOrNull() ?: "Build $versionCode"
    val lines = all.drop(1).map { it.removePrefix("•").removePrefix("-").removePrefix("*").trim() }
    return ChangelogEntry(versionCode, title, lines)
}

/** Reads every bundled changelog, newest first. */
fun loadChangelogs(context: Context): List<ChangelogEntry> =
    (context.assets.list("")?.toList() ?: emptyList())
        .mapNotNull { name -> CHANGELOG_FILE.matchEntire(name)?.let { it.groupValues[1].toLong() to name } }
        .mapNotNull { (code, name) ->
            runCatching {
                context.assets.open(name).bufferedReader().use { parseChangelog(code, it.readText()) }
            }.getOrNull()
        }
        .sortedByDescending { it.versionCode }

fun readAppVersion(context: Context): AppVersion {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    return AppVersion(
        name = info.versionName ?: "unknown",
        code = PackageInfoCompat.getLongVersionCode(info),
        isDebug = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    )
}

private const val SOURCE_URL = "https://github.com/NotsoLucki/neruppu"
private const val RELEASES_URL = "https://github.com/NotsoLucki/neruppu/releases"
private const val UPSTREAM_URL = "https://github.com/thamizh-root/neruppu"
private const val LICENSE_URL = "https://www.gnu.org/licenses/gpl-3.0.html"

@Composable
fun AboutScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val version = remember { readAppVersion(context) }
    val history by produceState<List<ChangelogEntry>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { loadChangelogs(context) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundPrimary)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .background(BackgroundSecondary)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
            }
            Text(
                "About",
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() }
            )
        }

        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item { AppCard(version) }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LinkRow("Source code", SOURCE_URL.removePrefix("https://"), SOURCE_URL, context)
                    LinkRow("All releases", "Download APKs and release notes", RELEASES_URL, context)
                    LinkRow("Original project", UPSTREAM_URL.removePrefix("https://"), UPSTREAM_URL, context)
                    LinkRow("License", "GNU GPL v3.0 or later", LICENSE_URL, context)
                }
            }

            item {
                Text(
                    "VERSION HISTORY",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .semantics { heading() }
                )
            }

            val entries = history
            when {
                entries == null -> Unit
                entries.isEmpty() -> item {
                    Text("No release notes bundled with this build.", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                }
                else -> items(entries, key = { it.versionCode }) { entry ->
                    ChangelogCard(entry, isCurrent = entry.versionCode == version.code)
                }
            }
        }
    }
}

@Composable
private fun AppCard(version: AppVersion) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BackgroundSecondary, RoundedCornerShape(12.dp))
            .border(0.5.dp, BorderTertiary, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Shield, contentDescription = null, tint = NeruppuOrange, modifier = Modifier.size(40.dp))
        Spacer(Modifier.width(16.dp))
        Column {
            Text("Neruppu", color = TextPrimary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "Version ${version.name} (build ${version.code})" + if (version.isDebug) " · debug" else "",
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "Offline-first physical security monitor",
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun LinkRow(label: String, detail: String, url: String, context: Context) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(BackgroundSecondary, RoundedCornerShape(12.dp))
            .border(0.5.dp, BorderTertiary, RoundedCornerShape(12.dp))
            .clickable {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = TextPrimary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(detail, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Opens in browser", tint = TextSecondary, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun ChangelogCard(entry: ChangelogEntry, isCurrent: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BackgroundSecondary, RoundedCornerShape(12.dp))
            .border(
                if (isCurrent) 1.dp else 0.5.dp,
                if (isCurrent) NeruppuOrange else BorderTertiary,
                RoundedCornerShape(12.dp)
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                entry.title,
                color = TextPrimary,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            if (isCurrent) {
                Text(
                    "Installed",
                    color = NeruppuOrange,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .background(NeruppuOrange.copy(alpha = 0.1f), RoundedCornerShape(50))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
        Text("Build ${entry.versionCode}", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(2.dp))
        entry.lines.forEach { line ->
            Row {
                Text("•", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(8.dp))
                Text(line, color = TextPrimary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
