package com.tactical.platform.radio

import com.tactical.domain.identity.RadioType
import com.tactical.domain.result.TacticalResult
import com.tactical.platform.api.radio.RadioTransport
import com.tactical.platform.api.radio.RawPacket
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge
import android.util.Log
import kotlinx.coroutines.flow.catch

/**
 * Multiplexes RadioTransport across BLE and Wi-Fi Direct so engine-mesh
 * only ever talks to one RadioTransport, regardless of which bearers are
 * actually available on a given device (both are declared as optional
 * uses-feature in the manifest).
 *
 * incoming(): merges both bearers' flows — a packet is a packet to
 * engine-mesh's dedup/TTL layer regardless of which radio it arrived on.
 *
 * broadcast(): sends on both bearers concurrently rather than picking one,
 * since a peer might only be reachable over one of the two at any given
 * moment (in BLE range but not yet Wi-Fi-Direct-grouped, or vice versa).
 * DeduplicationFilter downstream already collapses the resulting duplicate
 * deliveries, so sending twice costs bandwidth, not correctness. Reports
 * success if either bearer succeeds.
 */
class CompositeRadioTransport(
    private val bleTransport: RadioTransport,
    private val wifiDirectTransport: RadioTransport
) : RadioTransport {

    override val type: RadioType? = null

    override fun connectedPeerIds(): Set<String> =
        bleTransport.connectedPeerIds() + wifiDirectTransport.connectedPeerIds()

    override fun connectedPeerIdsByTransport(): Map<RadioType, Set<String>> {
        val merged = mutableMapOf<RadioType, MutableSet<String>>()
        bleTransport.connectedPeerIdsByTransport().forEach { (type, ids) ->
            merged.getOrPut(type) { mutableSetOf() }.addAll(ids)
        }
        wifiDirectTransport.connectedPeerIdsByTransport().forEach { (type, ids) ->
            merged.getOrPut(type) { mutableSetOf() }.addAll(ids)
        }
        return merged.mapValues { it.value.toSet() }
    }

    override fun allowPeer(deviceId: String) {
        bleTransport.allowPeer(deviceId)
        wifiDirectTransport.allowPeer(deviceId)
    }

    override suspend fun disconnectPeer(deviceId: String) {
        // Squad removal is bearer-aware. If both BLE and Wi-Fi Direct are
        // carrying the same peer, remove only the Wi-Fi Direct relationship;
        // the independent BLE link must remain usable. For a Wi-Fi-only peer,
        // remove Wi-Fi. For a BLE-only peer, preserve the old BLE-only removal
        // behavior.
        val wifiConnected = deviceId in wifiDirectTransport.connectedPeerIds()
        if (wifiConnected) {
            wifiDirectTransport.disconnectPeer(deviceId)
            return
        }

        val bleConnected = deviceId in bleTransport.connectedPeerIds()
        if (bleConnected) {
            bleTransport.disconnectPeer(deviceId)
        }
    }

    override fun incoming(): Flow<RawPacket> =
        merge(
            bleTransport.incoming().catch { e ->
                Log.w(TAG, "BLE transport unavailable: ${e.message}")
            },
            wifiDirectTransport.incoming().catch { e ->
                Log.w(TAG, "Wi-Fi Direct transport unavailable: ${e.message}")
            }
        )

    override suspend fun broadcast(raw: RawPacket): TacticalResult<Unit> = coroutineScope {
        // A non-null outgoing transport is an explicit bearer constraint.
        // Normal packets keep transport == null and retain the existing
        // concurrent BLE + Wi-Fi broadcast behavior.
        when (raw.transport) {
            RadioType.BLUETOOTH -> {
                return@coroutineScope runCatching { bleTransport.broadcast(raw) }
                    .getOrElse {
                        TacticalResult.Failure("BLE broadcast threw: " + (it.message ?: "unknown"))
                    }
            }
            RadioType.WIFI_DIRECT -> {
                return@coroutineScope runCatching { wifiDirectTransport.broadcast(raw) }
                    .getOrElse {
                        TacticalResult.Failure("Wi-Fi Direct broadcast threw: " + (it.message ?: "unknown"))
                    }
            }
            null -> Unit
        }

        // Targeted packets can now traverse either bearer. BLE retains its
        // existing physical target filtering, while Wi-Fi forwards the mesh
        // packet to its connected group peers and lets FloodMeshRouter apply
        // the authoritative application target gate.
        val bleDeferred = async { runCatching { bleTransport.broadcast(raw) } }
        val wifiDeferred = async { runCatching { wifiDirectTransport.broadcast(raw) } }

        val bleResult = bleDeferred.await()
            .getOrElse { TacticalResult.Failure("BLE broadcast threw: ${it.message}") }
        val wifiResult = wifiDeferred.await()
            .getOrElse { TacticalResult.Failure("WiFiDirect broadcast threw: ${it.message}") }

        when {
            bleResult is TacticalResult.Success || wifiResult is TacticalResult.Success ->
                TacticalResult.Success(Unit)

            else -> {
                val bleError = (bleResult as? TacticalResult.Failure)?.error ?: "unknown"
                val wifiError = (wifiResult as? TacticalResult.Failure)?.error ?: "unknown"
                TacticalResult.Failure("both bearers failed — BLE: $bleError; WiFiDirect: $wifiError")
            }
        }
    }
    companion object {
        private const val TAG = "CompositeRadioTransport"
    }
}