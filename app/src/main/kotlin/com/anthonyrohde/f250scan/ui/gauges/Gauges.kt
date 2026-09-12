package com.anthonyrohde.f250scan.ui.gauges

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anthonyrohde.f250scan.core.pid.GaugeZone
import com.anthonyrohde.f250scan.core.pid.Pid
import com.anthonyrohde.f250scan.core.pid.PidValue
import com.anthonyrohde.f250scan.core.pid.ZoneSeverity
import kotlin.math.cos
import kotlin.math.sin

private const val DIAL_START_ANGLE = 135f
private const val DIAL_SWEEP = 270f
private const val ARC_START_ANGLE = 180f
private const val ARC_SWEEP = 180f

/**
 * A circular dial with a needle, for the two parameters that have an
 * instinctive analogue form: engine speed and road speed.
 *
 * The value is also printed in the middle. That is not redundancy for its own
 * sake - a needle answers "roughly where am I" at a glance while driving, and
 * the number answers "exactly what is it" when stopped, and the two questions
 * get asked at different moments.
 */
@Composable
fun DialGauge(
    pid: Pid,
    value: PidValue?,
    modifier: Modifier = Modifier,
) {
    val severity = value?.severity ?: ZoneSeverity.NORMAL
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val tickColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    val needleColor = MaterialTheme.colorScheme.onSurface
    val accent = StatusPalette.color(severity)
    val fraction = value?.let { pid.normalise(it.value) } ?: 0.0

    Box(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(6.dp)) {
            val stroke = size.minDimension * 0.085f
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(inset, inset)

            drawArc(
                color = trackColor,
                startAngle = DIAL_START_ANGLE,
                sweepAngle = DIAL_SWEEP,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )

            // Zone bands sit on the track so the redline is visible before the
            // needle ever reaches it.
            pid.zones.forEach { zone ->
                if (zone.severity == ZoneSeverity.NORMAL) return@forEach
                drawZoneBand(pid, zone, topLeft, arcSize, stroke, DIAL_START_ANGLE, DIAL_SWEEP)
            }

            drawTicks(tickColor, DIAL_START_ANGLE, DIAL_SWEEP, stroke)

            // Progress and needle.
            drawArc(
                color = accent,
                startAngle = DIAL_START_ANGLE,
                sweepAngle = (DIAL_SWEEP * fraction).toFloat(),
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            drawNeedle(needleColor, DIAL_START_ANGLE + DIAL_SWEEP * fraction.toFloat(), stroke)
        }

        GaugeReadout(pid, value, compact = false)
    }
}

/**
 * A half-circle arc, for everything that is a level or a temperature.
 *
 * Cheaper vertically than a full dial, which matters when eight of them share
 * a phone screen mounted on a dash.
 */
