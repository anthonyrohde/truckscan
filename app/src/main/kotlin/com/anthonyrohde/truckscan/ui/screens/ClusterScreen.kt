package com.anthonyrohde.truckscan.ui.screens

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anthonyrohde.truckscan.ScanViewModel
import com.anthonyrohde.truckscan.core.cluster.Cluster
import com.anthonyrohde.truckscan.core.session.LiveDataSample
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * A dash cluster for a tablet mounted in the truck.
 *
 * Everything numeric - needle angles, tick positions, band arcs, clamping, the
 * boost subtraction - comes from [Cluster] in the core module, which has tests.
 * This file only turns those numbers into pixels. That split is not tidiness:
 * the Android module cannot be compiled in the environment this is written in,
 * so any arithmetic placed here would be unverifiable until it appeared, wrong,
 * on a screen in a truck.
 *
 * Designed for a large landscape tablet at arm's length in daylight: dark
 * ground, few colours, numbers big enough to read without looking twice, and
 * nothing that moves except the needles.
 */

private val Ground = Color(0xFF08090C)
private val Face = Color(0xFF101319)
private val Tick = Color(0xFF6E7787)
private val TickMajor = Color(0xFFD6DCE5)
private val Needle = Color(0xFF3FC8FF)
private val Accent = Color(0xFFFF7A18)
private val Caution = Color(0xFFFFB300)
private val Danger = Color(0xFFE03131)
private val Label = Color(0xFF8A93A3)
private val Reading = Color(0xFFF2F5FA)

