package com.anthonyrohde.f250scan.core.trace

import com.anthonyrohde.f250scan.core.ford.SecurityAccessManager
import com.anthonyrohde.f250scan.core.ford.SeedKeyAlgorithm

/** Captured handshakes as raw byte pairs, for [SeedKeyAlgorithm.Replay]. */
fun LearnedModule.seedKeyPairs(): List<Pair<ByteArray, ByteArray>> =
    seedKeyObservations
        // Only replay keys the module actually accepted. A rejected key is
        // evidence of nothing and would burn one of our two attempts.
        .filter { it.accepted }
        .map { it.seed to it.key }

fun LearnedProfile.seedKeyPairs(): List<Pair<ByteArray, ByteArray>> =
    modules.flatMap { it.seedKeyPairs() }

/**
 * Builds a security manager that tries captured handshakes before anything else.
 *
 * Replay is placed first because when it applies it is exact, whereas the
 * legacy algorithm is a guess that costs an attempt. Both are still bounded by
 * the manager's attempt cap, so a module cannot be locked out by this.
 */
fun LearnedProfile.securityAccessManager(
    logger: ((String) -> Unit)? = null,
): SecurityAccessManager {
    val pairs = seedKeyPairs()
    val algorithms = buildList {
        if (pairs.isNotEmpty()) add(SeedKeyAlgorithm.Replay(pairs))
        add(SeedKeyAlgorithm.Legacy)
    }
    return SecurityAccessManager(algorithms = algorithms, logger = logger)
}
