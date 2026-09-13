package com.anthonyrohde.truckscan.core.cluster

import com.anthonyrohde.truckscan.core.pid.MeasuredSupport
import com.anthonyrohde.truckscan.core.pid.PidCatalog
import com.anthonyrohde.truckscan.core.pid.PidValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The cluster's geometry, tested here because the screen that draws it cannot
 * be compiled in this environment at all.
 */
class ClusterTest {

    private fun value(key: String, v: Double): Pair<String, PidValue> {
        val pid = checkNotNull(PidCatalog.byKey(key)) { "no catalogue entry for $key" }
        return key to PidValue(pid, v, ByteArray(pid.byteCount))
    }

    private val tach = Cluster.LAYOUT.tachometer
    private val speedo = Cluster.LAYOUT.speedometer

    // ------------------------------------------------------------ geometry

    @Test
    fun `the needle starts and ends where the sweep does`() {
        assertEquals(150.0, Cluster.angleFor(tach, 0.0), 1e-9)
        assertEquals(390.0, Cluster.angleFor(tach, 5000.0), 1e-9)
        assertEquals(270.0, Cluster.angleFor(tach, 2500.0), 1e-9)
    }

    /**
     * A needle that keeps going past the end of the dial is how a gauge shows
     * 260 km/h on a truck that cannot do it.
     */
    @Test
    fun `readings outside the range clamp to the ends`() {
        assertEquals(150.0, Cluster.angleFor(speedo, -30.0), 1e-9)
        assertEquals(390.0, Cluster.angleFor(speedo, 999.0), 1e-9)
        assertEquals(0.0, Cluster.fractionFor(speedo, -1.0), 1e-9)
        assertEquals(1.0, Cluster.fractionFor(speedo, 1000.0), 1e-9)
    }

    @Test
    fun `ticks land on the numbers and the majors carry the labels`() {
        val ticks = Cluster.ticksFor(tach)
        val majors = ticks.filter { it.major }

        assertEquals(6, majors.size, "0 to 5 thousand inclusive")
        assertEquals(listOf("0", "1", "2", "3", "4", "5"), majors.map { it.label })
        assertEquals(listOf(0.0, 1000.0, 2000.0, 3000.0, 4000.0, 5000.0), majors.map { it.value })

        // Four minors between each pair of majors.
        assertEquals(26, ticks.size)
        assertTrue(ticks.none { !it.major && it.label != null }, "minors are unlabelled")
    }

    @Test
    fun `the speedometer is marked in twenties like the truck's own`() {
        val majors = Cluster.ticksFor(speedo).filter { it.major }
        assertEquals(listOf("0", "20", "40", "60", "80", "100", "120", "140", "160", "180", "200"),
            majors.map { it.label })
    }

    @Test
    fun `the redline band covers exactly the red part of the sweep`() {
        val bands = Cluster.bandsFor(tach)
        assertEquals(1, bands.size)
        val red = bands.single()
        assertTrue(red.danger)
        assertEquals(Cluster.angleFor(tach, 4200.0), red.startAngleDeg, 1e-9)
        assertEquals(Cluster.angleFor(tach, 5000.0) - red.startAngleDeg, red.sweepDeg, 1e-9)
    }

    @Test
    fun `a dial with both bands puts amber before red and they do not overlap`() {
        val coolant = Cluster.LAYOUT.arcs.single { it.id == "coolant" }
        val bands = Cluster.bandsFor(coolant)
        assertEquals(2, bands.size)
        val (amber, red) = bands
        assertFalse(amber.danger)
        assertTrue(red.danger)
        assertEquals(amber.startAngleDeg + amber.sweepDeg, red.startAngleDeg, 1e-9)
    }

    @Test
    fun `a dial with no bands draws none`() {
        assertTrue(Cluster.bandsFor(speedo).isEmpty())
        assertTrue(Cluster.bandsFor(Cluster.LAYOUT.arcs.single { it.id == "fuel" }).isEmpty())
    }

    // ------------------------------------------------------------- readings

    @Test
    fun `severity follows the bands`() {
        val coolant = Cluster.LAYOUT.arcs.single { it.id == "coolant" }
        fun at(v: Double) = Cluster.read(coolant, mapOf(value("coolant", v))).severity
        assertEquals(Cluster.Severity.NORMAL, at(90.0))
        assertEquals(Cluster.Severity.CAUTION, at(106.0))
        assertEquals(Cluster.Severity.DANGER, at(120.0))
    }

