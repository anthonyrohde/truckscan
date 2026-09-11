package com.anthonyrohde.f250scan.core.ford

import com.anthonyrohde.f250scan.core.uds.UdsClient
import com.anthonyrohde.f250scan.core.util.toHex

/**
 * Seed/key computation for UDS SecurityAccess (service 0x27).
 *
 * ## Read this before expecting writes to work
 *
 * Most write operations on a Ford module - As-Built changes, service routines,
 * anything that persists - are gated behind SecurityAccess. The module sends a
 * seed, the tester must return a key derived from it, and the derivation is
 * manufacturer proprietary.
 *
 * For older Ford modules the algorithm was recovered by the community and is
 * simple arithmetic. For a 2022 Super Duty that is generally no longer true:
 * newer modules use a keyed algorithm whose secret lives in Ford's servers, and
 * the dealer tool (FDRS) performs the handshake online. No amount of local code
 * substitutes for a key we do not have.
 *
 * So this is deliberately built as a plug-in point rather than a promise:
 *
 *  - [None] is the default and fails loudly with a useful explanation.
 *  - [Legacy] implements the classic community-documented Ford algorithm,
 *    which is worth trying on older modules and costs nothing to attempt.
 *  - [Custom] lets you drop in a derivation you have worked out yourself
 *    without touching the rest of the stack.
 *
 * The module tells you the truth: a wrong key returns NRC 0x35 (invalid key),
 * and too many wrong keys returns 0x36 and locks you out until an ignition
 * cycle. [SecurityAccessManager] counts attempts so we stop before that.
 */
interface SeedKeyAlgorithm {

    val name: String

    /**
     * Derives the key for [seed], or null if this algorithm cannot handle a
     * seed of that shape.
     */
    fun computeKey(seed: ByteArray, securityLevel: Int): ByteArray?

    /** Never attempt a handshake we know we cannot complete. */
    object None : SeedKeyAlgorithm {
        override val name = "None"
        override fun computeKey(seed: ByteArray, securityLevel: Int): ByteArray? = null
    }

    /**
     * The classic Ford/Mazda seed-key algorithm for older modules.
     *
     * Applies to 2-byte seeds only. Documented by the community for pre-UDS
     * and early UDS Ford modules; it is very unlikely to satisfy a 2022 module
     * but is harmless to try once, and this is where a verified algorithm
     * should be pinned if you work one out.
     */
    object Legacy : SeedKeyAlgorithm {
        override val name = "Legacy Ford (2-byte seed)"

        override fun computeKey(seed: ByteArray, securityLevel: Int): ByteArray? {
            if (seed.size != 2) return null
            val s = ((seed[0].toInt() and 0xFF) shl 8) or (seed[1].toInt() and 0xFF)
            // Reject the all-zero seed: a module returning zeros is telling us
            // it is already unlocked, and hashing that gains nothing.
            if (s == 0) return null
            val key = (s + 0x6F17) and 0xFFFF
            return byteArrayOf((key shr 8).toByte(), key.toByte())
        }
    }

    /**
     * Replays a seed/key pair captured from a working tool.
     *
     * Deliberately narrow, and worth understanding before relying on it. A
     * captured pair proves nothing about the *algorithm* - it is one input and
     * one output. Replaying it only unlocks the module if the module issues
     * that exact seed again.
     *
     * Whether it does is a property of the module, not of this code. Some issue
     * a fixed or small-set seed, in which case a capture is genuinely enough.
     * Most current modules randomise, in which case this will almost never hit
     * and the honest answer stays "we cannot derive the key".
     *
     * Pairs are supplied as raw (seed, key) byte arrays rather than as a trace
     * type, so that the Ford layer stays independent of the trace importer.
     */
    class Replay(pairs: List<Pair<ByteArray, ByteArray>>) : SeedKeyAlgorithm {

        override val name = "Replay of ${pairs.size} captured handshake(s)"

        // Indexed by seed so lookup is exact rather than a linear scan with
        // array identity comparison, which would never match.
        private val bySeed: Map<String, ByteArray> =
            pairs.associate { (seed, key) -> seed.toHex() to key }

