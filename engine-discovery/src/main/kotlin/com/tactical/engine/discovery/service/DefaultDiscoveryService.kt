package com.tactical.engine.discovery.service

import com.tactical.domain.identity.DeviceId
import com.tactical.domain.identity.DeviceNode
import com.tactical.domain.identity.LinkType
import com.tactical.domain.identity.RadioType
import com.tactical.engine.discovery.beacon.BeaconEmitter
import com.tactical.engine.discovery.catalog.DeviceCatalog
import com.tactical.engine.discovery.scanner.BeaconScanner
import com.tactical.platform.api.wifi.WifiDirectManager
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

class DefaultDiscoveryService(
    private val scanner: BeaconScanner,
    private val catalog: DeviceCatalog,
    private val emitter: BeaconEmitter,
    private val wifiDirectManager: WifiDirectManager,
    private val localDeviceId: String,
    private val localCallsignProvider: () -> String,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) : DiscoveryService {

    private var scanJob: Job? = null
    private var meshJob: Job? = null
    private var wifiJob: Job? = null
    private var beaconingStarted = false
    private val immediateScanRequests = Channel<Unit>(Channel.CONFLATED)

    override fun peers() = catalog.all()

    override suspend fun start() {
        // BLE beaconing, mesh reception, and Wi-Fi Direct each have their own
        // lifecycle. Do not return early just because BLE/discovery was already
        // started: Wi-Fi may have been enabled after the first service start.
        if (!beaconingStarted) {
            beaconingStarted = true
            emitter.start()
        }

        val wifiStart = wifiDirectManager.start()
        if (wifiStart is com.tactical.domain.result.TacticalResult.Failure) {
            // BLE remains usable when Wi-Fi Direct is unavailable.
        }

        // Register the same app-specific identity over Wi-Fi Direct.
        // Failure here is non-fatal: BLE can still discover the peer.
        runCatching {
            wifiDirectManager.advertisePresence(localDeviceId, localCallsignProvider())
        }

        // Mesh topology reception is continuous and independent of the
        // duty-cycled direct BLE scan windows. This prevents a 6-second mesh
        // beacon from being missed simply because the scanner is asleep.
        if (meshJob == null) {
            meshJob = scope.launch {
                try {
                    scanner.meshPeers().collect { catalog.upsert(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Mesh reception is best-effort; the next service restart
                    // can recreate the collector if the bearer fails.
                }
            }
        }

        // Discovery owns its background scan lifecycle. This keeps discovery
        // alive for reconnect/address refresh even when the Activity is gone.
        startDiscovery()
    }

    /**
     * Re-attempt Wi-Fi Direct after the Activity has received a runtime
     * permission grant. This deliberately does not restart BLE discovery,
     * keeping existing Bluetooth connections and scan jobs untouched.
     */
    suspend fun refreshWifiDirect() {
        val result = wifiDirectManager.start()
        if (result is com.tactical.domain.result.TacticalResult.Failure) {
            return
        }

        runCatching {
            wifiDirectManager.advertisePresence(
                localDeviceId,
                localCallsignProvider()
            )
        }

        // discoverPeers() starts the manager's underlying service discovery
        // as a side effect; the existing collector in wifiJob remains alive.
        runCatching {
            wifiDirectManager.discoverPeers()
        }

        startDiscovery()
    }

    fun startDiscovery() {
        // Startup and permission callbacks can race. Guard job creation so
        // only one BLE scan loop and one Wi-Fi collector can exist at a time.
        synchronized(this) {
            if (scanJob == null || !scanJob!!.isActive) {
                scanJob = scope.launch {
                    while (isActive) {
                        runBleScanWindow()

                        // Sleep between short scan windows to avoid continuously
                        // burning CPU/battery, but wake immediately for a manual
                        // Home-screen SCAN request.
                        withTimeoutOrNull(BACKGROUND_SCAN_INTERVAL_MS) {
                            immediateScanRequests.receive()
                        }
                        // Either the normal maintenance interval elapsed or a
                        // manual scan request woke the loop early.
                    }
                }
            }

            if (wifiJob == null || !wifiJob!!.isActive) {
                wifiJob = scope.launch {
                    try {
                        wifiDirectManager.discoverPeers().collect { peers ->
                            peers.forEach { peer ->
                                val appDeviceId = peer.appDeviceId ?: return@forEach
                                if (appDeviceId == localDeviceId) return@forEach

                                runCatching {
                                    catalog.upsert(
                                        DeviceNode(
                                            id = DeviceId(appDeviceId),
                                            callsign = peer.callsign ?: peer.deviceName,
                                            rssi = 0,
                                            lastSeen = java.time.Instant.ofEpochMilli(
                                                peer.lastSeenEpochMs.takeIf { it > 0L }
                                                    ?: System.currentTimeMillis()
                                            ),
                                            hopCount = 0,
                                            link = LinkType.DIRECT,
                                            transportStates = mapOf(
                                                RadioType.WIFI_DIRECT to peer.linkState
                                            )
                                        )
                                    )
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Wi-Fi Direct is optional; BLE remains the primary fallback.
                    }
                }
            }
        }
    }

    /**
     * Requests an immediate scan without changing the persistent discovery
     * lifecycle or exposing the background scan state to the UI.
     */
    fun scanNow() {
        immediateScanRequests.trySend(Unit)

        // A manual Home-screen scan is also the explicit recovery path for
        // Wi-Fi Direct. This matters when the app started while Wi-Fi was off
        // and the framework did not deliver a usable P2P state transition.
        scope.launch {
            runCatching { refreshWifiDirect() }
        }
    }

    private suspend fun runBleScanWindow() {
        withTimeoutOrNull(BACKGROUND_SCAN_WINDOW_MS) {
            try {
                scanner.scan().collect { catalog.upsert(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // BLE can be temporarily unavailable; the maintenance loop
                // remains alive and the next cycle will retry.
            }
        }
    }

    fun stopDiscovery() {
        scanJob?.cancel()
        meshJob?.cancel()
        wifiJob?.cancel()
        scanJob = null
        meshJob = null
        wifiJob = null
    }

    override suspend fun stop() {
        stopDiscovery()

        if (beaconingStarted) {
            emitter.stop()
            beaconingStarted = false
        }

        wifiDirectManager.stop()
    }

    companion object {
        // Short scan windows preserve discovery responsiveness while keeping
        // the scanner off most of the time. The 15s catalog TTL is long enough
        // to tolerate the gap between maintenance scans.
        private const val BACKGROUND_SCAN_INTERVAL_MS = 8_000L
        private const val BACKGROUND_SCAN_WINDOW_MS = 3_000L
    }
}
