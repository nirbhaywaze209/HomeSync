package com.homesync.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color


private val ChildColorScheme = lightColorScheme(
    primary = ChildPrimary,
    secondary = ChildAmber,
    tertiary = ChildEmerald,
    background = ChildBg,
    surface = ChildCardBg,
    onPrimary = Color.White,
    onBackground = ChildTextPrimary,
    onSurface = ChildTextPrimary
)

private val GuardianColorScheme = darkColorScheme(
    primary = ParentPrimary,
    secondary = ParentTeal,
    tertiary = ParentEmerald,
    background = ParentDarkBg,
    surface = ParentCardBg,
    onPrimary = Color.Black,
    onBackground = ParentTextPrimary,
    onSurface = ParentTextPrimary
)

private val ChildLoginColorScheme = lightColorScheme(
    primary = PastelGreenDark,
    secondary = PastelBlueSoft,
    tertiary = PastelYellowWarm,
    background = PastelGreenBg,
    surface = PastelCardBg,
    onPrimary = Color.White,
    onBackground = PastelTextDark,
    onSurface = PastelTextDark
)

private val GuardianLightColorScheme = lightColorScheme(
    primary = GuardianBlueAccent,
    secondary = GuardianGreenAccent,
    tertiary = GuardianBlueSoft,
    background = GuardianLightBg,
    surface = GuardianLightCard,
    onPrimary = Color.White,
    onBackground = GuardianTextDark,
    onSurface = GuardianTextDark
)

private val LockoutColorScheme = darkColorScheme(
    primary = NightEmergencyRed,
    secondary = NightMoonGlow,
    tertiary = NightStarWhite,
    background = NightSkyDark,
    surface = NightSkyMid,
    onPrimary = Color.White,
    onBackground = NightStarWhite,
    onSurface = NightStarWhite
)

@Composable
fun HomeSyncTheme(
    isChildMode: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (isChildMode) ChildColorScheme else GuardianColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

@Composable
fun ChildLoginTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = ChildLoginColorScheme,
        content = content
    )
}

@Composable
fun GuardianLightTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = GuardianLightColorScheme,
        content = content
    )
}

@Composable
fun LockoutTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = LockoutColorScheme,
        content = content
    )
}