package com.tactical.protocol.serialization

import com.tactical.domain.identity.DeviceId
import com.tactical.domain.location.GeoFix
import com.tactical.domain.packet.*
import com.tactical.protocol.constants.ProtocolConstants
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32

class BinaryPacketSerializer : PacketSerializer {

    // 100 Julian years represented as whole days. The day alignment means
    // legacy clients that only display HH:mm retain the same clock time.
    private val callModeTimestampOffsetMs: Long =
        36500L * 24L * 60L * 60L * 1000L

    override fun serialize(packet: Packet): ByteArray {
        val payload = when (packet) {
            is TextPacket -> encodeTextPacket(packet)
            is VoicePacket -> encodeVoicePacket(packet)
            is EmergencyPacket -> encodeEmergencyPacket(packet)
            is BeaconPacket -> encodeBeaconPacket(packet)
            is SquadControlPacket -> encodeSquadControlPacket(packet)
            is WifiGroupRemovalNoticePacket -> encodeWifiGroupRemovalNoticePacket(packet)
        }

        val type: Byte = when (packet) {
            is TextPacket -> 1
            is VoicePacket -> 2
            is EmergencyPacket -> 3
            is BeaconPacket -> 4
            is SquadControlPacket -> 5
            is WifiGroupRemovalNoticePacket -> 6
        }

        return wrapInEnvelope(type, payload)
    }

    override fun serializeRelay(packet: MeshRelayPacket): ByteArray {
        val innerPayload = serialize(packet.payload)

        val relayPayload = ByteArrayOutputStream().use { bos ->
            DataOutputStream(bos).use { out ->
                out.writeString(packet.originalSender.value)
                out.writeString(packet.immediateSender.value)
                out.writeInt(packet.ttl)
                out.writeInt(packet.hopCount)
                out.writeInt(innerPayload.size)
                out.write(innerPayload)

                val hasRoutingExtension =
                    packet.path.isNotEmpty() ||
                        packet.targetDeviceIds != null ||
                        packet.deliveredTargetDeviceIds.isNotEmpty()

                if (hasRoutingExtension) {
                    out.writeInt(packet.path.size)
                    packet.path.forEach { out.writeString(it.value) }

                    val targets = packet.targetDeviceIds
                    if (targets == null) {
                        out.writeInt(-1)
                    } else {
                        require(packet.deliveredTargetDeviceIds.all { it in targets }) {
                            "Delivered target is not present in target set"
                        }
                        out.writeInt(targets.size)
                        targets.forEach { out.writeString(it) }
                        out.writeInt(packet.deliveredTargetDeviceIds.size)
                        packet.deliveredTargetDeviceIds.forEach { out.writeString(it) }
                    }
                }
            }
            bos.toByteArray()
        }

        return wrapInEnvelope(100.toByte(), relayPayload)
    }

    // ---- Per-type payload encoding ----

    private fun encodeTextPacket(packet: TextPacket): ByteArray = byteStream {
        writeString(packet.sender.value)
        writeString(packet.languageCode)
        writeString(packet.text)
        // Encode Call Mode with a large day-aligned timestamp offset.
        // This keeps the packet layout unchanged and, importantly, makes
        // ordinary packets from older app versions unambiguously non-Call
        // Mode when decoded by a newer app. Older readers still parse the
        // packet as a normal TextPacket and retain the same HH:mm time.
        writeLong(
            if (packet.isCallMode) {
                packet.timestamp + callModeTimestampOffsetMs
            } else {
                packet.timestamp
            }
        )
    }

    private fun encodeVoicePacket(packet: VoicePacket): ByteArray = byteStream {
        writeString(packet.sender.value)
        writeByte(packet.codec.ordinal)
        writeInt(packet.audioData.size)
        write(packet.audioData)
        writeLong(packet.timestamp)
    }

    private fun encodeEmergencyPacket(packet: EmergencyPacket): ByteArray = byteStream {
        writeString(packet.sender.value)
        writeByte(packet.severity.ordinal)
        writeString(packet.description)
        writeString(packet.languageCode)
        writeGeoFix(packet.location)
        writeLong(packet.timestamp)
    }

    private fun encodeBeaconPacket(packet: BeaconPacket): ByteArray = byteStream {
        writeString(packet.sender.value)
        writeString(packet.callsign)
        writeBoolean(packet.listenPort != null)
        val listenPort = packet.listenPort
        if (listenPort != null) {
            writeInt(listenPort)
        }
        writeLong(packet.timestamp)
    }

    private fun encodeSquadControlPacket(packet: SquadControlPacket): ByteArray = byteStream {
        writeString(packet.sender.value)
        writeString(packet.target.value)
        writeString(packet.requestId)
        writeByte(packet.action.ordinal)
        writeString(packet.callsign)
        writeBoolean(packet.accepted != null)
        packet.accepted?.let { writeBoolean(it) }
        writeLong(packet.timestamp)
    }

