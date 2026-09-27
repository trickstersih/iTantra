package com.tactical.engine.discovery.scanner

import com.tactical.domain.identity.DeviceNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

interface BeaconScanner {
    /**
     * Cold flow of discovered device nodes from direct BLE scanning and any
     * other scan-time discovery sources.
     */
    fun scan(): Flow<DeviceNode>

    /**
     * Continuous mesh-presence stream. Implementations that receive topology
     * beacons independently of the BLE scan window can override this so the
     * discovery catalog stays updated even while direct BLE scanning is idle.
     */
    fun meshPeers(): Flow<DeviceNode> = emptyFlow()
}
