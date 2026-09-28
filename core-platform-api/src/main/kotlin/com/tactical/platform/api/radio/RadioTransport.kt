package com.tactical.platform.api.radio

import com.tactical.domain.identity.RadioType
import com.tactical.domain.result.TacticalResult
import kotlinx.coroutines.flow.Flow

/**
 * Bearer-agnostic packet transport.
 *
 * The mesh layer sees one transport even when multiple physical bearers are
 * active. A device may therefore be reachable through BLE, Wi-Fi Direct, or
 * both at the same time.
 */
interface RadioTransport {

    fun incoming(): Flow<RawPacket>

    suspend fun broadcast(raw: RawPacket): TacticalResult<Unit>

    /**
     * Stable iTantra IDs for peers with a currently usable physical link on
     * this bearer. Implementations that cannot yet identify peers return an
     * empty set.
     */
    fun connectedPeerIds(): Set<String> = emptySet()

    /**
     * Identifies this transport when a caller needs to report which bearer
     * delivered an incoming packet.
     */
    val type: RadioType?
}