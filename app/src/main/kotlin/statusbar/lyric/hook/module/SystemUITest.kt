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

package statusbar.lyric.hook.module

import android.annotation.SuppressLint
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.IntentCompat
import io.github.kyuubiran.ezxhelper.core.finder.MethodFinder.`-Static`.methodFinder
import io.github.kyuubiran.ezxhelper.xposed.EzXposed
import io.github.kyuubiran.ezxhelper.xposed.EzXposed.moduleRes
import io.github.kyuubiran.ezxhelper.xposed.dsl.HookFactory.`-Static`.createHook
import statusbar.lyric.R
import statusbar.lyric.config.XposedOwnSP.config
import statusbar.lyric.data.Data
import statusbar.lyric.hook.BaseHook
import statusbar.lyric.tools.ActivityTestTools.receiveClass
import statusbar.lyric.tools.LogTools.log
import statusbar.lyric.tools.LyricViewTools.hideView
import statusbar.lyric.tools.LyricViewTools.showView
import statusbar.lyric.tools.Tools.dispose
import statusbar.lyric.tools.Tools.goMainThread
import java.text.SimpleDateFormat
import java.util.Locale

class SystemUITest : BaseHook() {
    lateinit var context: Context
    lateinit var lastView: TextView
    var lastViewId = 0
    val testTextView by lazy {
        TextView(context).apply {
            text = runCatching { moduleRes.getString(R.string.app_name) }.getOrDefault("StatusBar Lyric")
            isSingleLine = true
            gravity = Gravity.CENTER
            setBackgroundColor(Color.WHITE)
            setTextColor(Color.BLACK)
        }
    }
    private val dataHashMap by lazy { HashMap<TextView, Data>() }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun init() {
        statusbar.lyric.hook.MainHook.onApplicationContextReady { appContext ->
            context = appContext.applicationContext ?: appContext
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                    TestReceiver(),
                    IntentFilter("TestReceiver"),
                    Context.RECEIVER_EXPORTED
                )
            } else {
                context.registerReceiver(TestReceiver(), IntentFilter("TestReceiver"))
            }
            statusbar.lyric.hook.MainHook.logAlways("SystemUITest: TestReceiver registered on ${context.javaClass.name}")
            runCatching { moduleRes.getString(R.string.start_hooking_text_view).log() }
            hook()
        }
    }

    private fun hook() {
        TextView::class.java.methodFinder().filterByName("onDraw").first().createHook {
            after { hookParam ->
                runCatching {
                    val view = (hookParam.thisObject as TextView)
                    val className = hookParam.thisObject::class.java.name
                    val text = view.text?.toString()?.dispose().orEmpty()
                    if (text.isEmpty()) return@after
                    text.isTimeSame {
                        if (className.filterClassName()) {
                            view.filterView {
                                val parentView = (view.parent as LinearLayout)
                                val entryName = runCatching {
                                    if (view.id != View.NO_ID) context.resources.getResourceEntryName(view.id) else "no_id"
                                }.getOrDefault("unknown")
                                val newData = Data(
                                    className,
                                    view.id,
                                    parentView::class.java.name,
                                    parentView.id,
                                    false,
                                    0,
                                    view.textSize,
                                    entryName
                                )
                                var index = 0
                                val exists = dataHashMap.values.any { data ->
                                    if (data.textViewClassName == className
                                        && data.textViewId == view.id
                                        && data.parentViewClassName == parentView::class.java.name
                                        && data.parentViewId == parentView.id
                                        && data.textSize == view.textSize
                                        && data.idName == entryName
                                    ) {
                                        index += 1
                                        true
                                    } else {
                                        false
                                    }
                                }
                                if (!exists) {
                                    newData.index = index
                                    dataHashMap[view] = newData
                                    runCatching {
                                        moduleRes.getString(R.string.first_filter)
                                            .format(newData, dataHashMap.size).log()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun String.isTimeSame(callback: () -> Unit) {
        val timeFormat = arrayOf(
            SimpleDateFormat("H:mm", Locale.getDefault()),
            SimpleDateFormat("h:mm", Locale.getDefault()),
            SimpleDateFormat("HH:mm", Locale.getDefault()),
            SimpleDateFormat("hh:mm", Locale.getDefault())
        )
        val nowTime = System.currentTimeMillis()
        timeFormat.forEach {
            if (it.format(nowTime).toRegex().containsMatchIn(this)) {
                callback()
                return
            }
        }
        config.update()
        if (config.relaxConditions) {
            if (this.contains("周") || this.contains("月") || this.contains("日") || this.contains(":")) {
                callback()
                return
            }
        }
    }

    private fun String.filterClassName(): Boolean {
        config.update()
        if (config.relaxConditions) return true
        val filterList = arrayListOf("controlcenter", "image", "keyguard")
        filterList.forEach {
            if (contains(it, true)) return false
        }
        return this != TextView::class.java.name
    }

    @SuppressLint("DiscouragedApi")
    private fun View.filterView(function: () -> Unit) {
        if (this.parent is LinearLayout) {
            val parentView = (this.parent as LinearLayout)
            val id = context.resources.getIdentifier("clock_container", "id", context.packageName)
            if (parentView.id != id) {
                function()
            }
        }
    }

    inner class TestReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val type = intent.getStringExtra("Type")
            statusbar.lyric.hook.MainHook.logAlways("SystemUITest.TestReceiver onReceive: type=$type, candidates=${dataHashMap.size}")
            when (type) {
                "GetClass" -> {
                    if (dataHashMap.isEmpty()) {
                        runCatching { moduleRes.getString(R.string.no_text_view).log() }
                        context.receiveClass(arrayListOf())
                        return
                    } else {
                        runCatching {
                            moduleRes.getString(R.string.send_text_view_class).format(dataHashMap).log()
                        }
                        context.receiveClass(ArrayList(dataHashMap.values))
                    }
                }

                "ShowView" -> {
                    val bundle = IntentCompat.getParcelableExtra(intent, "DataBundle", Bundle::class.java)
                    val data = if (bundle != null) {
                        Data.fromBundle(bundle)
                    } else {
                        runCatching {
                            intent.setExtrasClassLoader(Data::class.java.classLoader)
                            IntentCompat.getParcelableExtra(intent, "Data", Data::class.java)
                        }.getOrNull()
                    } ?: return
                    goMainThread {
                        dataHashMap.forEach { (textview, da) ->
                            if (da.textViewClassName == data.textViewClassName
                                && da.textViewId == data.textViewId
                                && da.parentViewClassName == data.parentViewClassName
                                && da.parentViewId == data.parentViewId
                                && da.textSize == data.textSize
                                && da.index == data.index
                            ) {
                                if (!this@SystemUITest::lastView.isInitialized || lastView !== textview) {
                                    if (this@SystemUITest::lastView.isInitialized) {
                                        runCatching { (lastView.parent as? LinearLayout)?.removeView(testTextView) }
                                        lastView.showView()
                                    }
                                    runCatching { (testTextView.parent as? android.view.ViewGroup)?.removeView(testTextView) }
                                    textview.hideView()
                                    val parentLinearLayout = textview.parent as? LinearLayout ?: return@forEach
                                    parentLinearLayout.addView(testTextView, 0)
                                    lastViewId = textview.id
                                    lastView = textview
                                }
                                return@forEach
                            }
                        }
                    }
                }
            }
        }
    }
}