package com.tactical.domain.identity

import java.time.Instant

/**
 * Immutable logical peer snapshot for discovery and mesh status.
 *
 * A DeviceNode represents exactly one iTantra identity. The same peer may
 * simultaneously be visible or connected through BLE and Wi-Fi Direct, so
 * transport state is stored as a map instead of creating duplicate nodes.
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
    val path: List<DeviceId> = emptyList(),
    /** Latest known state of each physical bearer for this logical peer. */
    val transportStates: Map<RadioType, RadioLinkState> = emptyMap()
) {
    init {
        require(callsign.isNotBlank()) { "callsign must not be blank" }
        require(hopCount >= 0) { "hopCount cannot be negative" }
        require(battery == null || battery in 0..100) { "battery must be in 0..100" }
    }
}