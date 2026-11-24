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

import android.content.res.Configuration
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlinx.serialization.Serializable
import statusbar.lyric.config.ActivityOwnSP.config
import statusbar.lyric.ui.page.ChoosePage
import statusbar.lyric.ui.page.ExtendPage
import statusbar.lyric.ui.page.HomePage
import statusbar.lyric.ui.page.IconPage
import statusbar.lyric.ui.page.LyricPage
import statusbar.lyric.ui.page.MenuPage
import statusbar.lyric.ui.page.SystemSpecialPage
import statusbar.lyric.ui.page.TestPage
import statusbar.lyric.ui.theme.AppTheme
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.VerticalDivider
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.nav.core.NavBackStack
import top.yukonga.miuix.kmp.nav.core.NavCornerClipMode
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.nav.transition.NavTransitions
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Serializable
sealed interface Route : NavKey {
    val routeName: String

    @Serializable
    data object HomePage : Route {
        override val routeName: String get() = "HomePage"
    }

    @Serializable
    data object ChoosePage : Route {
        override val routeName: String get() = "ChoosePage"
    }

    @Serializable
    data object TestPage : Route {
        override val routeName: String get() = "TestPage"
    }

    @Serializable
    data object MenuPage : Route {
        override val routeName: String get() = "MenuPage"
    }

    @Serializable
    data object LyricPage : Route {
        override val routeName: String get() = "LyricPage"
    }

    @Serializable
    data object IconPage : Route {
        override val routeName: String get() = "IconPage"
    }

    @Serializable
    data object ExtendPage : Route {
        override val routeName: String get() = "ExtendPage"
    }

    @Serializable
    data object SystemSpecialPage : Route {
        override val routeName: String get() = "SystemSpecialPage"
    }

    companion object {
        fun fromName(name: String): Route = when (name) {
            "ChoosePage" -> ChoosePage
            "TestPage" -> TestPage
            "MenuPage" -> MenuPage
            "LyricPage" -> LyricPage
            "IconPage" -> IconPage
            "ExtendPage" -> ExtendPage
            "SystemSpecialPage" -> SystemSpecialPage
            else -> HomePage
        }
    }
}

class AppNavigator(val backStack: NavBackStack) {
    val currentRoute: String
        get() = (backStack.lastOrNull() as? Route)?.routeName ?: "HomePage"

    fun navigate(route: String, popUpToRoute: String? = "HomePage") {
        val target = Route.fromName(route)
        if (popUpToRoute != null) {
            while (backStack.size > 1 && (backStack.last() as? Route)?.routeName != popUpToRoute) {
                backStack.removeAt(backStack.lastIndex)
            }
        }
        if (target !in backStack) {
            backStack.add(target)
        }
    }

    fun popBackStack() {
        if (backStack.size > 1) {
            backStack.removeLastOrNull()
        }
    }
}

@Composable
fun rememberBlurBackdrop(): LayerBackdrop? {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return null
    if (!isRuntimeShaderSupported()) return null
    val surfaceColor = MiuixTheme.colorScheme.surface
    return rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
}

@Composable
fun BlurredTopBar(
    backdrop: LayerBackdrop?,
    scrollBehavior: ScrollBehavior,
    content: @Composable () -> Unit
) {
    val collapsedFraction = scrollBehavior.state.collapsedFraction.coerceIn(0f, 1f)
    val surfaceColor = MiuixTheme.colorScheme.surface
    val alpha = if (collapsedFraction <= 0f) 1f else lerp(1f, 0.72f, collapsedFraction)
    Box(
        modifier = if (backdrop != null) {
            Modifier.textureBlur(
                backdrop = backdrop,
                shape = RectangleShape,
                blurRadius = 25f,
                colors = BlurDefaults.blurColors(
                    blendColors = listOf(
                        BlendColorEntry(color = surfaceColor.copy(alpha = alpha))
                    )
                )
            )
        } else {
            Modifier
        }
    ) {
        content()
    }
}

@Composable
fun App() {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val backStack = rememberNavBackStack<Route>(Route.HomePage)
    val navigator = remember(backStack) { AppNavigator(backStack) }
    val currentRoute = navigator.currentRoute

    AppTheme {
        Scaffold {
            BoxWithConstraints {
                if (isLandscape || maxWidth > 768.dp) {
                    val windowWidthPx = with(LocalDensity.current) { maxWidth.toPx() }
                    LandscapeLayout(navigator, currentRoute, windowWidthPx)
                } else {
                    PortraitLayout(navigator, currentRoute)
                }
            }
        }
    }
}