    private fun encodeWifiGroupRemovalNoticePacket(
        packet: WifiGroupRemovalNoticePacket
    ): ByteArray = byteStream {
        writeString(packet.sender.value)
        writeString(packet.target.value)
        writeString(packet.removedDevice.value)
        writeString(packet.removedCallsign)
        writeInt(packet.blockedPeerIds.size)
        packet.blockedPeerIds.forEach { writeString(it) }
        writeLong(packet.timestamp)
    }

    private fun DataOutputStream.writeGeoFix(fix: GeoFix?) {
        writeBoolean(fix != null)
        if (fix == null) return
        writeDouble(fix.latitude)
        writeDouble(fix.longitude)
        writeBoolean(fix.altitude != null)
        val altitude = fix.altitude
        if (altitude != null) {
            writeDouble(altitude)
        }
        writeBoolean(fix.accuracyMeters != null)

        val accuracyMeters = fix.accuracyMeters
        if (accuracyMeters != null) {
            writeFloat(accuracyMeters)
        }
        writeLong(fix.timestamp)
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private inline fun byteStream(block: DataOutputStream.() -> Unit): ByteArray =
        ByteArrayOutputStream().use { bos ->
            DataOutputStream(bos).use { it.block() }
            bos.toByteArray()
        }

    private fun wrapInEnvelope(type: Byte, payload: ByteArray): ByteArray {
        val totalSize = 11 + payload.size
        require(totalSize <= ProtocolConstants.MAX_PACKET_SIZE) {
            "Serialized packet ($totalSize bytes) exceeds MAX_PACKET_SIZE (${ProtocolConstants.MAX_PACKET_SIZE})"
        }

        val buffer = ByteBuffer.allocate(totalSize)
        buffer.put(ProtocolConstants.MAGIC_BYTE)
        buffer.put(ProtocolConstants.VERSION)
        buffer.put(type)
        buffer.putInt(payload.size)

        val crc = CRC32()
        crc.update(payload)
        buffer.putInt(crc.value.toInt())
        buffer.put(payload)

        return buffer.array()
    }

    // ---- Deserialization ----

    override fun deserialize(bytes: ByteArray): Packet {
        val unwrapped = unwrapEnvelope(bytes)
        val input = DataInputStream(unwrapped.payload.inputStream())

        return when (unwrapped.type.toInt()) {
            1 -> input.use {
                val sender = DeviceId(it.readString())
                val languageCode = it.readString()
                val text = it.readString()
                val wireTimestamp = it.readLong()
                val isCallMode = wireTimestamp >= callModeTimestampOffsetMs
                TextPacket(
                    sender = sender,
                    text = text,
                    languageCode = languageCode,
                    timestamp = if (isCallMode) {
                        wireTimestamp - callModeTimestampOffsetMs
                    } else {
                        wireTimestamp
                    },
                    isCallMode = isCallMode
                )
            }
            2 -> input.use {
                val sender = DeviceId(it.readString())
                val codec = AudioCodec.entries[it.readByte().toInt()]
                val audioLen = it.readInt()
                val audioData = ByteArray(audioLen).also { buf -> it.readFully(buf) }
                val timestamp = it.readLong()
                VoicePacket(sender = sender, audioData = audioData, codec = codec, timestamp = timestamp)
            }
            3 -> input.use {
                val sender = DeviceId(it.readString())
                val severity = Severity.entries[it.readByte().toInt()]
                val description = it.readString()
                val languageCode = it.readString()
                val location = it.readGeoFix()
                val timestamp = it.readLong()
                EmergencyPacket(
                    sender = sender,
                    severity = severity,
                    description = description,
                    location = location,
                    languageCode = languageCode,
                    timestamp = timestamp
                )
            }
            4 -> input.use {
                val sender = DeviceId(it.readString())
                val callsign = it.readString()
                val hasPort = it.readBoolean()
                val listenPort = if (hasPort) it.readInt() else null
                val timestamp = it.readLong()
                BeaconPacket(sender = sender, callsign = callsign, listenPort = listenPort, timestamp = timestamp)
            }
            5 -> input.use {
                val sender = DeviceId(it.readString())
                val target = DeviceId(it.readString())
                val requestId = it.readString()
                val action = SquadControlAction.entries[it.readByte().toInt()]
                val callsign = it.readString()
                val hasAccepted = it.readBoolean()
                val accepted = if (hasAccepted) it.readBoolean() else null
                val timestamp = it.readLong()
                SquadControlPacket(
                    sender = sender,
                    target = target,
                    requestId = requestId,
                    action = action,
                    callsign = callsign,
                    accepted = accepted,
                    timestamp = timestamp
                )
            }
            6 -> input.use {
                val sender = DeviceId(it.readString())
                val target = DeviceId(it.readString())
                val removedDevice = DeviceId(it.readString())
                val removedCallsign = it.readString()
                val blockedCount = it.readInt()
                require(blockedCount in 0..128) {
                    "Invalid Wi-Fi removal blocked-peer count: $blockedCount"
                }
                val blockedPeerIds = buildSet {
                    repeat(blockedCount) {
                        add(input.readString())
                    }
                }
                val timestamp = it.readLong()
                WifiGroupRemovalNoticePacket(
                    sender = sender,
                    target = target,
                    removedDevice = removedDevice,
                    removedCallsign = removedCallsign,
                    blockedPeerIds = blockedPeerIds,
                    timestamp = timestamp
                )
            }
            else -> throw IllegalArgumentException("Unknown packet type: ${unwrapped.type}")
        }
    }

    override fun deserializeRelay(bytes: ByteArray): MeshRelayPacket {
        val unwrapped = unwrapEnvelope(bytes)
        if (unwrapped.type.toInt() != 100) throw IllegalArgumentException("Not a relay packet")

        val input = DataInputStream(unwrapped.payload.inputStream())
        return input.use {
            val originalSender = DeviceId(it.readString())
            val immediateSender = DeviceId(it.readString())
            val ttl = it.readInt()
            val hopCount = it.readInt()
            val innerLen = it.readInt()
            require(innerLen >= 0 && innerLen <= it.available()) {
                "Invalid relay inner length: $innerLen"
            }
            val innerBytes = ByteArray(innerLen).also { buf -> it.readFully(buf) }

            val stream = it
            var path = emptyList<DeviceId>()
            var targetDeviceIds: Set<String>? = null
            var deliveredTargetDeviceIds = emptySet<String>()

            if (stream.available() > 0) {
                val pathCount = stream.readInt()
                require(pathCount in 0..(ProtocolConstants.MAX_HOPS + 1)) {
                    "Invalid relay path length: $pathCount"
                }
                path = List(pathCount) { DeviceId(stream.readString()) }

                // Older topology relays ended after the path extension.
                // Target metadata is therefore optional after the path.
                if (stream.available() > 0) {
                    val targetCount = stream.readInt()
                    require(targetCount >= -1) {
                        "Invalid relay target count: $targetCount"
                    }

                    if (targetCount >= 0) {
                        require(targetCount <= 128) {
                            "Invalid relay target count: $targetCount"
                        }

                        targetDeviceIds = buildSet {
                            repeat(targetCount) {
                                add(stream.readString())
                            }
                        }

                        require(stream.available() >= 4) {
                            "Missing delivered-target count"
                        }
                        val deliveredCount = stream.readInt()
                        require(deliveredCount in 0..targetCount) {
                            "Invalid delivered-target count: $deliveredCount"
                        }

                        deliveredTargetDeviceIds = buildSet {
                            repeat(deliveredCount) {
                                add(stream.readString())
                            }
                        }

                        require(deliveredTargetDeviceIds.all { it in targetDeviceIds }) {
                            "Delivered target is not present in target set"
                        }
                    } else {
                        require(stream.available() == 0) {
                            "Unexpected relay data after unrestricted-target marker"
                        }
                    }
                }
            }

            MeshRelayPacket(
                originalSender = originalSender,
                immediateSender = immediateSender,
                ttl = ttl,
                hopCount = hopCount,
                payload = deserialize(innerBytes),
                path = path,
                targetDeviceIds = targetDeviceIds,
                deliveredTargetDeviceIds = deliveredTargetDeviceIds
            )
        }
    }

    private fun DataInputStream.readString(): String {
        val len = readInt()
        val bytes = ByteArray(len)
        readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun DataInputStream.readGeoFix(): GeoFix? {
        val hasLocation = readBoolean()
        if (!hasLocation) return null
        val latitude = readDouble()
        val longitude = readDouble()
        val altitude = if (readBoolean()) readDouble() else null
        val accuracyMeters = if (readBoolean()) readFloat() else null
        val geoTimestamp = readLong()
        return GeoFix(
            latitude = latitude,
            longitude = longitude,
            altitude = altitude,
            accuracyMeters = accuracyMeters,
            timestamp = geoTimestamp
        )
    }

    private data class Unwrapped(val type: Byte, val payload: ByteArray)

    private fun unwrapEnvelope(bytes: ByteArray): Unwrapped {
        val buffer = ByteBuffer.wrap(bytes)
        val magic = buffer.get()
        if (magic != ProtocolConstants.MAGIC_BYTE) throw IllegalArgumentException("Invalid magic")

        val version = buffer.get()
        if (version != ProtocolConstants.VERSION) throw IllegalArgumentException("Unsupported version: $version")

        val type = buffer.get()
        val len = buffer.getInt()
        val crc = buffer.getInt()

        if (buffer.remaining() < len) throw IllegalArgumentException("Truncated packet: expected $len bytes, got ${buffer.remaining()}")

        val payload = ByteArray(len)
        buffer.get(payload)

        val computedCrc = CRC32()
        computedCrc.update(payload)
        if (crc != computedCrc.value.toInt()) {
            throw IllegalArgumentException("CRC mismatch")
        }

        return Unwrapped(type, payload)
    }
}