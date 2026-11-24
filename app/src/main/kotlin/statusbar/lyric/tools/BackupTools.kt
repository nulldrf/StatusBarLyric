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

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import org.json.JSONObject
import statusbar.lyric.MainActivity
import statusbar.lyric.config.ActivityOwnSP
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object BackupTools {

    private var sharedPreferences: SharedPreferences? = null

    private fun resolveSp(): SharedPreferences = sharedPreferences ?: ActivityOwnSP.ownSP

    fun formatDate(date: Date): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return formatter.format(date)
    }

    fun backup(activity: Activity, sp: SharedPreferences) {
        sharedPreferences = sp
        saveFile(activity, "StatusBarLyric_Compose(${formatDate(Date())}).json")
    }

    fun recovery(activity: Activity, sp: SharedPreferences) {
        sharedPreferences = sp
        openFile(activity)
    }

    private fun openFile(activity: Activity) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
        }
        (activity as? MainActivity)?.openDocumentLauncher?.launch(intent)
    }

    private fun saveFile(activity: Activity, fileName: String) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, fileName)
        }
        (activity as? MainActivity)?.createDocumentLauncher?.launch(intent)
    }

    fun handleReadDocument(activity: Activity, data: Uri?) {
        val uri = data ?: return
        try {
            activity.contentResolver.openInputStream(uri)?.bufferedReader().use { reader ->
                val read = reader?.readText().orEmpty()
                if (read.isNotEmpty()) {
                    val json = JSONObject(read)
                    val edit = resolveSp().edit()
                    edit.clear()
                    json.keys().forEach { key ->
                        when (val value = json.get(key)) {
                            is String -> {
                                if (value.startsWith("Float:")) {
                                    val parsed = value.substring(6).toFloatOrNull()
                                    if (parsed != null) {
                                        edit.putFloat(key, parsed / 1000f)
                                    } else {
                                        edit.putString(key, value)
                                    }
                                } else {
                                    edit.putString(key, value)
                                }
                            }

                            is Boolean -> edit.putBoolean(key, value)
                            is Int -> edit.putInt(key, value)
                            is Long -> edit.putLong(key, value)
                            is Double -> edit.putFloat(key, value.toFloat())
                        }
                    }
                    edit.commit()
                    ActivityOwnSP.syncAllToRemote(overwriteRemote = true)
                    ActivityTools.showToastOnLooper("Configuration loaded successfully")
                } else {
                    ActivityTools.showToastOnLooper("Load failed: Empty file")
                }
            }
        } catch (e: Throwable) {
            ActivityTools.showToastOnLooper("Load failed\n$e")
        }
    }

    fun handleCreateDocument(activity: Activity, data: Uri?) {
        val uri = data ?: return
        try {
            val stream = runCatching { activity.contentResolver.openOutputStream(uri, "wt") }.getOrNull()
                ?: activity.contentResolver.openOutputStream(uri)
            stream?.bufferedWriter().use { writer ->
                writer?.write(JSONObject().apply {
                    resolveSp().all.forEach { (key, value) ->
                        when (value) {
                            is Float -> put(key, "Float:${(value * 1000).toInt()}")
                            else -> put(key, value)
                        }
                    }
                }.toString())
            }
            ActivityTools.showToastOnLooper("Configuration saved successfully")
        } catch (e: Throwable) {
            ActivityTools.showToastOnLooper("Save failed\n$e")
        }
    }
}