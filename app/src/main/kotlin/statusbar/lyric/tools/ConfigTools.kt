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

package statusbar.lyric.tools

import android.annotation.SuppressLint
import android.content.SharedPreferences
import statusbar.lyric.XposedApp
import statusbar.lyric.config.Config.Companion.CONFIG_NAME
import statusbar.lyric.tools.Tools.isNull

@SuppressLint("CommitPrefEdits")
class ConfigTools(
    sharedPreferences: SharedPreferences?,
    private val readOnly: Boolean = false
) {
    private var mSP: SharedPreferences? = sharedPreferences
    private var mSPEditor: SharedPreferences.Editor? = if (readOnly) null else sharedPreferences?.edit()

    fun reload() {
        if (readOnly) {
            if (mSP == null) {
                mSP = Tools.getPref(CONFIG_NAME)
            }
        }
    }

    fun put(key: String?, any: Any) {
        if (readOnly || key == null) return
        when (any) {
            is Int -> mSPEditor?.putInt(key, any)
            is String -> mSPEditor?.putString(key, any)
            is Boolean -> mSPEditor?.putBoolean(key, any)
            is Float -> mSPEditor?.putFloat(key, any)
            is Long -> mSPEditor?.putLong(key, any)
        }
        mSPEditor?.apply()
        XposedApp.syncPreference(CONFIG_NAME, key, any)
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> opt(key: String, defValue: T): T {
        val sp = mSP
        sp.isNull {
            return defValue
        }
        return try {
            when (defValue) {
                is String -> (sp!!.getString(key, defValue) ?: defValue) as T
                is Int -> sp!!.getInt(key, defValue) as T
                is Boolean -> sp!!.getBoolean(key, defValue) as T
                is Long -> sp!!.getLong(key, defValue) as T
                is Double -> sp!!.getFloat(key, defValue.toFloat()).toDouble() as T
                is Float -> {
                    try {
                        sp!!.getFloat(key, defValue) as T
                    } catch (_: ClassCastException) {
                        val raw = sp!!.all[key]
                        when (raw) {
                            is Number -> raw.toFloat() as T
                            is String -> (raw.toFloatOrNull() ?: defValue) as T
                            else -> defValue
                        }
                    }
                }
                else -> defValue
            }
        } catch (_: Throwable) {
            defValue
        }
    }

    fun clearConfig() {
        if (readOnly) return
        mSPEditor?.clear()?.apply()
        XposedApp.clearRemotePreferences(CONFIG_NAME)
    }
}