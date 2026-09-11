package com.anthonyrohde.f250scan.core.transport

/**
 * A raw byte pipe to an OBD adapter.
 *
 * Deliberately minimal: everything above this (the ELM/STN command set, ISO-TP,
 * UDS) is platform independent and lives in :core. Only the implementations of
 * this interface know about Bluetooth sockets, BLE GATT characteristics or USB
 * serial endpoints, which is what keeps the protocol stack unit-testable
 * without a truck in the driveway.
 */
interface ObdTransport {

    val isOpen: Boolean

    /** Human-readable description used in logs and the connection UI. */
    val description: String

    suspend fun open()

    suspend fun write(bytes: ByteArray)

    /**
     * Reads whatever bytes are currently available, suspending until at least
     * one byte arrives or [timeoutMillis] elapses. Returns an empty array on
     * timeout rather than throwing: the adapter framing layer decides whether
     * a quiet pipe is an error, since some commands legitimately return nothing.
     */
    suspend fun read(timeoutMillis: Long): ByteArray

    suspend fun close()
}

open class TransportException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

class TransportClosedException(message: String = "Transport is not open") :
    TransportException(message)
