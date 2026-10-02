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
     * Wi-Fi Direct device address (MAC) of the current group owner.
     * This lets the UI identify which squad member is the Wi-Fi group head.
     */
    val groupOwnerDeviceAddress: String? = null,
    /**
     * Stable iTantra application ID of the current Wi-Fi group owner, when
     * the owner has been resolved from Wi-Fi Direct service discovery.
     */
    val groupOwnerAppDeviceId: String? = null,
    /**
     * Wi-Fi Direct device addresses currently reported by the local P2P group.
     * Android may include the local device in this collection.
     */
    val groupMemberDeviceAddresses: Set<String> = emptySet()
)
