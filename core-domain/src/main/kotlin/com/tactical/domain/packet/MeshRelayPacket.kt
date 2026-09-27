package com.tactical.domain.packet

import com.tactical.domain.identity.DeviceId

/**
 * Envelope for controlled flooding. Wraps a live Packet (not opaque bytes)
 * so engine-mesh reasons about the actual domain object it's routing.
 * Serialization to bytes happens only at the RadioTransport/RawPacket
 * boundary via core-protocol's PacketSerializer — not here.
 */
data class MeshRelayPacket(
    val originalSender: DeviceId,
    val immediateSender: DeviceId,
    val ttl: Int,
    val hopCount: Int,
    val payload: Packet,
    /**
     * Ordered path of nodes that have already carried this packet,
     * starting at the original sender. The receiving node is not included.
     */
    val path: List<DeviceId> = emptyList()
) {
    init {
        require(ttl >= 0) { "ttl cannot be negative" }
        require(hopCount >= 0) { "hopCount cannot be negative" }
    }
}

/**
 * That resolves the open question we flagged two
 * files ago about XxHashPacketHasher: since payload
 * is now a real Packet, the hasher can call
 * PacketSerializer.serialize(meshRelayPacket.payload)
 * directly to get canonical bytes to hash — no need
 * to worry about "does the envelope's own fields leak
 * into the hash," since hashing naturally operates
 * on just the inner payload, not the whole
 * MeshRelayPacket. Worth mentioning that resolution
 * to whoever's building core-protocol, since it's a
 * cleaner answer than what was possible under the old
 * opaque-bytes design.
 */