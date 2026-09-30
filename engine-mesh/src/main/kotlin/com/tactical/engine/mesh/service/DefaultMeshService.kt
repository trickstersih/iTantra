package com.tactical.engine.mesh.service

import com.tactical.domain.identity.DeviceId
import com.tactical.domain.packet.MeshRelayPacket
import com.tactical.domain.packet.Packet
import com.tactical.domain.result.TacticalResult
import com.tactical.engine.mesh.forwarding.ForwardDecision
import com.tactical.engine.mesh.router.MeshRouter
import com.tactical.engine.mesh.quality.LinkQualityMonitor
import com.tactical.platform.api.radio.RadioTransport
import com.tactical.platform.api.radio.RawPacket
import com.tactical.protocol.serialization.PacketSerializer
import com.tactical.protocol.constants.ProtocolConstants
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Coordinates routing, deduplication, and transport for the mesh network.
 */
class DefaultMeshService(
    private val localDeviceId: DeviceId,
    private val router: MeshRouter,
    private val serializer: PacketSerializer,
    private val transport: RadioTransport,
    private val qualityMonitor: LinkQualityMonitor,
    private val squadDeviceIdsProvider: () -> Set<String> = { emptySet() },
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) : MeshService {

    private val _incomingPackets = MutableSharedFlow<Packet>()

    init {
        // Start processing incoming radio traffic
        transport.incoming()
            .catch {
                // A transport failing (no Bluetooth hardware, permission
                // revoked mid-run, etc.) must not propagate as an uncaught
                // exception here — SupervisorJob isolates sibling coroutines
                // from each other, it does not swallow this exception, so
                // without this the whole app crashes over what should just
                // mean "one bearer is temporarily unavailable."
            }
            .onEach { handleIncomingRaw(it) }
            .launchIn(scope)
    }

    override suspend fun send(packet: Packet): TacticalResult<Unit> {
        val relayPacket = MeshRelayPacket(
            originalSender = localDeviceId,
            immediateSender = localDeviceId,
            ttl = ProtocolConstants.DEFAULT_TTL,
            hopCount = 0,
            payload = packet,
            path = if (packet is com.tactical.domain.packet.BeaconPacket) {
                listOf(localDeviceId)
            } else {
                emptyList()
            }
        )
        // Text packets are application-targeted, but the physical first hop
        // must be a mesh broadcast so an intermediate relay can receive the
        // packet even when the final recipient is not directly connected.
        // The embedded target set is the authoritative delivery gate.
        val targetDeviceIds = when (packet) {
            is com.tactical.domain.packet.TextPacket -> squadDeviceIdsProvider()
            is com.tactical.domain.packet.SquadControlPacket -> setOf(packet.target.value)
            is com.tactical.domain.packet.EmergencyPacket -> null
            else -> null
        }

        val routedRelayPacket = relayPacket.copy(
            targetDeviceIds = targetDeviceIds
        )
        return broadcastRelay(routedRelayPacket)
    }

    override fun receive(): Flow<Packet> = _incomingPackets.asSharedFlow()

    private suspend fun handleIncomingRaw(raw: RawPacket) {
        try {
            val relayPacket = serializer.deserializeRelay(raw.data)
            
            // Track link quality
            qualityMonitor.recordSample(relayPacket.immediateSender, raw.rssi)
            
            // Route the packet
            when (val decision = router.handle(relayPacket)) {
                is ForwardDecision.AcceptLocal -> {
                    _incomingPackets.emit(relayPacket.payload)
                }
                is ForwardDecision.Rebroadcast -> {
                    broadcastRelay(decision.relayPacket.copy(immediateSender = localDeviceId))
                }
                is ForwardDecision.AcceptAndRebroadcast -> {
                    _incomingPackets.emit(relayPacket.payload)
                    broadcastRelay(decision.relayPacket.copy(immediateSender = localDeviceId))
                }
                is ForwardDecision.Drop -> {
                    // Log or ignore
                }
            }
        } catch (e: Exception) {
            // Malformed packet, skip
        }
    }

    private suspend fun broadcastRelay(
        relayPacket: MeshRelayPacket,
        targetDeviceIds: Set<String>? = null
    ): TacticalResult<Unit> {
        val bytes = serializer.serializeRelay(relayPacket)
        val raw = RawPacket(
            data = bytes,
            rssi = 0,
            timestamp = System.currentTimeMillis(),
            targetDeviceIds = targetDeviceIds
        )
        return transport.broadcast(raw)
    }
}
