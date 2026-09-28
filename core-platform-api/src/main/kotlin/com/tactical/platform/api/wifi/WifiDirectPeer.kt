package com.tactical.platform.api.wifi

import com.tactical.domain.identity.RadioLinkState

data class WifiDirectPeer(
    val deviceAddress: String,
    val deviceName: String,
    val appDeviceId: String? = null,
    val callsign: String? = null,
    val linkState: RadioLinkState = RadioLinkState.AVAILABLE,
    val lastSeenEpochMs: Long = 0L
) {
    init {
        require(deviceAddress.isNotBlank()) { "deviceAddress must not be blank" }
    }
}