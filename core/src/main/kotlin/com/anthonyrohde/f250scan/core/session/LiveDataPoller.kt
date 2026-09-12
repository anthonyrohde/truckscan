package com.anthonyrohde.f250scan.core.session

import com.anthonyrohde.f250scan.core.ford.VehicleProfiles
import com.anthonyrohde.f250scan.core.adapter.BusRouter
import com.anthonyrohde.f250scan.core.adapter.CanBus
import com.anthonyrohde.f250scan.core.isotp.IsoTpChannel
import com.anthonyrohde.f250scan.core.pid.Pid
import com.anthonyrohde.f250scan.core.pid.PidCatalog
import com.anthonyrohde.f250scan.core.pid.PidValue
import com.anthonyrohde.f250scan.core.util.u8
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** One sweep of the selected parameters. */
data class LiveDataSample(
    /**
     * Readings keyed by [com.anthonyrohde.f250scan.core.pid.Pid.key], not by
     * OBD PID. Several diesel PIDs carry more than one sensor - the four
     * exhaust gas temperatures all arrive under 0x78 - so keying by PID would
     * let them overwrite each other.
     */
    val values: Map<String, PidValue>,
    val timestampMillis: Long = System.currentTimeMillis(),
    /** Parameters that did not decode this sweep, so the UI can grey them out. */
    val failedKeys: Set<String> = emptySet(),
    /** Wall-clock duration of the sweep, so the UI can show the achieved rate. */
    val sweepMillis: Long = 0,
)

/**
 * Streams live parameters from the powertrain.
 *
 * Requests go to the OBD-II functional address rather than a specific module,
 * which is what makes the standard mode 01 parameters work without knowing
 * anything about the vehicle.
 *
 * ## On sample rate
 *
 * Each parameter is a separate request/response round trip, so the achievable
 * rate is set by how many you watch: roughly 20-25 ms per parameter on a
 * 500 kbps bus with a decent adapter. Watching six parameters gives about
 * 6-7 Hz; watching twenty gives about 2 Hz. The UI should therefore encourage
 * a small selection when someone wants a responsive graph, and this class
 * reports the achieved interval so it can say so honestly instead of implying
 * a rate it is not delivering.
 */
class LiveDataPoller(
    private val channel: IsoTpChannel,
    private val busRouter: BusRouter? = null,
    private val logger: ((String) -> Unit)? = null,
) {
    /**
     * Asks the vehicle which mode 01 parameters it supports.
     *
     * One request per 32-parameter bank instead of probing individually, which
     * is both faster and avoids filling the log with no-data results.
     */
    suspend fun readSupportedPids(): Set<Int> {
        runCatching { busRouter?.ensureBus(CanBus.HS_CAN1) }
        val supported = mutableSetOf<Int>()
        for (base in listOf(0x00, 0x20, 0x40, 0x60, 0x80, 0xA0, 0xC0)) {
            val payload = requestPid(base) ?: break
            val batch = PidCatalog.decodeSupportMask(base, payload)
            supported += batch
            // Bit 0 of the last byte indicates the next bank exists.
            if (payload.size < 4 || payload.u8(3) and 0x01 == 0) break
        }
        logger?.invoke("Vehicle reports ${supported.size} supported mode 01 parameters")
        return supported
    }

    /** Filters the catalog down to what this vehicle actually supports. */
    suspend fun availableParameters(): List<Pid> {
        val supported = runCatching { readSupportedPids() }.getOrDefault(emptySet())
        // An empty support mask means the query failed, not that nothing is
        // supported; offering the whole catalog is the safer fallback.
        if (supported.isEmpty()) return PidCatalog.ALL
        return PidCatalog.ALL.filter { it.id in supported }
            // A multi-sensor PID reports support for the PID, not per sensor.
            // Sensors that are absent decode to nothing and drop out on the
            // first sweep rather than sitting on the dashboard forever.
            .distinctBy { it.key }
    }

    /**
     * Reads [pids] once.
     *
     * Requests are grouped by OBD PID so a multi-sensor parameter costs one
     * round trip rather than one per sensor: watching all four exhaust gas
     * temperatures is a single request, not four.
     */
    suspend fun sampleOnce(pids: List<Pid>): LiveDataSample {
        // Legislated OBD-II is answered by the powertrain on HS-CAN1, so a
        // sample taken after reading a body module has to come back here first.
        runCatching { busRouter?.ensureBus(CanBus.HS_CAN1) }

        val startedAt = System.currentTimeMillis()
        val values = linkedMapOf<String, PidValue>()
        val failed = mutableSetOf<String>()

        for ((pidId, group) in pids.groupBy { it.id }) {
            val payload = requestPid(pidId)
            if (payload == null) {
                group.forEach { failed += it.key }
                continue
            }
            for (pid in group) {
                val decoded = pid.decode(payload)
                if (decoded != null) values[pid.key] = decoded else failed += pid.key
            }
        }

        return LiveDataSample(
            values = values,
            failedKeys = failed,
            sweepMillis = System.currentTimeMillis() - startedAt,
        )
    }

    /**
     * Continuously samples [pids].
     *
     * @param intervalMillis target gap between sweeps. When a sweep takes
     *   longer than this the flow does not try to catch up - it emits as fast
     *   as the bus allows, since queueing stale requests only adds latency.
     */
    fun stream(pids: List<Pid>, intervalMillis: Long = 250): Flow<LiveDataSample> = flow {
        require(pids.isNotEmpty()) { "Select at least one parameter to stream" }
        while (true) {
            val startedAt = System.currentTimeMillis()
            emit(sampleOnce(pids))
            val elapsed = System.currentTimeMillis() - startedAt
            val remaining = intervalMillis - elapsed
            if (remaining > 0) delay(remaining)
        }
    }

    /**
     * Requests a single mode 01 parameter.
     *
     * Strips the echoed mode and PID bytes so the caller gets just the value
     * payload. A reply whose echo does not match is discarded rather than
     * decoded, since on a shared bus it belongs to a different request.
     */
    private suspend fun requestPid(pid: Int): ByteArray? = runCatching {
        val response = channel.request(
            VehicleProfiles.OBD_FUNCTIONAL_REQUEST,
            VehicleProfiles.OBD_RESPONSE_RANGE_START,
            byteArrayOf(0x01, pid.toByte()),
            1_000,
        )
        if (response.size < 3) return@runCatching null
        if (response.u8(0) != 0x41 || response.u8(1) != pid) return@runCatching null
        response.copyOfRange(2, response.size)
    }.getOrNull()
}
