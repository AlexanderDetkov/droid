package com.adetkov.droid

import android.app.Activity
import android.content.ClipData
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.launch
import java.io.File

/** Droid's colors: quiet neutrals, one strong ink for primary actions, and Dash's periwinkle where he calls you. */
@Immutable
data class Palette(
    val dark: Boolean,
    val bg: Color,
    val drawer: Color,
    /** User bubbles, the composer, cards. */
    val surface: Color,
    /** Selected rows, pressed chips, the scroll-to-bottom button. */
    val raised: Color,
    /** Menus and sheets. */
    val sheet: Color,
    val hairline: Color,
    val text: Color,
    val muted: Color,
    val faint: Color,
    /** Primary buttons: send, voice, Done. */
    val ink: Color,
    val onInk: Color,
    val codeBg: Color,
    val codeHeader: Color,
    val danger: Color,
    val success: Color,
    /** "Your turn": the color of Dash's call (his signal rings), so the words and the character say it together. */
    val call: Color,
)

private val DarkPalette = Palette(
    dark = true,
    bg = Color(0xFF101010), drawer = Color(0xFF171717), surface = Color(0xFF242424), raised = Color(0xFF2C2C2C),
    sheet = Color(0xFF1E1E1E), hairline = Color(0xFF2E2E2E),
    text = Color(0xFFECECEC), muted = Color(0xFF9A9A9A), faint = Color(0xFF6A6A6A),
    ink = Color(0xFFF2F2F2), onInk = Color(0xFF0D0D0D),
    codeBg = Color(0xFF181818), codeHeader = Color(0xFF222222),
    danger = Color(0xFFF28B82), success = Color(0xFF81C995),
    call = Color(0xFFB3B7FF),
)

private val LightPalette = Palette(
    dark = false,
    bg = Color(0xFFFFFFFF), drawer = Color(0xFFF7F7F7), surface = Color(0xFFF2F2F2), raised = Color(0xFFE9E9E9),
    sheet = Color(0xFFFFFFFF), hairline = Color(0xFFE3E3E3),
    text = Color(0xFF0D0D0D), muted = Color(0xFF6B6B6B), faint = Color(0xFF9E9E9E),
    ink = Color(0xFF0D0D0D), onInk = Color(0xFFFFFFFF),
    codeBg = Color(0xFFF7F7F7), codeHeader = Color(0xFFEDEDED),
    danger = Color(0xFFD93025), success = Color(0xFF1E8E3E),
    call = Color(0xFF5A5FD6),
)

val LocalPalette = staticCompositionLocalOf { DarkPalette }

/** The palette of a theme, outside composition (a page's stylesheet takes its colors from it). */
fun palette(dark: Boolean): Palette = if (dark) DarkPalette else LightPalette

/** The current palette. */
val P: Palette @Composable @ReadOnlyComposable get() = LocalPalette.current

val Muted: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.muted

/**
 * The phone's own UI font (Google Sans Flex on Pixels) when it is there, else the default. Loaded from the file so
 * the variable font's weights are real weights, not synthetic bold.
 */
private val AppFont: FontFamily = try {
    val f = File("/product/fonts/GoogleSansFlex-Regular.ttf")
    if (!f.canRead()) FontFamily.Default
    else FontFamily(listOf(400, 500, 600, 700).map { w ->
        Font(f, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    })
} catch (_: Exception) { FontFamily.Default }

private fun type(): Typography {
    val base = Typography()
    fun TextStyle.f() = copy(fontFamily = AppFont)
    return Typography(
        displayLarge = base.displayLarge.f(), displayMedium = base.displayMedium.f(), displaySmall = base.displaySmall.f(),
        headlineLarge = base.headlineLarge.f(), headlineMedium = base.headlineMedium.f(),
        headlineSmall = base.headlineSmall.f().copy(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium),
        titleLarge = base.titleLarge.f().copy(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.f().copy(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.f().copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
        bodyLarge = base.bodyLarge.f().copy(fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.1.sp),
        bodyMedium = base.bodyMedium.f().copy(fontSize = 15.sp, lineHeight = 21.sp, letterSpacing = 0.1.sp),
        bodySmall = base.bodySmall.f().copy(fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.1.sp),
        labelLarge = base.labelLarge.f().copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
        labelMedium = base.labelMedium.f().copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
        labelSmall = base.labelSmall.f(),
    )
}

private val AppType = type()

@Composable
fun DroidTheme(content: @Composable () -> Unit) {
    val prefs = (LocalContext.current.applicationContext as DroidApp).prefs
    val mode by prefs.theme.collectAsState()
    val dark = when (mode) { ThemeMode.LIGHT -> false; ThemeMode.DARK -> true; ThemeMode.SYSTEM -> isSystemInDarkTheme() }
    val p = if (dark) DarkPalette else LightPalette
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = p.ink, onPrimary = p.onInk,
        background = p.bg, onBackground = p.text,
        surface = p.bg, onSurface = p.text,
        surfaceVariant = p.surface, onSurfaceVariant = p.muted,
        surfaceContainerLowest = p.bg, surfaceContainerLow = p.drawer, surfaceContainer = p.sheet,
        surfaceContainerHigh = p.sheet, surfaceContainerHighest = p.raised,
        secondaryContainer = p.raised, onSecondaryContainer = p.text,
        outline = p.hairline, outlineVariant = p.hairline,
        error = p.danger,
    )
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        (view.context as? Activity)?.window?.let { w ->
            WindowCompat.getInsetsController(w, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, typography = AppType) {
            // Text outside a Surface takes this color; without it, it would be black in the dark theme.
            CompositionLocalProvider(
                LocalContentColor provides p.text,
                LocalTextSelectionColors provides TextSelectionColors(p.text, p.text.copy(alpha = .25f)),
                content = content,
            )
        }
    }
}

/** A light tick for taps that change something (send, stop, copy), unless the user turned haptics off. */
@Composable
fun rememberHaptic(): () -> Unit {
    val view = LocalView.current
    val prefs = (LocalContext.current.applicationContext as DroidApp).prefs
    return { if (prefs.haptics.value) view.performHapticFeedback(HapticFeedbackConstants.CONFIRM) }
}

/** Puts text on the clipboard. */
@Composable
fun rememberCopy(): (String) -> Unit {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    return { text -> scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, text))) } }
}
