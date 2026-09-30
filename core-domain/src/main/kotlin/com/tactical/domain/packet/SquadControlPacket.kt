package com.tactical.domain.packet

import com.tactical.domain.identity.DeviceId

enum class SquadControlAction {
    REQUEST,
    RESPONSE,
    REMOVE
}

/**
 * Application-level squad control message carried through the mesh.
 *
 * It is deliberately separate from the fixed 20-byte direct-GATT
 * SquadControlCodec so the existing BLE control protocol remains unchanged.
 */
data class SquadControlPacket(
    val sender: DeviceId,
    val target: DeviceId,
    val requestId: String,
    val action: SquadControlAction,
    val callsign: String,
    val accepted: Boolean? = null,
    val timestamp: Long
) : Packet {
    init {
        require(requestId.isNotBlank()) { "requestId must not be blank" }
        require(callsign.isNotBlank()) { "callsign must not be blank" }
        if (action == SquadControlAction.RESPONSE) {
            require(accepted != null) { "accepted must be set for a response" }
        } else {
            require(accepted == null) { "accepted must be null for a request" }
        }
    }
}
