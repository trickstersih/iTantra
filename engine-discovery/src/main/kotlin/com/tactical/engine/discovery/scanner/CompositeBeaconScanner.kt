package com.tactical.engine.discovery.scanner

import com.tactical.domain.identity.DeviceNode
import com.tactical.domain.identity.LinkType
import com.tactical.domain.packet.BeaconPacket
import com.tactical.domain.packet.MeshRelayPacket
import com.tactical.platform.api.ble.BleBeaconPayloadCodec
import com.tactical.platform.api.ble.BleBeaconScanner
import com.tactical.platform.api.radio.RadioTransport
import com.tactical.platform.api.radio.RawPacket
import com.tactical.protocol.serialization.PacketSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.SharingStarted
import java.time.Instant

class CompositeBeaconScanner(
    private val bleScanner: BleBeaconScanner,
    private val radioTransport: RadioTransport,
    private val serializer: PacketSerializer,
    private val localDeviceId: String
) : BeaconScanner {

    /**
     * Radio transport receives mesh packets independently of the direct BLE
     * scan windows. Keep that stream hot so topology discovery does not depend
     * on the 3-second direct-BLE maintenance window.
     */
    private val meshScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val meshPeerFlow: Flow<DeviceNode> =
        radioTransport.incoming()
            .mapNotNull { raw -> decodeRadioBeacon(raw) }
            .shareIn(
                meshScope,
                started = SharingStarted.Eagerly,
                replay = 0
            )

    override fun scan(): Flow<DeviceNode> {
        val bleFlow = bleScanner.scan()
            .mapNotNull { scanned ->
                val payload = scanned.advertisementPayload ?: return@mapNotNull null

                // decode() checks the magic bytes first and returns null
                // immediately for anything that isn't this app's format.
                val packet = BleBeaconPayloadCodec.decode(payload)
                    ?: return@mapNotNull null

                // Never add this phone to its own device catalog.
                if (packet.sender.value == localDeviceId) return@mapNotNull null

                DeviceNode(
                    id = packet.sender,
                    callsign = packet.callsign,
                    rssi = scanned.rssi,
                    lastSeen = Instant.now(),
                    hopCount = 0,
                    link = LinkType.DIRECT,
                    path = listOf(packet.sender)
                )
            }

        return merge(bleFlow, meshPeerFlow)
    }

    override fun meshPeers(): Flow<DeviceNode> = meshPeerFlow

    private fun decodeRadioBeacon(raw: RawPacket): DeviceNode? {
        return try {
            // Mesh beacons use the relay envelope. Inspecting it here lets
            // discovery retain the full route instead of waiting for
            // MeshService to strip the envelope.
            val relay = runCatching {
                serializer.deserializeRelay(raw.data)
            }.getOrNull()

            if (relay != null) {
                val packet = relay.payload as? BeaconPacket ?: return null

                if (packet.sender.value == localDeviceId ||
                    relay.originalSender.value == localDeviceId
                ) {
                    return null
                }

                // path contains the nodes that have already forwarded the
                // packet, not this receiver.
                val path = if (relay.path.isNotEmpty()) {
                    relay.path
                } else {
                    listOf(relay.originalSender, relay.immediateSender).distinct()
                }

                // A packet whose route already contains us is a looped copy;
                // never surface it as a valid topology route.
                if (path.any { it.value == localDeviceId }) return null

                val direct = path.size <= 1 &&
                    relay.originalSender == relay.immediateSender

                return DeviceNode(
                    id = packet.sender,
                    callsign = packet.callsign,
                    // RSSI on a relayed packet is only the last-hop RSSI.
                    rssi = if (direct) raw.rssi else 0,
                    lastSeen = Instant.ofEpochMilli(raw.timestamp),
                    hopCount = if (direct) 0 else path.size,
                    link = if (direct) LinkType.DIRECT else LinkType.RELAYED,
                    path = path
                )
            }

            val packet = serializer.deserialize(raw.data)
            if (packet !is BeaconPacket) return null
            if (packet.sender.value == localDeviceId) return null

            DeviceNode(
                id = packet.sender,
                callsign = packet.callsign,
                rssi = raw.rssi,
                lastSeen = Instant.ofEpochMilli(raw.timestamp),
                hopCount = 0,
                link = LinkType.DIRECT,
                path = listOf(packet.sender)
            )
        } catch (_: Exception) {
            null
        }
    }
}
