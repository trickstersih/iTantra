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