@Composable
fun ArcGauge(
    pid: Pid,
    value: PidValue?,
    modifier: Modifier = Modifier,
) {
    val severity = value?.severity ?: ZoneSeverity.NORMAL
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val accent = StatusPalette.color(severity)
    val fraction = value?.let { pid.normalise(it.value) } ?: 0.0

    Box(modifier.aspectRatio(1.8f), contentAlignment = Alignment.BottomCenter) {
        Canvas(Modifier.fillMaxSize().padding(horizontal = 6.dp)) {
            val stroke = size.height * 0.14f
            val diameter = minOf(size.width, size.height * 2f) - stroke
            val topLeft = Offset((size.width - diameter) / 2f, stroke / 2f)
            val arcSize = Size(diameter, diameter)

            drawArc(
                color = trackColor,
                startAngle = ARC_START_ANGLE,
                sweepAngle = ARC_SWEEP,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )

            pid.zones.forEach { zone ->
                if (zone.severity == ZoneSeverity.NORMAL) return@forEach
                drawZoneBand(pid, zone, topLeft, arcSize, stroke, ARC_START_ANGLE, ARC_SWEEP)
            }

            drawArc(
                color = accent,
                startAngle = ARC_START_ANGLE,
                sweepAngle = (ARC_SWEEP * fraction).toFloat(),
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }

        GaugeReadout(pid, value, compact = true)
    }
}

/** A plain bar, for counters where a sweep would imply a range that has no meaning. */
@Composable
fun BarGauge(
    pid: Pid,
    value: PidValue?,
    modifier: Modifier = Modifier,
) {
    val severity = value?.severity ?: ZoneSeverity.NORMAL
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val accent = StatusPalette.color(severity)
    val fraction = value?.let { pid.normalise(it.value) } ?: 0.0

    Column(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            pid.name,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value?.formatted ?: "--",
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
        )
        Canvas(Modifier.fillMaxWidth().height(8.dp).padding(top = 3.dp)) {
            val radius = size.height / 2f
            drawLine(
                color = trackColor,
                start = Offset(radius, size.height / 2f),
                end = Offset(size.width - radius, size.height / 2f),
                strokeWidth = size.height,
                cap = StrokeCap.Round,
            )
            if (fraction > 0.0) {
                drawLine(
                    color = accent,
                    start = Offset(radius, size.height / 2f),
                    end = Offset(
                        radius + (size.width - 2 * radius) * fraction.toFloat(),
                        size.height / 2f,
                    ),
                    strokeWidth = size.height,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

/** Picks the right gauge for the parameter. */
@Composable
fun Gauge(pid: Pid, value: PidValue?, modifier: Modifier = Modifier) {
    when (pid.style) {
        com.anthonyrohde.f250scan.core.pid.GaugeStyle.DIAL -> DialGauge(pid, value, modifier)
        com.anthonyrohde.f250scan.core.pid.GaugeStyle.ARC -> ArcGauge(pid, value, modifier)
        com.anthonyrohde.f250scan.core.pid.GaugeStyle.BAR -> BarGauge(pid, value, modifier)
    }
}

// ------------------------------------------------------------------ internals

/**
 * The number, unit and name.
 *
 * Text stays in the theme's ink colours rather than taking the status colour:
 * the coloured band beside it already carries the state, and a number that
 * changes colour is harder to read at a glance than one that does not.
 */
@Composable
private fun GaugeReadout(pid: Pid, value: PidValue?, compact: Boolean) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(bottom = if (compact) 2.dp else 0.dp),
    ) {
        Text(
            text = value?.let { pid.format(it.value).substringBefore(' ') } ?: "--",
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = if (compact) 24.sp else 30.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (pid.unit.isNotEmpty()) {
            Text(
                pid.unit,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            pid.shortName,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Paints one zone as a band on the track, positioned by the parameter's scale. */
private fun DrawScope.drawZoneBand(
    pid: Pid,
    zone: GaugeZone,
    topLeft: Offset,
    arcSize: Size,
    stroke: Float,
    startAngle: Float,
    totalSweep: Float,
) {
    val from = pid.normalise(zone.from).toFloat()
    val to = pid.normalise(zone.to).toFloat()
    if (to <= from) return

    drawArc(
        color = StatusPalette.color(zone.severity).copy(alpha = 0.85f),
        startAngle = startAngle + totalSweep * from,
        sweepAngle = totalSweep * (to - from),
        useCenter = false,
        topLeft = topLeft,
        size = arcSize,
        style = Stroke(width = stroke * 0.34f, cap = StrokeCap.Butt),
    )
}

/** Recessive tick marks - orientation, not decoration. */
private fun DrawScope.drawTicks(
    color: Color,
    startAngle: Float,
    totalSweep: Float,
    stroke: Float,
    count: Int = 9,
) {
    val centre = Offset(size.width / 2f, size.height / 2f)
    val outer = size.minDimension / 2f - stroke * 1.15f
    val inner = outer - stroke * 0.55f

    for (i in 0..count) {
        val angle = Math.toRadians((startAngle + totalSweep * i / count).toDouble())
        drawLine(
            color = color,
            start = Offset(
                centre.x + inner * cos(angle).toFloat(),
                centre.y + inner * sin(angle).toFloat(),
            ),
            end = Offset(
                centre.x + outer * cos(angle).toFloat(),
                centre.y + outer * sin(angle).toFloat(),
            ),
            strokeWidth = 2f,
            cap = StrokeCap.Round,
        )
    }
}

private fun DrawScope.drawNeedle(color: Color, angleDegrees: Float, stroke: Float) {
    val centre = Offset(size.width / 2f, size.height / 2f)
    val angle = Math.toRadians(angleDegrees.toDouble())
    val length = size.minDimension / 2f - stroke * 1.5f

    drawLine(
        color = color,
        start = centre,
        end = Offset(
            centre.x + length * cos(angle).toFloat(),
            centre.y + length * sin(angle).toFloat(),
        ),
        strokeWidth = stroke * 0.22f,
        cap = StrokeCap.Round,
    )
    drawCircle(color = color, radius = stroke * 0.28f, center = centre)
}
