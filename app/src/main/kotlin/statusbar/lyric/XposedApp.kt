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

package statusbar.lyric

import android.app.Application
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import statusbar.lyric.config.ActivityOwnSP
import statusbar.lyric.config.Config
import statusbar.lyric.tools.ActivityTools.isHook
import statusbar.lyric.tools.LogTools.log

class XposedApp : Application(), XposedServiceHelper.OnServiceListener {

    override fun onCreate() {
        super.onCreate()
        instance = this
        MainActivity.appContext = this
        ActivityOwnSP.migrateLegacyPreferences(this)
        isModuleActive = isHook()
        MainActivity.isLoad = isModuleActive
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        xposedService = service
        isModuleActive = true
        MainActivity.isLoad = true
        ActivityOwnSP.syncWithRemoteService(service)
        "XposedService bound: ${service.frameworkName} ${service.frameworkVersion} (API ${service.apiVersion})".log()
    }

    override fun onServiceDied(service: XposedService) {
        if (xposedService === service) {
            xposedService = null
        }
        isModuleActive = isHook()
        MainActivity.isLoad = isModuleActive
        "XposedService died".log()
    }

    companion object {
        lateinit var instance: XposedApp
            private set

        @Volatile
        var xposedService: XposedService? = null
            private set

        var isModuleActive by mutableStateOf(false)
            internal set

        fun getRemotePreferences(group: String = Config.CONFIG_NAME): SharedPreferences? {
            return try {
                xposedService?.getRemotePreferences(group)
            } catch (t: Throwable) {
                t.log()
                null
            }
        }

        fun syncPreference(group: String = Config.CONFIG_NAME, key: String?, value: Any?) {
            if (key == null) return
            val remotePrefs = getRemotePreferences(group) ?: return
            try {
                remotePrefs.edit().apply {
                    when (value) {
                        null -> remove(key)
                        is Int -> putInt(key, value)
                        is String -> putString(key, value)
                        is Boolean -> putBoolean(key, value)
                        is Float -> putFloat(key, value)
                        is Long -> putLong(key, value)
                        is Set<*> -> {
                            @Suppress("UNCHECKED_CAST")
                            putStringSet(key, value as Set<String>)
                        }
                    }
                    commit()
                }
            } catch (t: Throwable) {
                t.log()
            }
        }

        fun clearRemotePreferences(group: String = Config.CONFIG_NAME) {
            val remotePrefs = getRemotePreferences(group) ?: return
            try {
                val editor = remotePrefs.edit()
                remotePrefs.all.keys.forEach { editor.remove(it) }
                editor.clear()
                editor.commit()
            } catch (t: Throwable) {
                t.log()
            }
        }
    }
}
