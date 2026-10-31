/*
 * BitChord Glass: Square's navigation bar (Square, GPL-3.0) carrying BitChord's
 * tabs and BitChord's own now playing pill. The bar is Square's
 * FloatingTabBar + LiquidBottomTabs + liquidGlass with Square's tuning; the
 * destinations, the order and every handler are BitChord's, unchanged.
 */
@file:OptIn(ExperimentalSharedTransitionApi::class)

package com.music.bitchord.glass.nav

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Bold
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.bold.MagnifyingGlass
import com.adamglin.phosphoricons.fill.House
import com.adamglin.phosphoricons.fill.MusicNotes
import com.adamglin.phosphoricons.fill.SquaresFour
import com.adamglin.phosphoricons.fill.XCircle
import com.music.bitchord.data.model.Song
import com.music.bitchord.glass.theme.Ink
import com.music.bitchord.glass.theme.inkOn
import com.music.bitchord.glass.theme.lightPage
import com.music.bitchord.glass.ui.GlassEffectConfig
import com.music.bitchord.glass.ui.GlassStyle
import com.music.bitchord.glass.ui.LiquidBottomTab
import com.music.bitchord.glass.ui.LiquidBottomTabs
import com.music.bitchord.glass.ui.LocalAppBackdrop
import com.music.bitchord.glass.ui.LocalGlassEffectConfig
import com.music.bitchord.glass.ui.floatingtabbar.FloatingTabBar
import com.music.bitchord.glass.ui.floatingtabbar.FloatingTabBarDefaults
import com.music.bitchord.glass.ui.floatingtabbar.FloatingTabBarInlineBehavior
import com.music.bitchord.glass.ui.floatingtabbar.FloatingTabBarScrollConnection
import com.music.bitchord.glass.ui.floatingtabbar.LocalFloatingTabBarInline
import com.music.bitchord.glass.ui.liquidGlass
import com.music.bitchord.glass.ui.pressable
import com.music.bitchord.ui.components.BottomTab
import com.music.bitchord.ui.components.GlassNowPlaying
import com.music.bitchord.ui.components.MiniPlayerPull
import com.music.bitchord.ui.components.floatingtabbar.FloatingTabBarScrollConnection as BitChordScroll
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import com.music.bitchord.ui.player.PlayerDock

/** Square's open bar height, measured off its reference. */
private val BarHeight = 61.dp
private val TabIcon = 26.dp
private const val SEARCH_CIRCLE = "search-circle"

/** Square's film over the glass: a twelfth of white on the dark side, more on the light. */
internal val GlassFilm: Color
    @Composable get() = if (lightPage()) Color.White.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.12f)

/**
 * Square's icons for BitChord's four places, by position: Home, Explore,
 * Library and Search. Square has no Explore; its "New" grid is the place that
 * means "browse what there is", so Explore takes that glyph.
 */
private fun squareIcon(index: Int, last: Int): ImageVector = when (index) {
    last -> PhosphorIcons.Bold.MagnifyingGlass
    0 -> PhosphorIcons.Fill.House
    1 -> PhosphorIcons.Fill.SquaresFour
    else -> PhosphorIcons.Fill.MusicNotes
}