@Composable
fun PortraitLayout(
    navigator: AppNavigator,
    currentRoute: String
) {
    val navCornerRadius = rememberNavSystemCornerRadius()
    val backdropColor = MiuixTheme.colorScheme.surface
    val effects = remember(navCornerRadius, backdropColor) {
        NavDisplayEffects(
            enableCornerClip = true,
            cornerClipRadius = navCornerRadius,
            cornerClipMode = NavCornerClipMode.Leading,
            dimAmount = 0.5f,
            blockInputDuringTransition = true,
            backdropColor = backdropColor
        )
    }
    val swipeDirection = if (LocalLayoutDirection.current == LayoutDirection.Rtl) {
        NavSwipeDirection.RightToLeft
    } else {
        NavSwipeDirection.LeftToRight
    }

    NavDisplay(
        backStack = navigator.backStack,
        onBack = { navigator.popBackStack() },
        transition = NavTransitions.MiuixDefault,
        effects = effects
    ) {
        entry<Route.HomePage>(swipeDismiss = NavSwipeDirection.None) {
            HomePage(navigator, currentRoute)
        }
        entry<Route.ChoosePage>(swipeDismiss = swipeDirection) {
            ChoosePage(navigator)
        }
        entry<Route.TestPage>(swipeDismiss = swipeDirection) {
            TestPage(navigator, currentRoute)
        }
        entry<Route.MenuPage>(swipeDismiss = swipeDirection) {
            MenuPage(navigator)
        }
        entry<Route.LyricPage>(swipeDismiss = swipeDirection) {
            LyricPage(navigator)
        }
        entry<Route.IconPage>(swipeDismiss = swipeDirection) {
            IconPage(navigator)
        }
        entry<Route.ExtendPage>(swipeDismiss = swipeDirection) {
            ExtendPage(navigator)
        }
        entry<Route.SystemSpecialPage>(swipeDismiss = swipeDirection) {
            SystemSpecialPage(navigator)
        }
    }
}

@Composable
fun LandscapeLayout(
    navigator: AppNavigator,
    currentRoute: String,
    windowWidthPx: Float
) {
    var weight by remember { mutableFloatStateOf(config.pageRatio) }
    var potentialWeight by remember { mutableFloatStateOf(weight) }
    val dragState = rememberDraggableState {
        if (windowWidthPx > 0f) {
            val nextPotentialWeight = potentialWeight + it / windowWidthPx
            potentialWeight = nextPotentialWeight
            weight = nextPotentialWeight.coerceIn(0.35f, 0.65f)
        }
    }
    val backdropColor = MiuixTheme.colorScheme.surface
    val effects = remember(backdropColor) {
        NavDisplayEffects(
            enableCornerClip = false,
            cornerClipRadius = 0.dp,
            dimAmount = 0f,
            blockInputDuringTransition = true,
            backdropColor = backdropColor
        )
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp)
    ) {
        Box(
            modifier = Modifier
                .weight(weight)
                .fillMaxHeight()
        ) {
            HomePage(navigator, currentRoute)
        }
        VerticalDivider(
            modifier = Modifier
                .draggable(
                    state = dragState,
                    orientation = Orientation.Horizontal,
                    onDragStarted = {
                        potentialWeight = weight
                    },
                    onDragStopped = {
                        config.pageRatio = weight
                    }
                )
                .padding(horizontal = 12.dp)
        )
        Box(
            modifier = Modifier
                .weight(1f - weight)
                .fillMaxHeight()
                .clipToBounds()
        ) {
            NavDisplay(
                backStack = navigator.backStack,
                onBack = { navigator.popBackStack() },
                transition = NavTransitions.MiuixDefault,
                effects = effects
            ) {
                entry<Route.HomePage>(swipeDismiss = NavSwipeDirection.None) {
                    EmptyPage()
                }
                entry<Route.ChoosePage>(swipeDismiss = NavSwipeDirection.None) {
                    ChoosePage(navigator)
                }
                entry<Route.TestPage>(swipeDismiss = NavSwipeDirection.None) {
                    TestPage(navigator, currentRoute)
                }
                entry<Route.MenuPage>(swipeDismiss = NavSwipeDirection.None) {
                    MenuPage(navigator)
                }
                entry<Route.LyricPage>(swipeDismiss = NavSwipeDirection.None) {
                    LyricPage(navigator)
                }
                entry<Route.IconPage>(swipeDismiss = NavSwipeDirection.None) {
                    IconPage(navigator)
                }
                entry<Route.ExtendPage>(swipeDismiss = NavSwipeDirection.None) {
                    ExtendPage(navigator)
                }
                entry<Route.SystemSpecialPage>(swipeDismiss = NavSwipeDirection.None) {
                    SystemSpecialPage(navigator)
                }
            }
        }
    }
}

@Composable
fun EmptyPage() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            tint = MiuixTheme.colorScheme.secondary,
            modifier = Modifier.size(300.dp)
        )
    }
}
