package com.tactical.engine.mesh.router

import com.tactical.domain.identity.DeviceId
import com.tactical.domain.packet.MeshRelayPacket
import com.tactical.engine.mesh.forwarding.ForwardDecision
import com.tactical.engine.mesh.deduplication.DeduplicationFilter
import com.tactical.engine.mesh.ttl.TtlTracker
import com.tactical.protocol.hashing.PacketHasher

/**
 * Implements controlled flooding: rebroadcasts a packet if its TTL > 0 and 
 * it hasn't been seen recently.
 */
class FloodMeshRouter(
    private val localDeviceId: DeviceId,
    private val hasher: PacketHasher,
    private val dedup: DeduplicationFilter,
    private val ttlTracker: TtlTracker
) : MeshRouter {

    override fun handle(relayPacket: MeshRelayPacket): ForwardDecision {
        // 1. Is it from us? Drop to prevent loops if we receive our own broadcast.
        if (relayPacket.originalSender == localDeviceId) {
            return ForwardDecision.Drop("Originating from self")
        }

        // 2. Have we seen it?
        val hash = hasher.hash(relayPacket.payload)
        if (dedup.mightContain(hash)) {
            return ForwardDecision.Drop("Duplicate packet")
        }

        // Mark as seen
        dedup.put(hash)

        // Reconstruct a useful path for packets produced by an older
        // build that did not carry path metadata.
        val existingPath = if (relayPacket.path.isNotEmpty()) {
            relayPacket.path
        } else {
            listOf(relayPacket.originalSender, relayPacket.immediateSender)
                .distinct()
        }

        // A path containing this node indicates a loop. Deduplication normally
        // catches this too, but rejecting explicitly keeps route metadata clean.
        if (localDeviceId in existingPath) {
            return ForwardDecision.Drop("Relay path already contains local device")
        }

        // 3. TTL check and decrement for rebroadcast
        val updatedPacket = ttlTracker.decrement(relayPacket)?.copy(
            path = existingPath + localDeviceId
        )

        // 4. Decision logic
        // In this architecture, all packets are accepted locally if they are new.
        // If we can decrement TTL, we also rebroadcast.
        return if (updatedPacket != null) {
            ForwardDecision.AcceptAndRebroadcast(updatedPacket)
        } else {
            ForwardDecision.AcceptLocal
        }
    }
}
