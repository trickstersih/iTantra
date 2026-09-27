package com.tactical.domain.identity

import java.time.Instant

/**
 * Immutable snapshot of a peer device for discovery and mesh status.
 * Produced by engine-discovery, consumed by the roster UI and mesh
 * quality monitors.
 */
data class DeviceNode(
    val id: DeviceId,
    val callsign: String,
    val rssi: Int,
    val lastSeen: Instant,
    val hopCount: Int,
    val link: LinkType,
    val battery: Int? = null,
    /** Nodes traversed from the original sender before reaching this device. */
    val path: List<DeviceId> = emptyList()
) {
    init {
        require(callsign.isNotBlank()) { "callsign must not be blank" }
        require(hopCount >= 0) { "hopCount cannot be negative" }
        require(battery == null || battery in 0..100) { "battery must be in 0..100" }
    }
}

