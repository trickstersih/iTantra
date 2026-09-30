package com.tactical.platform.api.squad

/**
 * Application-level squad membership.
 *
 * Squad membership deliberately lives above any physical bearer. BLE and
 * Wi-Fi Direct may both establish links to the same squad member.
 */
interface SquadMembershipStore {
    fun squadDeviceIds(): Set<String>

    fun contains(deviceId: String): Boolean =
        deviceId in squadDeviceIds()

    fun add(deviceId: String)

    fun remove(deviceId: String)
}
