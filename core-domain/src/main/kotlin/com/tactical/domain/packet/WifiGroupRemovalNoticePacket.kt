package com.tactical.domain.packet

import com.tactical.domain.identity.DeviceId

/**
 * Wi-Fi-Direct-only topology notice sent by a squad member that removed
 * another member from the Wi-Fi group relationship.
 *
 * It is deliberately separate from SquadControlPacket so this notification
 * can be transported only over Wi-Fi Direct without changing the BLE control
 * protocol or disconnecting an independent BLE relationship.
 */
data class WifiGroupRemovalNoticePacket(
    val sender: DeviceId,
    val target: DeviceId,
    val removedDevice: DeviceId,
    val removedCallsign: String,
    /**
     * Wi-Fi peers whose automatic P2P reconnection must be suppressed after
     * this removal. This is carried to both the removed peer and the remaining
     * peers so neither side recreates the old Wi-Fi-only relationship.
     */
    val blockedPeerIds: Set<String> = emptySet(),
    val timestamp: Long
) : Packet {
    init {
        require(removedCallsign.isNotBlank()) {
            "removedCallsign must not be blank"
        }
    }
}