        override fun computeKey(seed: ByteArray, securityLevel: Int): ByteArray? =
            bySeed[seed.toHex()]

        /** True when [seed] was seen in the capture. */
        fun canAnswer(seed: ByteArray): Boolean = bySeed.containsKey(seed.toHex())

        val observedSeedCount: Int get() = bySeed.size
    }

    /** Wraps a caller-supplied derivation. */
    class Custom(
        override val name: String,
        private val derive: (ByteArray, Int) -> ByteArray?,
    ) : SeedKeyAlgorithm {
        override fun computeKey(seed: ByteArray, securityLevel: Int): ByteArray? =
            derive(seed, securityLevel)
    }
}

class SecurityAccessException(message: String) : Exception(message)

/** Outcome of a security access attempt. */
sealed class SecurityAccessResult {
    /** Module granted access - writes may proceed. */
    data class Granted(val level: Int, val algorithm: String) : SecurityAccessResult()

    /** Module reported it is already unlocked (all-zero seed). */
    data class AlreadyUnlocked(val level: Int) : SecurityAccessResult()

    /** We have no algorithm that produces a key for this seed. */
    data class NoAlgorithm(val level: Int, val seed: ByteArray) : SecurityAccessResult() {
        val explanation: String get() =
            "The module issued a ${seed.size}-byte seed (${seed.toHex(" ")}) but no " +
                "configured algorithm can derive a key for it. On a 2022 vehicle this " +
                "is the expected result: the derivation is Ford proprietary and the " +
                "dealer tool completes the handshake against Ford's servers. Read " +
                "operations are unaffected."
    }

    /** Module rejected our key. */
    data class Rejected(val level: Int, val algorithm: String, val reason: String) :
        SecurityAccessResult()
}

/**
 * Runs the seed/key handshake, trying each configured algorithm in turn.
 *
 * Attempt counting is the point of this class. A module that receives too many
 * bad keys returns NRC 0x36 and refuses further attempts until the ignition is
 * cycled, which is a genuinely annoying state to put a truck in, so we stop at
 * [maxAttempts] and say so rather than brute-forcing.
 */
class SecurityAccessManager(
    private val algorithms: List<SeedKeyAlgorithm> = listOf(SeedKeyAlgorithm.Legacy),
    private val maxAttempts: Int = 2,
    private val logger: ((String) -> Unit)? = null,
) {
    suspend fun requestAccess(client: UdsClient, level: Int = 0x01): SecurityAccessResult {
        val seed = try {
            client.requestSeed(level)
        } catch (e: Exception) {
            throw SecurityAccessException(
                "${client.moduleName} refused to issue a security seed at level " +
                    "0x${level.toString(16).uppercase()}: ${e.message}",
            )
        }

        // Convention: an all-zero seed means access is already granted.
        if (seed.isEmpty() || seed.all { it == 0.toByte() }) {
            logger?.invoke("${client.moduleName} is already unlocked at level $level")
            return SecurityAccessResult.AlreadyUnlocked(level)
        }

        val usable = algorithms.mapNotNull { algo ->
            algo.computeKey(seed, level)?.let { algo to it }
        }.take(maxAttempts)

        if (usable.isEmpty()) {
            return SecurityAccessResult.NoAlgorithm(level, seed)
        }

        var lastReason = "unknown"
        for ((algo, key) in usable) {
            logger?.invoke("Trying ${algo.name} for ${client.moduleName}")
            try {
                client.sendKey(level, key)
                logger?.invoke("${client.moduleName} granted access via ${algo.name}")
                return SecurityAccessResult.Granted(level, algo.name)
            } catch (e: Exception) {
                lastReason = e.message ?: "rejected"
                logger?.invoke("${algo.name} rejected: $lastReason")
                // Stop immediately on lockout rather than burning the remaining
                // attempts; the module will not accept anything until reset.
                if (lastReason.contains("Exceeded number of attempts") ||
                    lastReason.contains("time delay")
                ) {
                    break
                }
            }
        }

        return SecurityAccessResult.Rejected(
            level,
            usable.joinToString(", ") { it.first.name },
            lastReason,
        )
    }
}