@Composable
fun SquareNavBar(
    tabs: List<BottomTab>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    scrollConnection: BitChordScroll,
    song: Song?,
    isPlaying: Boolean,
    isLoading: Boolean,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onExpand: () -> Unit,
    controlsLocked: Boolean,
    onBlockedControl: () -> Unit,
    dock: PlayerDock?,
    pull: MiniPlayerPull?,
    /** Search's field, grown out of the bar while the search tab is open. */
    query: String,
    onQueryChange: (String) -> Unit,
    onSearchSubmit: () -> Unit,
    /** BitChord's own hint for the source being searched. */
    searchPlaceholder: String,
    /** Whether the bar holds the field; BitChord's page then draws none of its own. */
    onSearchFieldShown: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    val backdrop = LocalAppBackdrop.current
    val glassConfig = LocalGlassEffectConfig.current
    val searchIndex = tabs.lastIndex
    val searching = selectedIndex == searchIndex
    var searchOpen by remember { mutableStateOf(searching) }
    LaunchedEffect(searching) { if (!searching) searchOpen = false }
    val fieldShown = searching && searchOpen
    LaunchedEffect(fieldShown) { onSearchFieldShown(fieldShown) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val currentOnTabSelected by rememberUpdatedState(onTabSelected)

    // Square's bar folds through its own scroll connection; BitChord's pages
    // dispatch into BitChord's. The two are kept in step both ways.
    val squareScroll = remember {
        FloatingTabBarScrollConnection(scrollConnection.isInline, 1f, FloatingTabBarInlineBehavior.Never)
    }
    LaunchedEffect(scrollConnection) {
        snapshotFlow { scrollConnection.isInline }.collect { if (it) squareScroll.inline() else squareScroll.expand() }
    }
    LaunchedEffect(scrollConnection) {
        snapshotFlow { squareScroll.isInline }.collect { if (!it) scrollConnection.expand() }
    }
    // Searching keeps the bar open, as in Square: the field lives in it.
    LaunchedEffect(fieldShown) { if (fieldShown) scrollConnection.expand() }

    // Square's chrome tuning over the listener's material: a deeper bend at the
    // rim and a lighter film. The sliders scale it, as they do in Square.
    val lightBar = lightPage()
    val defaults = GlassEffectConfig()
    fun scaled(tuned: Float, set: Float, default: Float) = (tuned * set / default).coerceIn(0f, 1f)
    val chromeConfig = glassConfig.copy(
        lensHeight = scaled(0.60f, glassConfig.lensHeight, defaults.lensHeight),
        lensAmount = scaled(0.69f, glassConfig.lensAmount, defaults.lensAmount),
        surfaceTintColor = if (glassConfig.surfaceTintColor != Color.Unspecified) {
            glassConfig.surfaceTintColor
        } else if (lightBar) Color(0xFFF5F5F7) else Color.White,
        surfaceOpacity = scaled(if (lightBar) 0.40f else 0.15f, glassConfig.surfaceOpacity, defaults.surfaceOpacity),
    )
    val barConfig = if (glassConfig.navBarEnabled) chromeConfig else glassConfig.copy(style = GlassStyle.TRANSPARENT)
    val pillConfig = if (glassConfig.miniPlayerEnabled) chromeConfig else glassConfig.copy(style = GlassStyle.TRANSPARENT)
    val barShape = remember { RoundedCornerShape(percent = 50) }
    val barGlass = Modifier.liquidGlass(config = barConfig, shape = barShape, highlightAlpha = 0.3f)
    val pillGlass = if (glassConfig.miniPlayerEnabled == glassConfig.navBarEnabled) barGlass
    else Modifier.liquidGlass(config = pillConfig, shape = barShape, highlightAlpha = 0.3f)

    val barInk = Ink
    val tabAccent = inkOn(if (lightBar) Color(0xFFFAFAFA) else Color(0xFF23232A))
    val puckWash = if (lightBar) {
        Color.Black.copy(alpha = (glassConfig.puckOpacity.coerceIn(0f, 1f) * 0.13f).coerceAtMost(0.11f))
    } else {
        Color.Black.copy(alpha = (glassConfig.puckOpacity.coerceIn(0f, 1f) * 1.3f).coerceAtMost(0.5f))
    }

    val select: (Int) -> Unit = { index ->
        if (index == searchIndex) {
            if (searching && searchOpen) {
                searchOpen = false
                focus.clearFocus()
                keyboard?.hide()
            } else {
                searchOpen = true
                if (!searching) currentOnTabSelected(index)
            }
        } else {
            if (index != selectedIndex) haptics.play(Haptic.Select)
            currentOnTabSelected(index)
        }
    }

    val accessory: (@Composable androidx.compose.animation.SharedTransitionScope.(
        Modifier, androidx.compose.animation.AnimatedVisibilityScope,
    ) -> Unit)? = song?.let { current ->
        { accessoryModifier, _ ->
            GlassNowPlaying(
                song = current,
                isInline = LocalFloatingTabBarInline.current,
                isPlaying = isPlaying,
                isLoading = isLoading,
                contentColor = barInk,
                onPlayPause = onPlayPause,
                onNext = onNext,
                onPrevious = onPrevious,
                onExpand = onExpand,
                controlsLocked = controlsLocked,
                onBlockedControl = onBlockedControl,
                dock = dock,
                pull = pull,
                modifier = accessoryModifier.fillMaxWidth().then(pillGlass),
            )
        }
    }

    val selectedState = rememberUpdatedState(selectedIndex)
    val selectedTabIndex = remember { { selectedState.value } }

    FloatingTabBar(
        selectedTabKey = if (searching) SEARCH_CIRCLE else selectedIndex,
        scrollConnection = squareScroll,
        modifier = modifier
            .windowInsetsPadding(com.music.bitchord.ui.components.floatingBarInsets.union(WindowInsets.ime))
            .padding(horizontal = 16.dp)
            .padding(bottom = 8.dp)
            .fillMaxWidth(),
        tabBarContentModifier = barGlass,
        colors = FloatingTabBarDefaults.colors(
            backgroundColor = GlassFilm,
            accessoryBackgroundColor = GlassFilm,
            selectedTabBackgroundColor = puckWash,
            selectedStandaloneTabBackgroundColor = puckWash,
        ),
        sizes = FloatingTabBarDefaults.sizes(
            tabBarContentPadding = PaddingValues(vertical = 6.dp, horizontal = 2.dp),
            tabExpandedContentPadding = PaddingValues(vertical = 8.dp, horizontal = 14.dp),
            tabInlineContentPadding = PaddingValues(14.dp),
            componentSpacing = 14.dp,
        ),
        expandedTabs = { tabsModifier, _ ->
            CompositionLocalProvider(LocalGlassEffectConfig provides barConfig) {
                LiquidBottomTabs(
                    selectedTabIndex = selectedTabIndex,
                    onTabSelected = select,
                    backdrop = backdrop,
                    tabsCount = tabs.size,
                    accentColor = tabAccent,
                    containerColor = GlassFilm,
                    indicatorColor = puckWash,
                    height = BarHeight,
                    lensDepth = 20.dp,
                    modifier = tabsModifier,
                    drawContainer = !com.music.bitchord.glass.ui.floatingtabbar.LocalGeometricFloatingTabBar.current,
                ) {
                    tabs.forEachIndexed { index, tab ->
                        BarTab(tab.label, squareIcon(index, searchIndex), index == selectedIndex, tabAccent, barInk) {
                            select(index)
                        }
                    }
                }
            }
        },
        contentKey = listOf(selectedIndex, searching, searchOpen, tabAccent, barInk, tabs),
        expandedTabsHeight = BarHeight,
        standaloneInExpanded = false,
        searchMode = fieldShown,
        searchBarContent = if (fieldShown) {
            { fieldModifier ->
                BarSearchField(query, onQueryChange, onSearchSubmit, searchPlaceholder, fieldModifier, barInk)
            }
        } else null,
        tabsFillWidth = true,
        inlineAccessory = accessory,
        expandedAccessory = accessory,
    ) {
        tabs.forEachIndexed { index, tab ->
            val selected = index == selectedIndex
            tab(
                key = if (index == searchIndex) SEARCH_CIRCLE else index,
                title = { TabLabel(tab.label, if (selected) tabAccent else barInk) },
                icon = {
                    Icon(
                        squareIcon(index, searchIndex),
                        contentDescription = tab.label,
                        tint = if (selected) tabAccent else barInk,
                        modifier = Modifier.size(TabIcon),
                    )
                },
                onClick = { select(index) },
            )
        }
        if (!searching) {
            standaloneTab(
                key = SEARCH_CIRCLE,
                icon = {
                    Icon(
                        PhosphorIcons.Bold.MagnifyingGlass,
                        contentDescription = tabs.lastOrNull()?.label,
                        tint = barInk,
                        modifier = Modifier.size(TabIcon),
                    )
                },
                onClick = { select(searchIndex) },
            )
        }
    }
}

@Composable
private fun TabLabel(text: String, color: Color) {
    Text(
        text,
        fontSize = 9.sp,
        lineHeight = 11.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = LocalTextStyle.current.copy(
            platformStyle = PlatformTextStyle(includeFontPadding = false),
            lineHeightStyle = LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Center,
                trim = LineHeightStyle.Trim.Both,
            ),
        ),
        fontWeight = FontWeight.Bold,
        color = color,
    )
}

