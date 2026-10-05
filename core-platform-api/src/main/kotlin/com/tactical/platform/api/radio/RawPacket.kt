package com.tactical.platform.api.radio

import com.tactical.domain.identity.RadioType

/**
 * Data class wrapping received bytes from any radio bearer, plus the
 * signal strength and time they were received at.
 */
data class RawPacket(
    val data: ByteArray,
    val rssi: Int,
    val timestamp: Long,
    /**
     * For received packets, the bearer that delivered the packet.
     *
     * For outgoing packets, a non-null value is a bearer preference and null
     * means "send through every available bearer".
     */
    val transport: RadioType? = null,
    /**
     * Optional stable iTantra application IDs for direct delivery.
     *
     * null means transport-wide broadcast. An empty set means there are no
     * direct recipients.
     */
    val targetDeviceIds: Set<String>? = null
) {
    init {
        require(data.isNotEmpty()) { "data must not be empty" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RawPacket) return false
        return data.contentEquals(other.data) &&
            rssi == other.rssi &&
            timestamp == other.timestamp &&
            transport == other.transport &&
            targetDeviceIds == other.targetDeviceIds
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + rssi
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + (transport?.hashCode() ?: 0)
        result = 31 * result + (targetDeviceIds?.hashCode() ?: 0)
        return result
    }
}