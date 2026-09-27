package com.tactical.engine.discovery.scanner

import com.tactical.domain.identity.DeviceNode
import com.tactical.domain.identity.LinkType
import com.tactical.domain.packet.BeaconPacket
import com.tactical.domain.packet.MeshRelayPacket
import com.tactical.platform.api.ble.BleBeaconPayloadCodec
import com.tactical.platform.api.ble.BleBeaconScanner
import com.tactical.platform.api.radio.RadioTransport
import com.tactical.protocol.serialization.PacketSerializer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import java.time.Instant

class CompositeBeaconScanner(
    private val bleScanner: BleBeaconScanner,
    private val radioTransport: RadioTransport,
    private val serializer: PacketSerializer,
    private val localDeviceId: String
) : BeaconScanner {

    override fun scan(): Flow<DeviceNode> {
        val bleFlow = bleScanner.scan()
            .mapNotNull { scanned ->
                val payload = scanned.advertisementPayload ?: return@mapNotNull null

                // decode() checks the magic bytes first and returns null
                // immediately for anything that isn't this app's format —
                // this is what actually filters out unrelated nearby BLE
                // devices (headphones, trackers, etc.) instead of trying
                // to interpret their random bytes as a beacon.
                val packet = BleBeaconPayloadCodec.decode(payload) ?: return@mapNotNull null

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

        val radioFlow = radioTransport.incoming()
            .mapNotNull { raw ->
                try {
                    // Mesh beacons use the existing relay envelope. We inspect
                    // the envelope here so discovery can retain the route
                    // rather than waiting for MeshService to strip it.
                    val relay = runCatching { serializer.deserializeRelay(raw.data) }.getOrNull()

                    if (relay != null) {
                        val packet = relay.payload as? BeaconPacket ?: return@mapNotNull null

                        // path contains the nodes that have already forwarded
                        // the packet, not this receiver. A one-node path means
                        // the beacon came directly from its origin.
                        val path = if (relay.path.isNotEmpty()) {
                            relay.path
                        } else {
                            listOf(relay.originalSender, relay.immediateSender).distinct()
                        }
                        val direct = path.size <= 1 &&
                            relay.originalSender == relay.immediateSender

                        if (packet.sender.value == localDeviceId) return@mapNotNull null

                        return@mapNotNull DeviceNode(
                            id = packet.sender,
                            callsign = packet.callsign,
                            // RSSI on a relayed packet describes the last hop,
                            // not the original device. Do not present it as
                            // the remote device's distance.
                            rssi = if (direct) raw.rssi else 0,
                            lastSeen = Instant.ofEpochMilli(raw.timestamp),
                            hopCount = if (direct) 0 else path.size,
                            link = if (direct) LinkType.DIRECT else LinkType.RELAYED,
                            path = path
                        )
                    }

                    val packet = serializer.deserialize(raw.data)
                    if (packet !is BeaconPacket) return@mapNotNull null

                    if (packet.sender.value == localDeviceId) return@mapNotNull null

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

        return merge(bleFlow, radioFlow)
    }
}