/** One place in the open bar: Square's BarTab. */
@Composable
private fun RowScope.BarTab(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    accent: Color,
    ink: Color,
    onClick: () -> Unit,
) {
    LiquidBottomTab(onClick = onClick) {
        Icon(icon, contentDescription = label, tint = if (selected) accent else ink, modifier = Modifier.size(TabIcon))
        TabLabel(label, if (selected) accent else ink)
    }
}

/** Square's search field, grown out of the bar; BitChord's query and submit. */
@Composable
private fun BarSearchField(
    query: String,
    onQuery: (String) -> Unit,
    onSubmit: () -> Unit,
    placeholder: String,
    modifier: Modifier,
    ink: Color,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    var field by remember { mutableStateOf(TextFieldValue(query, TextRange(query.length))) }
    LaunchedEffect(query) {
        if (field.text != query) field = TextFieldValue(query, TextRange(query.length))
    }
    Row(modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            PhosphorIcons.Bold.MagnifyingGlass,
            contentDescription = null,
            tint = ink.copy(alpha = 0.7f),
            modifier = Modifier.size(20.dp),
        )
        BasicTextField(
            value = field,
            onValueChange = {
                field = it
                if (it.text != query) onQuery(it.text)
            },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = ink),
            cursorBrush = SolidColor(ink),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp)
                .focusRequester(focus),
            decorationBox = { inner ->
                if (field.text.isEmpty()) {
                    Text(
                        placeholder,
                        style = MaterialTheme.typography.bodyLarge,
                        color = ink.copy(alpha = 0.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                inner()
            },
        )
        if (field.text.isNotEmpty()) {
            Icon(
                PhosphorIcons.Fill.XCircle,
                contentDescription = null,
                tint = ink.copy(alpha = 0.55f),
                modifier = Modifier
                    .size(22.dp)
                    .pressable(onClick = { onQuery("") }, pressedScale = 0.9f),
            )
        }
    }
}
