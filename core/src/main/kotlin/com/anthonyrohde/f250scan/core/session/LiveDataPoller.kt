package com.anthonyrohde.f250scan.core.session

import com.anthonyrohde.f250scan.core.ford.VehicleProfiles
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
    val values: Map<Int, PidValue>,
    val timestampMillis: Long = System.currentTimeMillis(),
    /** Parameters that failed this sweep, so the UI can grey them out. */
    val failedPids: Set<Int> = emptySet(),
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
    private val logger: ((String) -> Unit)? = null,
) {
    /**
     * Asks the vehicle which mode 01 parameters it supports.
     *
     * One request per 32-parameter bank instead of probing individually, which
     * is both faster and avoids filling the log with no-data results.
     */
    suspend fun readSupportedPids(): Set<Int> {
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
    }

    /** Reads [pids] once. */
    suspend fun sampleOnce(pids: List<Pid>): LiveDataSample {
        val values = linkedMapOf<Int, PidValue>()
        val failed = mutableSetOf<Int>()

        for (pid in pids) {
            val payload = requestPid(pid.id)
            val decoded = payload?.let { pid.decode(it) }
            if (decoded != null) values[pid.id] = decoded else failed += pid.id
        }
        return LiveDataSample(values, failedPids = failed)
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
