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

    /**
     * Connect using the stable iTantra application UUID discovered from the
     * Wi-Fi Direct service advertisement. Implementations translate it to the
     * underlying Android P2P device address.
     */
    suspend fun connectByAppDeviceId(deviceId: String): TacticalResult<Unit> =
        TacticalResult.Failure("Wi-Fi Direct app-device lookup is unsupported")

    /**
     * Records the stable iTantra identity learned from the current Wi-Fi group
     * owner's transport hello. This is more authoritative than waiting for
     * DNS-SD and lets a group client render the HEAD immediately.
     */
    fun noteGroupOwnerAppDeviceId(deviceId: String) {}

    /**
     * Prevent the background Wi-Fi reconnect loop from recreating a relationship
     * that was explicitly invalidated by a Wi-Fi group removal.
     *
     * This does not affect BLE and does not prevent an explicit user initiated
     * connect/add operation.
     */
    fun suppressAutoReconnectTo(deviceId: String) {}

    /**
     * Explicit user re-add clears the Wi-Fi-only reconnect suppression.
     */
    fun allowAutoReconnectTo(deviceId: String) {}
}
