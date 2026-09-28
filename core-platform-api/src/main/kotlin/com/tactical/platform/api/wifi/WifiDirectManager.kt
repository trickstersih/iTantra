package com.tactical.platform.api.wifi

import com.tactical.domain.identity.RadioLinkState
import com.tactical.domain.result.TacticalResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface WifiDirectManager {
    /** Starts the single app-owned Wi-Fi Direct lifecycle. */
    suspend fun start(): TacticalResult<Unit>

    /** Stops Wi-Fi discovery/observation and releases the P2P lifecycle. */
    suspend fun stop()

    /** Advertise this installation as an iTantra Wi-Fi Direct service. */
    suspend fun advertisePresence(
        deviceId: String,
        callsign: String
    ): TacticalResult<Unit>

    /**
     * Returns the shared peer stream. Implementations must not register a new
     * BroadcastReceiver for each collector.
     */
    suspend fun discoverPeers(): Flow<List<WifiDirectPeer>>

    /** Current P2P group topology owned by this manager. */
    fun connectionInfo(): StateFlow<WifiDirectConnectionInfo>

    /** Current Wi-Fi Direct availability/link lifecycle state. */
    fun state(): StateFlow<RadioLinkState>

    suspend fun disconnect(): TacticalResult<Unit>

    /**
     * Initiates a P2P connection to the Android Wi-Fi Direct device address.
     * The application-level iTantra identity is learned separately.
     */
    suspend fun connect(deviceAddress: String): TacticalResult<Unit>
}
