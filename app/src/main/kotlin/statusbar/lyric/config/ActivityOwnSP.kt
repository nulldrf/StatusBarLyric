/*
 * StatusBarLyric
 * Copyright (C) 2021-2022 fkj@fkj233.cn
 * https://github.com/Block-Network/StatusBarLyric
 *
 * This software is free opensource software: you can redistribute it
 * and/or modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either
 * version 3 of the License, or any later version and our eula as
 * published by Block-Network contributors.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * and eula along with this software.  If not, see
 * <https://www.gnu.org/licenses/>
 * <https://github.com/Block-Network/StatusBarLyric/blob/main/LICENSE>.
 */

package statusbar.lyric.config

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import statusbar.lyric.BuildConfig
import statusbar.lyric.MainActivity
import statusbar.lyric.XposedApp
import statusbar.lyric.config.Config.Companion.CONFIG_NAME
import statusbar.lyric.tools.LogTools.log
import statusbar.lyric.tools.Tools

@SuppressLint("StaticFieldLeak")
object ActivityOwnSP {
    val ownSP: SharedPreferences by lazy { Tools.getSP(MainActivity.appContext, CONFIG_NAME) }
    val config: Config by lazy { Config(ownSP, readOnly = false) }
    private val ownEditor by lazy { ownSP.edit() }

    private fun copyEntries(from: Map<String, *>, toEditor: SharedPreferences.Editor, overwrite: Boolean, existing: Map<String, *>) {
        from.forEach { (key, value) ->
            if (!overwrite && existing.containsKey(key)) return@forEach
            when (value) {
                is Int -> toEditor.putInt(key, value)
                is Float -> toEditor.putFloat(key, value)
                is String -> {
                    if (value.startsWith("Float:")) {
                        val parsed = value.substring(6).toFloatOrNull()
                        if (parsed != null) {
                            toEditor.putFloat(key, parsed / 1000f)
                        } else {
                            toEditor.putString(key, value)
                        }
                    } else {
                        toEditor.putString(key, value)
                    }
                }
                is Boolean -> toEditor.putBoolean(key, value)
                is Long -> toEditor.putLong(key, value)
                is Set<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    toEditor.putStringSet(key, value as Set<String>)
                }
            }
        }
    }

    /**
     * Migrates preferences from any legacy SharedPreferences storage locations (credential-protected
     * or legacy Lyric_Config) into the primary device-protected COMPOSE_CONFIG SharedPreferences.
     */
    fun migrateLegacyPreferences(context: Context) {
        try {
            val dpContext = context.createDeviceProtectedStorageContext()
            val targetSp = dpContext.getSharedPreferences(CONFIG_NAME, Context.MODE_PRIVATE)
            val existing = targetSp.all
            val editor = targetSp.edit()
            var migrated = false

            val candidates = listOf(
                runCatching { context.getSharedPreferences(CONFIG_NAME, Context.MODE_PRIVATE) }.getOrNull(),
                runCatching { dpContext.getSharedPreferences("Lyric_Config", Context.MODE_PRIVATE) }.getOrNull(),
                runCatching { context.getSharedPreferences("Lyric_Config", Context.MODE_PRIVATE) }.getOrNull()
            )

            candidates.forEach { legacySp ->
                if (legacySp != null && legacySp !== targetSp) {
                    val map = legacySp.all
                    if (map.isNotEmpty()) {
                        copyEntries(map, editor, overwrite = false, existing = existing)
                        migrated = true
                    }
                }
            }

            if (migrated) {
                editor.apply()
                "Migrated legacy local preferences into $CONFIG_NAME".log()
            }
        } catch (t: Throwable) {
            t.log()
        }
    }

    /**
     * Synchronizes local preferences with libxposed API 101 RemotePreferences so that users
     * upgrading from legacy Xposed API 82 keep all of their existing preferences and keys.
     */
    fun syncWithRemoteService(service: XposedService) {
        try {
            val remotePrefs = service.getRemotePreferences(CONFIG_NAME)
            val localAll = ownSP.all
            val remoteAll = remotePrefs.all

            if (localAll.isEmpty() && remoteAll.isNotEmpty()) {
                val localEditor = ownSP.edit()
                copyEntries(remoteAll, localEditor, overwrite = true, existing = localAll)
                localEditor.putInt("ver", BuildConfig.COMPOSE_CONFIG_VERSION)
                localEditor.commit()
                "Restored preferences from Xposed RemotePreferences".log()
                return
            }

            if (remoteAll.isNotEmpty()) {
                val localEditor = ownSP.edit()
                copyEntries(remoteAll, localEditor, overwrite = false, existing = localAll)
                localEditor.commit()
            }

            val mergedLocal = ownSP.all
            val remoteEditor = remotePrefs.edit()
            copyEntries(mergedLocal, remoteEditor, overwrite = true, existing = remoteAll)
            remoteEditor.putInt("ver", BuildConfig.COMPOSE_CONFIG_VERSION)
            remoteEditor.commit()

            if (ownSP.getInt("ver", 0) < BuildConfig.COMPOSE_CONFIG_VERSION) {
                ownEditor.putInt("ver", BuildConfig.COMPOSE_CONFIG_VERSION).commit()
            }
            "Synced ${mergedLocal.size} preference keys to Xposed RemotePreferences".log()
        } catch (t: Throwable) {
            t.log()
        }
    }

    fun syncAllToRemote() {
        val service = XposedApp.xposedService ?: return
        syncWithRemoteService(service)
    }

    fun updateConfigVer() {
        if (ownSP.getInt("ver", 0) < BuildConfig.COMPOSE_CONFIG_VERSION) {
            ownEditor.putInt("ver", BuildConfig.COMPOSE_CONFIG_VERSION).apply()
            XposedApp.syncPreference(CONFIG_NAME, "ver", BuildConfig.COMPOSE_CONFIG_VERSION)
        }
    }
}
