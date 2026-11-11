package au.projectwork.parking.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import au.projectwork.parking.R
import au.projectwork.parking.ui.theme.AuBlue
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.semantics
import kotlin.math.max
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import au.projectwork.parking.data.Spot
import au.projectwork.parking.ui.theme.TicketColors
import au.projectwork.parking.ui.theme.TicketType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

/** A ticket torn off a roll: square top, perforated bottom edge. */
private class TicketShape(private val radius: Dp, private val pitch: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = with(density) { radius.toPx() }
        val step = with(density) { pitch.toPx() }
        val path = Path().apply {
            moveTo(0f, 0f)
            lineTo(size.width, 0f)
            lineTo(size.width, size.height)
            // semicircle bites along the bottom, right to left
            var cx = size.width - step / 2
            while (cx - r > 0) {
                lineTo(cx + r, size.height)
                arcTo(Rect(Offset(cx, size.height), r), 0f, -180f, false)
                cx -= step
            }
            lineTo(0f, size.height)
            close()
        }
        return Outline.Generic(path)
    }
}

private val TicketEdge = TicketShape(radius = 4.dp, pitch = 12.dp)

@Composable
fun Ticket(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier
            .clip(TicketEdge)
            .background(TicketColors.current.paper)
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 18.dp),
        content = content,
    )
}

/** The dashed line a ticket would tear along. */
@Composable
fun TearRule(modifier: Modifier = Modifier) {
    val color = TicketColors.current.hairline
    Canvas(modifier.fillMaxWidth().height(1.dp)) {
        drawLine(
            color, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
        )
    }
}

@Composable
fun ParkingPlate(modifier: Modifier = Modifier) {
    val c = TicketColors.current
    Box(
        modifier.size(24.dp).background(c.plate, RoundedCornerShape(3.dp)).clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text("P", color = c.onPlate, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

/** Rubber-stamped over a full lot: the lot stays listed, it is cancelled, not hidden. */
@Composable
fun FullStamp(modifier: Modifier = Modifier) {
    val c = TicketColors.current
    Text(
        "FULL",
        style = TicketType.count.copy(fontSize = 26.sp, lineHeight = 30.sp),
        color = c.stamp,
        modifier = modifier
            .rotate(-4f)
            .border(2.dp, c.stamp, RoundedCornerShape(2.dp))
            .padding(horizontal = 8.dp),
    )
}

// material "star_border"; the core icon set only ships the filled star
private val StarBorder = ImageVector.Builder("StarBorder", 24.dp, 24.dp, 24f, 24f).addPath(
    addPathNodes(
        "M22,9.24l-7.19,-0.62L12,2 9.19,8.63 2,9.24l5.46,4.73L5.82,21 12,17.27 18.18,21l-1.63,-7.03L22,9.24z" +
            "M12,15.4l-3.76,2.27 1,-4.28 -3.32,-2.88 4.38,-0.38L12,6.1l1.71,4.04 4.38,0.38 -3.32,2.88 1,4.28L12,15.4z",
    ),
    fill = SolidColor(androidx.compose.ui.graphics.Color.Black),
).build()

@Composable
fun FavouriteToggle(favourite: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    IconToggleButton(checked = favourite, onCheckedChange = { onToggle() }, modifier = modifier) {
        Icon(
            if (favourite) Icons.Filled.Star else StarBorder,
            contentDescription = if (favourite) "Remove from favourites" else "Add to favourites",
            tint = TicketColors.current.ink,
        )
    }
}

/** Current time, ticking every second, so stamps can age while the screen is open. */
@Composable
fun rememberNow(): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(1_000)
            value = System.currentTimeMillis()
        }
    }
    return now
}

private val TimeOfDay = DateTimeFormatter.ofPattern("HH:mm:ss")
private val DayAndTime = DateTimeFormatter.ofPattern("d MMM HH:mm")

/**
 * Data older than two missed slow polls (60 s, used while the update stream is up) is stale.
 * With the stream down the app polls every 5 s and a failed fetch already shows as offline.
 */
private const val STALE_MS = 2 * 60_000 + 5_000

/** Whether numbers fetched at [fetchedAt] may no longer be true: offline, or not refreshed for two polls. */
fun isStale(fetchedAt: Long, offline: Boolean, now: Long) = offline || now - fetchedAt > STALE_MS

/** Ink for a count: full ink when fresh, faded once the number may be out of date. */
@Composable
fun countInk(stale: Boolean) = if (stale) TicketColors.current.faded else TicketColors.current.ink

/**
 * "Printed 14:32:07": when the numbers were true. Reprints (ink darkens in) when [printKey] changes, i.e. the
 * numbers changed, or when fresh data returns after a stale spell, and fades with the reason once stale.
 */
@Composable
fun PrintStamp(fetchedAt: Long, offline: Boolean, printKey: Any?, modifier: Modifier = Modifier) {
    val c = TicketColors.current
    val stale = isStale(fetchedAt, offline, rememberNow())
    val zone = ZoneId.systemDefault()
    val at = Instant.ofEpochMilli(fetchedAt).atZone(zone)
    val time = if (at.toLocalDate() == LocalDate.now(zone)) at.format(TimeOfDay) else at.format(DayAndTime)
    val ink = remember { Animatable(1f) }
    LaunchedEffect(printKey, stale) {
        if (!stale) {
            ink.snapTo(0.35f)
            ink.animateTo(1f, tween(600))
        }
    }
    val text = when {
        offline -> "Last printed $time · offline"
        stale -> "Last printed $time · updating"
        else -> "Printed $time"
    }
    Text(text, style = TicketType.stamp, color = if (stale) c.faded else c.ink, modifier = modifier.alpha(ink.value))
}

