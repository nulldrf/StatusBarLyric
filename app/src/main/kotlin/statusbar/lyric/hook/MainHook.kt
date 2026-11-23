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

package statusbar.lyric.hook

import android.annotation.SuppressLint
import android.content.res.AssetManager
import android.content.res.Resources
import android.os.Build
import androidx.annotation.RequiresApi
import io.github.kyuubiran.ezxhelper.xposed.EzXposed
import io.github.kyuubiran.ezxhelper.xposed.EzXposed.moduleRes
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import statusbar.lyric.BuildConfig
import statusbar.lyric.R
import statusbar.lyric.config.Config
import statusbar.lyric.config.XposedOwnSP
import statusbar.lyric.config.XposedOwnSP.config
import statusbar.lyric.hook.module.Self
import statusbar.lyric.hook.module.SystemUILyric
import statusbar.lyric.hook.module.SystemUITest
import statusbar.lyric.tools.LogTools
import statusbar.lyric.tools.LogTools.log
import java.util.Locale

class MainHook : XposedModule() {

    private fun registerProviders() {
        LogTools.xposedLogger = { message, throwable ->
            if (throwable != null) {
                log(android.util.Log.INFO, LogTools.TAG, message, throwable)
            } else {
                log(android.util.Log.INFO, LogTools.TAG, message)
            }
        }
        XposedOwnSP.remotePrefsProvider = { group ->
            runCatching { getRemotePreferences(group) }.getOrNull()
        }
        XposedOwnSP.emptyClassWarningProvider = {
            safeString(R.string.load_class_empty, "Anchor class is empty")
        }
    }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        registerProviders()
        runCatching {
            EzXposed.initOnModuleLoaded(this, param)
        }
        ensureModuleResources()
        XposedOwnSP.remotePrefs = runCatching { getRemotePreferences(Config.CONFIG_NAME) }.getOrNull()
        config.update()
        LogTools.init(config.outLog)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onPackageLoaded(param: PackageLoadedParam) {
        runCatching {
            EzXposed.initOnPackageLoaded(param)
        }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (!param.isFirstPackage) return
        registerProviders()
        runCatching {
            EzXposed.initOnPackageReady(param)
        }
        ensureModuleResources()
        if (XposedOwnSP.remotePrefs == null) {
            XposedOwnSP.remotePrefs = runCatching { getRemotePreferences(Config.CONFIG_NAME) }.getOrNull()
        }
        config.update()
        LogTools.init(config.outLog)

        when (param.packageName) {
            "com.android.systemui" -> {
                if (!config.masterSwitch) {
                    safeString(R.string.master_off, "Master switch is off").log()
                    return
                }
                "${BuildConfig.APPLICATION_ID} - ${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}[${Locale.getDefault().language}] *${BuildConfig.BUILD_TYPE})".log()
                if (config.testMode) {
                    safeString(R.string.hook_page, "Anchor Selection Mode").log()
                    initHooks(SystemUITest())
                } else {
                    safeString(R.string.lyric_mode, "Lyric Mode").log()
                    try {
                        initHooks(SystemUILyric())
                    } catch (t: Throwable) {
                        t.log()
                    }
                }
            }

            BuildConfig.APPLICATION_ID -> {
                initHooks(Self())
            }
        }
    }

    @SuppressLint("DiscouragedPrivateApi", "PrivateApi")
    private fun ensureModuleResources() {
        val loaded = runCatching {
            EzXposed.initModuleResources()
            moduleRes.getString(R.string.app_name)
            true
        }.getOrDefault(false)
        if (loaded) return

        runCatching {
            val sourceDir = moduleApplicationInfo.sourceDir
            val sysRes = Resources.getSystem()
            val assetManager = AssetManager::class.java.getDeclaredConstructor().apply {
                isAccessible = true
            }.newInstance()
            val addAssetPath = AssetManager::class.java.getDeclaredMethod("addAssetPath", String::class.java).apply {
                isAccessible = true
            }
            addAssetPath.invoke(assetManager, sourceDir)
            @Suppress("DEPRECATION")
            val fallbackRes = Resources(assetManager, sysRes.displayMetrics, sysRes.configuration)
            EzXposed::class.java.getDeclaredField("modulePath").apply {
                isAccessible = true
                set(null, sourceDir)
            }
            EzXposed::class.java.getDeclaredField("moduleRes").apply {
                isAccessible = true
                set(null, fallbackRes)
            }
        }.onFailure {
            it.log()
        }
    }

    private fun safeString(resId: Int, fallback: String): String {
        return runCatching { moduleRes.getString(resId) }.getOrDefault(fallback)
    }

    private fun initHooks(vararg hook: BaseHook) {
        hook.forEach {
            try {
                if (it.isInit) return
                it.init()
                it.isInit = true
                "${safeString(R.string.hook_succeeded, "Hook Succeeded")}:${it.javaClass.simpleName}".log()
            } catch (e: Throwable) {
                "${safeString(R.string.hook_failed, "Hook Failed")}:${it.javaClass.simpleName}".log()
                e.log()
            }
        }
    }
}