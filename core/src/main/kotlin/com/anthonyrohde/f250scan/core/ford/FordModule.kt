package com.anthonyrohde.f250scan.core.ford

import com.anthonyrohde.f250scan.core.adapter.CanBus

enum class ModuleCategory(val label: String) {
    POWERTRAIN("Powertrain"),
    CHASSIS("Chassis & braking"),
    BODY("Body & comfort"),
    INFOTAINMENT("Infotainment"),
    SAFETY("Safety & restraints"),
    DRIVER_ASSIST("Driver assistance"),
    NETWORK("Network & gateway"),
    UNKNOWN("Unidentified"),
}

/**
 * A diagnosable control module.
 *
 * Ford uses 11-bit CAN diagnostic addressing with a consistent convention: the
 * response ID is the request ID plus 8. That convention is why [responseId]
 * defaults rather than being spelled out for every entry, and why the
 * discovery sweep can probe an address range without a lookup table.
 */
data class FordModule(
    /** Short code as Ford and the service literature use it, e.g. "PCM". */
    val code: String,
    val name: String,
    val requestId: Int,
    val responseId: Int = requestId + 8,
    /**
     * Buses this module is expected on, most likely first.
     *
     * Treated as probe order, never as fact: what is actually populated varies
     * with trim and options, and on 2020+ Fords the gateway may answer for a
     * module that physically sits on another bus. Discovery decides.
     */
    val expectedBuses: List<CanBus> = listOf(CanBus.HS_CAN1),
    val category: ModuleCategory = ModuleCategory.UNKNOWN,
    val description: String = "",
    /**
     * Marks modules where a careless write has safety consequences. The UI
     * requires an extra, explicit confirmation for these.
     */
    val safetyCritical: Boolean = false,
) {
    val addressLabel: String get() = requestId.toString(16).uppercase().padStart(3, '0')

    override fun toString(): String = "$code ($addressLabel) $name"
}