/** Top-bar status: a filled dot and LIVE while numbers are fresh, a hollow dot and OFFLINE otherwise. */
@Composable
fun LiveIndicator(stale: Boolean, modifier: Modifier = Modifier) {
    val c = TicketColors.current
    Row(
        modifier.padding(end = 16.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val dot = Modifier.size(8.dp)
        Box(if (stale) dot.border(1.5.dp, c.faded, CircleShape) else dot.background(c.ink, CircleShape))
        Text(if (stale) "OFFLINE" else "LIVE", style = TicketType.field, color = if (stale) c.faded else c.ink)
    }
}

/** Narrowest a bay may get before the strip wraps (or, with [maxLines], cuts off). */
private val MinBay = 20.dp

/**
 * The lot's bays as painted on the floor, one strip per line: free bays are open paint lines with their number,
 * taken bays hold a parked car. Shape carries the state, not colour. Neighbouring bays share one paint line.
 */
@Composable
fun PaintedBays(
    spots: List<Spot>,
    modifier: Modifier = Modifier,
    maxBayWidth: Dp = 40.dp,
    bayHeight: Dp = 56.dp,
    maxLines: Int = Int.MAX_VALUE,
) {
    if (spots.isEmpty()) return
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val perLine = max(1, (maxWidth / MinBay).toInt())
        val bay = minOf(maxBayWidth, maxWidth / minOf(perLine, spots.size))
        val lines = spots.chunked(perLine)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            lines.take(maxLines).forEach { BayStrip(it, bay, bayHeight) }
            val hidden = lines.drop(maxLines).sumOf { it.size }
            if (hidden > 0) FieldLabel("+$hidden more bays")
        }
    }
}

@Composable
private fun BayStrip(spots: List<Spot>, bay: Dp, height: Dp) {
    val c = TicketColors.current
    Row(Modifier.drawBehind {
        val paint = 2.dp.toPx()
        // back line across the strip, then one shared line at every bay boundary
        drawRect(c.ink, Offset.Zero, Size(size.width, paint))
        for (i in 0..spots.size) {
            val x = (i * bay.toPx()).coerceAtMost(size.width - paint)
            drawRect(c.ink, Offset(x, 0f), Size(paint, size.height))
        }
    }) {
        spots.forEach { spot ->
            val label = if (spot.occupied) "taken" else "free"
            Box(
                Modifier.size(bay, height).clearAndSetSemantics { contentDescription = "Spot ${spot.id}, $label" },
                contentAlignment = Alignment.BottomCenter,
            ) {
                if (spot.occupied) {
                    Box(
                        Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = bay * 0.18f)
                            .fillMaxWidth()
                            .height(height * 0.62f)
                            .background(c.ink, RoundedCornerShape(percent = 30)),
                    )
                } else {
                    // the free bays are the answer: their numbers are printed in full ink
                    Text(
                        "${spot.id}",
                        style = TicketType.stamp.copy(fontSize = if (bay < 32.dp) 10.sp else 12.sp),
                        color = c.ink,
                        maxLines = 1,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            }
        }
    }
}

/** "free" / "taken" key under a bay drawing. */
@Composable
fun BayLegend(modifier: Modifier = Modifier) {
    val c = TicketColors.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(14.dp, 18.dp).drawBehind {
                val p = 1.5.dp.toPx()
                drawRect(c.ink, Offset.Zero, Size(p, size.height)); drawRect(c.ink, Offset(size.width - p, 0f), Size(p, size.height))
                drawRect(c.ink, Offset.Zero, Size(size.width, p))
            })
            Text("free", style = TicketType.stamp, color = c.ink)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(14.dp, 18.dp).padding(2.dp).background(c.ink, RoundedCornerShape(percent = 30)))
            Text("taken", style = TicketType.stamp, color = c.ink)
        }
    }
}

// material "navigation" arrow: Navigate is an action, not a place, so not the location pin
val NavigationArrow = ImageVector.Builder("Navigation", 24.dp, 24.dp, 24f, 24f).addPath(
    addPathNodes("M12,2L4.5,20.29l0.71,0.71L12,18l6.79,3 0.71,-0.71z"),
    fill = SolidColor(androidx.compose.ui.graphics.Color.Black),
).build()

/** Aarhus University's mark (from the supplied logo, wordmark dropped): AU blue by day, ink at night. */
@Composable
fun AuMark(modifier: Modifier = Modifier) {
    val dark = TicketColors.current.ground.luminance() < 0.5f
    Image(
        painterResource(R.drawable.au_mark),
        contentDescription = null, // decorative: the title next to it already says "AU Parking"
        colorFilter = ColorFilter.tint(if (dark) TicketColors.current.ink else AuBlue),
        modifier = modifier.height(22.dp).aspectRatio(2f),
    )
}

/** A field label printed on a ticket, e.g. "NEAREST WITH SPACE". */
@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = TicketType.field, color = TicketColors.current.faded, modifier = modifier)
}

