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
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import statusbar.lyric.MainActivity
import statusbar.lyric.R
import statusbar.lyric.data.Data
import statusbar.lyric.tools.LogTools.log
import kotlin.system.exitProcess

@SuppressLint("StaticFieldLeak")
object ActivityTools {
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    var dataList: ArrayList<Data> = arrayListOf()

    fun isHook(): Boolean = false

    fun changeConfig(type: String = "normal", path: String = "") {
        Thread {
            Thread.sleep(200)
            MainActivity.appContext.sendBroadcast(Intent("updateConfig").apply {
                setPackage("com.android.systemui")
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                putExtra("type", type)
                putExtra("path", path)
            })
        }.start()
    }

    fun showToastOnLooper(message: Any?) {
        try {
            handler.post {
                Toast.makeText(MainActivity.appContext, message.toString(), Toast.LENGTH_LONG).show()
                message.log()
            }
        } catch (e: RuntimeException) {
            e.printStackTrace()
        }
    }

    fun colorCheck(value: String, unit: (String) -> Unit, default: String = "") {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            unit(default)
            return
        }
        try {
            trimmed.toColorInt()
        } catch (_: Exception) {
            showToastOnLooper(MainActivity.appContext.getString(R.string.color_error))
            return
        }
        unit(trimmed)
    }

    fun colorSCheck(value: String, unit: (String) -> Unit, default: String = "") {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            unit(default)
            return
        }
        val parts = trimmed.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) {
            unit(default)
            return
        }
        try {
            parts.forEach { it.toColorInt() }
        } catch (_: Exception) {
            showToastOnLooper(MainActivity.appContext.getString(R.string.color_error))
            return
        }
        unit(parts.joinToString(","))
    }

    fun openUrl(url: String) {
        runCatching {
            MainActivity.appContext.startActivity(
                Intent(Intent.ACTION_VIEW, url.toUri()).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }

    fun restartApp() {
        val ctx = MainActivity.appContext
        val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            ?: Intent(ctx, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ctx.startActivity(intent)
        exitProcess(0)
    }
}