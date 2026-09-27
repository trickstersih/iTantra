package com.tactical.engine.discovery.beacon

import com.tactical.domain.identity.DeviceId
import com.tactical.domain.packet.BeaconPacket
import com.tactical.domain.packet.MeshRelayPacket
import com.tactical.platform.api.ble.BleBeaconAdvertiser
import com.tactical.platform.api.ble.BleBeaconPayloadCodec
import com.tactical.platform.api.radio.RadioTransport
import com.tactical.platform.api.radio.RawPacket
import com.tactical.protocol.serialization.PacketSerializer
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger

class PeriodicBeaconEmitter(
    private val localDeviceId: DeviceId,
    private val callsignProvider: () -> String,
    private val transport: RadioTransport,
    private val serializer: PacketSerializer,
    private val bleAdvertiser: BleBeaconAdvertiser,
    private val scope: CoroutineScope =
        CoroutineScope(Dispatchers.Default + SupervisorJob())
) : BeaconEmitter {

    private var job: Job? = null
    private var lastMeshBeaconAt = 0L
    private val running = AtomicBoolean(false)

    override fun start() {
        if (!running.compareAndSet(false, true)) return

        job = scope.launch {
            try {
                while (isActive) {
                    try {
                        emitBeacon()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Keep advertising recoverable: one failed beacon
                        // attempt must not stop the discovery loop. Log the
                        // actual platform failure so OEM-specific advertising
                        // problems are visible during testing.
                        LOGGER.warning(
                            "BLE beacon advertisement failed: " +
                                (e.message ?: e.javaClass.simpleName)
                        )
                    }
                    delay(BEACON_INTERVAL_MS)
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                running.set(false)
                try {
                    bleAdvertiser.stopAdvertising()
                } catch (_: Exception) {
                    // Nothing else to do during shutdown.
                }
            }
        }
    }

    override suspend fun stop() {
        val currentJob = job ?: return
        job = null
        currentJob.cancel()
        currentJob.join()
    }

    private suspend fun emitBeacon() {
        val now = System.currentTimeMillis()
        val beacon = BeaconPacket(
            sender = localDeviceId,
            callsign = callsignProvider(),
            timestamp = now
        )

        // BLE gets the compact, magic-byte-prefixed encoding — the magic
        // bytes let CompositeBeaconScanner reject any third-party BLE
        // device's unrelated advertisement instead of misparsing it.
        val bleBytes = BleBeaconPayloadCodec.encode(beacon)
        bleAdvertiser.advertise(bleBytes)

        // Every few seconds, put the same presence packet into the existing
        // mesh relay path. Intermediates will append themselves to the relay
        // path, allowing downstream peers to discover the complete hop chain.
        if (now - lastMeshBeaconAt >= MESH_BEACON_INTERVAL_MS) {
            lastMeshBeaconAt = now
            val relay = MeshRelayPacket(
                originalSender = localDeviceId,
                immediateSender = localDeviceId,
                ttl = com.tactical.protocol.constants.ProtocolConstants.DEFAULT_TTL,
                hopCount = 0,
                payload = beacon,
                path = listOf(localDeviceId)
            )
            try {
                transport.broadcast(
                    RawPacket(
                        data = serializer.serializeRelay(relay),
                        rssi = 0,
                        timestamp = now
                    )
                )
            } catch (_: Exception) {
                // Mesh presence is best-effort. Direct BLE advertising must
                // continue even if the radio transport is temporarily down.
            }
        }
    }

    companion object {
        private const val BEACON_INTERVAL_MS = 2000L
        private const val MESH_BEACON_INTERVAL_MS = 6000L
        private val LOGGER: Logger =
            Logger.getLogger(PeriodicBeaconEmitter::class.java.name)
    }
}
