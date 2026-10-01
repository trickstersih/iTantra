package com.tactical.platform.api.wifi

/**
 * Current Android Wi-Fi Direct group topology.
 *
 * The group owner address is sufficient for the current TCP transport:
 * clients connect to the GO, while the GO accepts client sockets.
 */
data class WifiDirectConnectionInfo(
    val groupFormed: Boolean = false,
    val isGroupOwner: Boolean = false,
    val groupOwnerAddress: String? = null,
    /**
     * Wi-Fi Direct device addresses currently in the same P2P group as the
     * local device, excluding the local device itself.
     */
    val groupMemberDeviceAddresses: Set<String> = emptySet()
)
