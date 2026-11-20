package au.projectwork.parking.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import au.projectwork.parking.R

/** The thermal-ticket palette; Material roles are mapped from it in [ParkingTheme]. */
@Immutable
data class TicketPalette(
    val ground: Color,   // what the tickets lie on
    val paper: Color,    // the ticket itself
    val ink: Color,
    val faded: Color,    // secondary print, still >= 4.5:1 on paper
    val hairline: Color, // tear rules, outline variants
    val parking: Color,  // Danish parking blue: primary action colour (buttons)
    val onParking: Color,
    val stamp: Color,    // FULL stamp only
) {
    // the P plate is a road sign: parking blue with a white P in both themes
    val plate get() = Color(0xFF0B4EA2)
    val onPlate get() = Color.White
}

/** Aarhus University blue, sampled from the supplied logo; the mark prints in it on light paper. */
val AuBlue = Color(0xFF023E86)

private val Light = TicketPalette(
    ground = Color(0xFFE2E2DC),
    paper = Color(0xFFF4F4F0),
    ink = Color(0xFF1B1C1E),
    faded = Color(0xFF64645F),
    hairline = Color(0xFFB4B4AE),
    parking = Color(0xFF0B4EA2),
    onParking = Color.White,
    stamp = Color(0xFFB72D1A),
)

// night: tickets read like paper under a car's interior light, not inverted
private val Dark = TicketPalette(
    ground = Color(0xFF26282B),
    paper = Color(0xFF34363A),
    ink = Color(0xFFECECE6),
    faded = Color(0xFFB4B4AE),
    hairline = Color(0xFF55575C),
    parking = Color(0xFFA9C7FF),
    onParking = Color(0xFF062E63),
    stamp = Color(0xFFFF8A75),
)

val TicketColors = staticCompositionLocalOf { Light }

/** Numbers, times and distances: measured values, set in a monospace so they never jitter on refresh. */
val Mono = FontFamily(
    Font(R.font.roboto_mono_medium, FontWeight.Medium),
    Font(R.font.roboto_mono_bold, FontWeight.Bold),
)

object TicketType {
    val heroCount = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 96.sp, lineHeight = 96.sp, letterSpacing = (-0.04).em)
    val count = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 40.sp, lineHeight = 44.sp, letterSpacing = (-0.03).em)
    val unit = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 18.sp)
    val field = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.06.em)
    val stamp = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp)
}

@Composable
fun ParkingTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val p = if (dark) Dark else Light
    val roles = if (dark) darkColorScheme() else lightColorScheme()
    val scheme = roles.copy(
        primary = p.parking, onPrimary = p.onParking,
        background = p.ground, onBackground = p.ink,
        surface = p.ground, onSurface = p.ink,
        surfaceContainer = p.paper, surfaceContainerLow = p.paper, surfaceContainerHigh = p.paper,
        onSurfaceVariant = p.faded,
        // selected chip = inked
        secondaryContainer = p.ink, onSecondaryContainer = p.paper,
        outline = p.ink, outlineVariant = p.hairline,
        error = p.stamp,
    )
    val base = Typography()
    val typography = base.copy(
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Medium),
    )
    CompositionLocalProvider(TicketColors provides p) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}