    /**
     * Boost is manifold pressure above ambient. Subtracting a constant 101
     * would be wrong by several kPa at altitude or in a weather front, so both
     * halves are measured.
     */
    @Test
    fun `boost is manifold pressure above the measured ambient`() {
        val boost = Cluster.LAYOUT.arcs.single { it.id == "boost" }
        val reading = Cluster.read(boost, mapOf(value("map_ext", 240.0), value("baro", 99.0)))
        assertEquals(141.0, reading.value!!, 1e-9)
    }

    @Test
    fun `boost stays blank until both halves have arrived`() {
        val boost = Cluster.LAYOUT.arcs.single { it.id == "boost" }
        // Half an answer is not a smaller answer, it is a wrong one.
        assertNull(Cluster.read(boost, mapOf(value("map_ext", 240.0))).value)
        assertNull(Cluster.read(boost, mapOf(value("baro", 99.0))).value)
        assertEquals("--", Cluster.read(boost, emptyMap()).text)
    }

    /**
     * Not yet read and cannot be read must look different. One resolves in a
     * second; the other never will.
     */
    @Test
    fun `a parameter the vehicle does not offer reads as unavailable, not pending`() {
        val speedReading = Cluster.read(speedo, emptyMap())
        assertTrue(speedReading.supported)
        assertEquals("--", speedReading.text)

        // 0x0B manifold pressure: in the catalogue, absent from this truck.
        val madeUp = speedo.copy(id = "x", source = Cluster.Source.Single("map"))
        val unsupported = Cluster.read(madeUp, emptyMap())
        assertFalse(unsupported.supported)
        assertEquals("n/a", unsupported.text)
    }

    @Test
    fun `an unverified decode is carried through to the gauge`() {
        val boost = Cluster.LAYOUT.arcs.single { it.id == "boost" }
        // map_ext's scaling has never been checked against a vehicle.
        assertFalse(Cluster.read(boost, emptyMap()).verified)
        assertTrue(Cluster.read(speedo, emptyMap()).verified)
    }

    // --------------------------------------------------------------- layout

    /**
     * The whole point of the layout: every dial on it has to be something this
     * truck can actually answer, or the cluster is decoration.
     */
    @Test
    fun `every dial and readout is supported by the measured vehicle`() {
        for (reading in Cluster.readAll(emptyMap())) {
            assertTrue(reading.supported, "${reading.dial.id} is not supported by this truck")
        }
        for (readout in Cluster.LAYOUT.readouts) {
            val key = (readout.source as Cluster.Source.Single).key
            val pid = PidCatalog.byKey(key)
            assertNotNull(pid, "no catalogue entry for $key")
            assertTrue(
                pid!!.id in MeasuredSupport.SUPER_DUTY_2022_PCM_DATA,
                "${readout.id} reads PID %02X, which this truck does not offer".format(pid.id),
            )
        }
    }

    /**
     * Oil pressure is on the real dash. OBD-II does not carry it, and putting
     * another reading under that label would be the most misleading thing this
     * app could draw.
     */
    @Test
    fun `nothing on the face is labelled oil pressure`() {
        val labels = Cluster.LAYOUT.all.map { it.label } + Cluster.LAYOUT.readouts.map { it.label }
        assertTrue(
            labels.none { it.contains("PRESS", ignoreCase = true) && it.contains("OIL", true) },
            labels.toString(),
        )
        assertTrue(labels.any { it.contains("OIL TEMP") }, "the slot should say what it is")
    }

    @Test
    fun `the required keys are exactly what the dials and readouts ask for`() {
        val keys = Cluster.requiredKeys()
        assertEquals(keys.distinct(), keys, "no duplicates")
        assertTrue("rpm" in keys && "speed" in keys && "map_ext" in keys && "baro" in keys)
        for (key in keys) {
            assertNotNull(PidCatalog.byKey(key), "requiredKeys names $key, which is not in the catalogue")
        }
    }

    // ----------------------------------------------------------- formatting

    @Test
    fun `formatting keeps the decimals a gauge needs`() {
        assertEquals("0", Cluster.format(0.4, 0))
        assertEquals("35707.4", Cluster.format(35707.44, 1))
        assertEquals("12.6", Cluster.format(12.649, 1))
        assertEquals("-7", Cluster.format(-7.2, 0))
        assertEquals("-0.4", Cluster.format(-0.44, 1))
    }
}
