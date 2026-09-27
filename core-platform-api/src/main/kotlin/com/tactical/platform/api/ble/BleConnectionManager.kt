package com.tactical.platform.api.ble

import com.tactical.domain.result.TacticalResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * BLE physical-link management plus app-level squad membership.
 *
 * Discovery reports nearby devices only. Physical GATT connections are
 * established automatically for approved squad members; squad membership
 * is an application decision and is independent from Android bonding/pairing.
 */
interface BleConnectionManager {
    /** Send an application-level squad request; membership changes only after approval. */
    suspend fun addToSquad(deviceAddress: String): TacticalResult<Unit>

    /**
     * Records squad membership learned through a mesh control response/request
     * without attempting to send the legacy direct-GATT control frame.
     * Existing GATT reconnect logic will establish a physical link when the
     * peer becomes directly reachable.
     */
    suspend fun addMeshSquadMember(deviceId: String, callsign: String): TacticalResult<Unit>

    /** Requests waiting for local user approval. */
    fun pendingSquadRequests(): StateFlow<List<SquadRequest>>

    /** Announce the local callsign over currently connected squad links. */
    suspend fun announceLocalCallsign(callsign: String)

    /** Emits callsign changes received from connected squad peers. */
    fun peerIdentityUpdates(): Flow<PeerIdentityUpdate>

    /** Approve or reject an incoming squad request. */
    suspend fun respondToSquadRequest(deviceId: String, approve: Boolean): TacticalResult<Unit>

    /** Remove an iTantra peer from the application-level squad. */
    suspend fun removeFromSquad(deviceAddress: String)

    /** Establish a GATT session; Android bonding is never required. */
    suspend fun connect(deviceAddress: String): TacticalResult<Unit>

    suspend fun disconnect(deviceAddress: String)
    fun state(deviceAddress: String): Flow<BleLinkState>

    /** Emits the latest RSSI measured from the active GATT connection. */
    fun rssi(deviceAddress: String): Flow<Int?>

    /** Reconnect an application-level squad member after a transient link loss. */
    suspend fun reconnectSquadMember(deviceAddress: String): TacticalResult<Unit>

    /** Force a fresh GATT connection without performing Bluetooth pairing. */
    suspend fun repairAndReconnect(deviceAddress: String): TacticalResult<Unit>

    /** Returns stable iTantra IDs currently selected for the local squad. */
    fun squadDeviceIds(): Set<String>

    /** Returns squad members with an active inbound or outbound GATT session. */
    fun connectedSquadDeviceIds(): Set<String>

    fun diagnostics(): Flow<BleDiagnostics>
}

data class PeerIdentityUpdate(
    val deviceId: String,
    val callsign: String
)

data class SquadRequest(
    val deviceId: String,
    val callsign: String
)

data class BleDiagnostics(
    val advertisingOk: Boolean,
    val scanningOk: Boolean,
    val connectedPeerCount: Int,
    val consecutiveEmptyCycles: Int,
    val consecutiveScanFailures: Int,
    val lastRecoveryEpochMs: Long = 0L,
    val issue: String? = null
)

enum class BleLinkState {
    AVAILABLE,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    FAILED
}
