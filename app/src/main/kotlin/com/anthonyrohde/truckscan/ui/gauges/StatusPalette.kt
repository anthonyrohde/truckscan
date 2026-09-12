package com.anthonyrohde.truckscan.ui.gauges

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.anthonyrohde.truckscan.core.pid.ZoneSeverity

/**
 * Status colours for gauge zones and warning lights.
 *
 * These four steps are a validated status palette: each clears 3:1 against the
 * dark surface this app uses by default, and they stay distinct from one
 * another under colour-vision deficiency simulation.
 *
 * Colour never carries the meaning alone. Every use here is paired with an
 * icon and a written label, because this screen gets read at a glance, in
 * daylight, by someone driving - conditions where hue is the first thing to go.
 */
object StatusPalette {
    val good = Color(0xFF0CA30C)
    val caution = Color(0xFFFAB219)
    val warning = Color(0xFFEC835A)
    val critical = Color(0xFFD03B3B)

    fun color(severity: ZoneSeverity): Color = when (severity) {
        ZoneSeverity.NORMAL -> good
        ZoneSeverity.CAUTION -> caution
        ZoneSeverity.WARNING -> warning
        ZoneSeverity.CRITICAL -> critical
    }

    fun icon(severity: ZoneSeverity): ImageVector = when (severity) {
        ZoneSeverity.NORMAL -> Icons.Filled.CheckCircle
        ZoneSeverity.CAUTION -> Icons.Filled.ReportProblem
        ZoneSeverity.WARNING -> Icons.Filled.Warning
        ZoneSeverity.CRITICAL -> Icons.Filled.Error
    }

    /** Word for the severity, so the state is never inferred from colour. */
    fun word(severity: ZoneSeverity): String = when (severity) {
        ZoneSeverity.NORMAL -> "OK"
        ZoneSeverity.CAUTION -> "Watch"
        ZoneSeverity.WARNING -> "Warning"
        ZoneSeverity.CRITICAL -> "Critical"
    }

    @Composable
    fun colorFor(severity: ZoneSeverity): Color = color(severity)
}