@Composable
fun ClusterScreen(viewModel: ScanViewModel) {
    val sample by viewModel.liveSample.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // A dash display that sleeps is not a dash display.
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // Lets the face be judged without a vehicle. Everything about it shouts
    // that it is not a reading, because a gauge that lies convincingly is the
    // worst thing this app could do.
    var demo by remember { mutableStateOf(false) }

    val effective = if (demo) LiveDataSample(values = Cluster.demoValues()) else sample
    val readings = viewModel.clusterReadings(effective)
    val byId = readings.associateBy { it.dial.id }

    Box(Modifier.fillMaxSize().background(Ground)) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {

            if (demo) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(Accent)
                        .padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "DEMONSTRATION - THESE ARE INVENTED NUMBERS, NOT YOUR TRUCK",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                    )
                }
            }

            // Top row: the four small arcs, as on the truck's own cluster.
            Row(
                Modifier.fillMaxWidth().weight(0.9f),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                for (dial in Cluster.LAYOUT.arcs) {
                    byId[dial.id]?.let { reading ->
                        Box(Modifier.weight(1f).fillMaxHeight()) { SmallArc(reading) }
                    }
                }
            }

            // Middle: the two large dials with the data panel between them.
            Row(
                Modifier.fillMaxWidth().weight(2.4f),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                byId["tach"]?.let { Box(Modifier.weight(1f).fillMaxHeight()) { BigDial(it) } }

                Box(Modifier.weight(1.1f).fillMaxHeight()) {
                    Panel(viewModel, sample)
                }

                byId["speed"]?.let { Box(Modifier.weight(1f).fillMaxHeight()) { BigDial(it) } }
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Driven by the sample rather than viewModel.isStreaming: the
                // latter is a plain property, so composition would never be
                // told it changed and the button would stay wrong.
                if (sample != null) {
                    Button(onClick = { viewModel.stopLiveData() }) { Text("Stop") }
                } else {
                    Button(onClick = { viewModel.startCluster() }) { Text("Start cluster") }
                }
                Button(onClick = { demo = !demo }) {
                    Text(if (demo) "Leave demo" else "Demo layout")
                }
                if (!demo) {
                    sample?.let {
                        Text(
                            "sweep ${it.sweepMillis} ms",
                            color = Label,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ big dial

@Composable
private fun BigDial(reading: Cluster.Reading) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .aspectRatio(1f)
                .fillMaxHeight()
                .drawBehind { drawDial(reading, big = true) },
        )
    }
}

@Composable
private fun SmallArc(reading: Cluster.Reading) {
    Box(Modifier.fillMaxSize().drawBehind { drawDial(reading, big = false) })
}

/**
 * Draws one dial.
 *
 * The only geometry decided here is where things sit on the canvas. Every angle
 * comes from [Cluster].
 */
private fun DrawScope.drawDial(reading: Cluster.Reading, big: Boolean) {
    val dial = reading.dial
    val radius = min(size.width, size.height) / 2f * if (big) 0.92f else 0.98f
    val centre = if (big) {
        Offset(size.width / 2f, size.height / 2f)
    } else {
        // A half-circle arc wastes the bottom of its box, so it is pushed down
        // and the label put underneath the sweep rather than inside it.
        Offset(size.width / 2f, size.height * 0.74f)
    }
    val ringWidth = radius * if (big) 0.09f else 0.14f

    if (big) {
        drawCircle(Face, radius = radius, center = centre)
    }

    // The sweep itself, dim, so the bands and needle read against it.
    drawArc(
        color = Color(0xFF232935),
        startAngle = dial.startAngleDeg.toFloat(),
        sweepAngle = dial.sweepDeg.toFloat(),
        useCenter = false,
        topLeft = Offset(centre.x - radius, centre.y - radius),
        size = Size(radius * 2, radius * 2),
        style = Stroke(width = ringWidth),
    )

    for (band in Cluster.bandsFor(dial)) {
        drawArc(
            color = if (band.danger) Danger else Caution,
            startAngle = band.startAngleDeg.toFloat(),
            sweepAngle = band.sweepDeg.toFloat(),
            useCenter = false,
            topLeft = Offset(centre.x - radius, centre.y - radius),
            size = Size(radius * 2, radius * 2),
            style = Stroke(width = ringWidth),
        )
    }

    // Filled portion up to the needle, which reads at a glance where a bare
    // needle does not.
    reading.fraction?.let { fraction ->
        drawArc(
            color = when (reading.severity) {
                Cluster.Severity.DANGER -> Danger
                Cluster.Severity.CAUTION -> Caution
                Cluster.Severity.NORMAL -> Needle
            }.copy(alpha = 0.35f),
            startAngle = dial.startAngleDeg.toFloat(),
            sweepAngle = (dial.sweepDeg * fraction).toFloat(),
            useCenter = false,
            topLeft = Offset(centre.x - radius, centre.y - radius),
            size = Size(radius * 2, radius * 2),
            style = Stroke(width = ringWidth),
        )
    }

    val tickOuter = radius - ringWidth * 0.6f
    for (tick in Cluster.ticksFor(dial)) {
        val rad = Math.toRadians(tick.angleDeg)
        val cosA = cos(rad).toFloat()
        val sinA = sin(rad).toFloat()
        val inner = tickOuter - radius * (if (tick.major) 0.13f else 0.07f)
        drawLine(
            color = if (tick.major) TickMajor else Tick,
            start = Offset(centre.x + cosA * inner, centre.y + sinA * inner),
            end = Offset(centre.x + cosA * tickOuter, centre.y + sinA * tickOuter),
            strokeWidth = if (tick.major) radius * 0.022f else radius * 0.011f,
        )

        if (tick.major && tick.label != null && big) {
            val labelRadius = inner - radius * 0.11f
            drawNativeText(
                text = tick.label!!,
                x = centre.x + cosA * labelRadius,
                y = centre.y + sinA * labelRadius,
                sizePx = radius * 0.13f,
                color = TickMajor,
                bold = true,
            )
        }
    }

    // Needle. Nothing is drawn when there is no reading: a needle resting at
    // zero is a claim that the value is zero.
    reading.angleDeg?.let { angle ->
        rotate(degrees = angle.toFloat(), pivot = centre) {
            val length = radius * if (big) 0.78f else 0.70f
            drawLine(
                color = Accent,
                start = Offset(centre.x - radius * 0.10f, centre.y),
                end = Offset(centre.x + length, centre.y),
                strokeWidth = radius * (if (big) 0.030f else 0.045f),
            )
        }
        drawCircle(Accent, radius = radius * (if (big) 0.055f else 0.05f), center = centre)
        drawCircle(Ground, radius = radius * (if (big) 0.028f else 0.024f), center = centre)
    }

    // Label and reading.
    if (big) {
        drawNativeText(
            text = dial.label,
            x = centre.x, y = centre.y + radius * 0.34f,
            sizePx = radius * 0.10f, color = Label, bold = false,
        )
        drawNativeText(
            text = reading.text,
            x = centre.x, y = centre.y + radius * 0.62f,
            sizePx = radius * 0.30f, color = Reading, bold = true,
        )
    } else {
        drawNativeText(
            text = dial.label,
            x = centre.x, y = centre.y - radius * 0.58f,
            sizePx = radius * 0.15f, color = Label, bold = false,
        )
        drawNativeText(
            text = reading.text,
            x = centre.x, y = centre.y - radius * 0.16f,
            sizePx = radius * 0.34f,
            color = if (reading.supported) Reading else Label,
            bold = true,
        )
        // An unconfirmed decode is marked on the face. A number that might be
        // scaled wrong should not look identical to one that cannot be.
        if (!reading.verified && reading.supported) {
            drawNativeText(
                text = "unconfirmed",
                x = centre.x, y = centre.y + radius * 0.12f,
                sizePx = radius * 0.11f, color = Caution, bold = false,
            )
        }
    }
}

/**
 * Text on a canvas, centred horizontally on [x] with its baseline at [y].
 *
 * Compose's own text measurement is not available inside a plain DrawScope
 * without threading a TextMeasurer through every call, and the platform Paint
 * centres text in one line.
 */
private fun DrawScope.drawNativeText(
    text: String,
    x: Float,
    y: Float,
    sizePx: Float,
    color: Color,
    bold: Boolean,
) {
    val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        textAlign = android.graphics.Paint.Align.CENTER
        textSize = sizePx
        this.color = android.graphics.Color.argb(
            (color.alpha * 255).toInt(),
            (color.red * 255).toInt(),
            (color.green * 255).toInt(),
            (color.blue * 255).toInt(),
        )
        typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.SANS_SERIF,
            if (bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL,
        )
    }
    drawContext.canvas.nativeCanvas.drawText(text, x, y, paint)
}

// --------------------------------------------------------------------- panel

@Composable
private fun Panel(
    viewModel: ScanViewModel,
    sample: LiveDataSample?,
) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        for (row in Cluster.LAYOUT.readouts.chunked(2)) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                for (readout in row) {
                    Column(Modifier.weight(1f)) {
                        Text(readout.label, color = Label, fontSize = 11.sp)
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                viewModel.clusterReadout(readout, sample),
                                color = Reading,
                                fontSize = 26.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                            )
                            Text(
                                " ${readout.unit}",
                                color = Label,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(bottom = 4.dp),
                            )
                        }
                    }
                }
                if (row.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}
