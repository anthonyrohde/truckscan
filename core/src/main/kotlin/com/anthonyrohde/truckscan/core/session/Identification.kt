package com.anthonyrohde.truckscan.core.session

import com.anthonyrohde.truckscan.core.uds.UdsClient

/**
 * Standard UDS identification data identifiers (ISO 14229-1 Annex C).
 *
 * Unlike the As-Built block mapping, these are genuinely standardised, so they
 * can be asserted rather than discovered. Reading them is how the app puts a
 * real part number and calibration level against each module, which is what
 * you need before deciding whether a configuration change is safe.
 */
enum class IdentificationDid(val did: Int, val label: String, val isText: Boolean = true) {
    BOOT_SOFTWARE(0xF180, "Boot software identification"),
    APPLICATION_SOFTWARE(0xF181, "Application software identification"),
    APPLICATION_DATA(0xF182, "Application data identification"),
    ACTIVE_SESSION(0xF186, "Active diagnostic session", isText = false),
    SPARE_PART_NUMBER(0xF187, "Manufacturer spare part number"),
    ECU_SOFTWARE_NUMBER(0xF188, "Manufacturer ECU software number"),
    ECU_SOFTWARE_VERSION(0xF189, "Manufacturer ECU software version"),
    SUPPLIER_IDENTIFIER(0xF18A, "System supplier identifier"),
    MANUFACTURING_DATE(0xF18B, "ECU manufacturing date", isText = false),
    SERIAL_NUMBER(0xF18C, "ECU serial number"),
    VIN(0xF190, "Vehicle identification number"),
    ECU_HARDWARE_NUMBER(0xF191, "Manufacturer ECU hardware number"),
    SUPPLIER_HARDWARE_NUMBER(0xF192, "Supplier ECU hardware number"),
    SUPPLIER_HARDWARE_VERSION(0xF193, "Supplier ECU hardware version"),
    SUPPLIER_SOFTWARE_NUMBER(0xF194, "Supplier ECU software number"),
    SUPPLIER_SOFTWARE_VERSION(0xF195, "Supplier ECU software version"),
    SYSTEM_NAME(0xF197, "System name or engine type"),
}

/** Identification data read back from a module. */
data class ModuleIdentification(
    val values: Map<IdentificationDid, ByteArray>,
) {
    /**
     * Decodes a DID as text.
     *
     * Ford pads these fields with spaces and sometimes NULs, and a module that
     * has never been programmed returns 0xFF fill. All three are trimmed so the
     * UI shows an empty field rather than mojibake.
     */
    fun text(did: IdentificationDid): String? {
        val raw = values[did] ?: return null
        val printable = raw
            .filter { it != 0x00.toByte() && it != 0xFF.toByte() }
            .toByteArray()
        val decoded = String(printable, Charsets.ISO_8859_1).trim()
        return decoded.ifBlank { null }
    }

    val vin: String? get() = text(IdentificationDid.VIN)
    val partNumber: String? get() = text(IdentificationDid.SPARE_PART_NUMBER)
        ?: text(IdentificationDid.ECU_HARDWARE_NUMBER)
    val calibrationLevel: String? get() = text(IdentificationDid.ECU_SOFTWARE_NUMBER)
        ?: text(IdentificationDid.APPLICATION_SOFTWARE)
    val serialNumber: String? get() = text(IdentificationDid.SERIAL_NUMBER)

    val isEmpty: Boolean get() = values.isEmpty()

    companion object {
        /**
         * Reads every identification DID a module will answer.
         *
         * Unsupported identifiers are skipped silently: modules vary in which
         * of these they implement, and a missing one is normal rather than a
         * fault worth reporting.
         */
        suspend fun read(
            client: UdsClient,
            dids: List<IdentificationDid> = IdentificationDid.entries,
        ): ModuleIdentification {
            val found = linkedMapOf<IdentificationDid, ByteArray>()
            for (entry in dids) {
                client.tryReadDataByIdentifier(entry.did)?.let { found[entry] = it }
            }
            return ModuleIdentification(found)
        }
    }
}
