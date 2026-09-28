package com.tactical.domain.identity

/**
 * Physical bearer used by iTantra.
 *
 * These are implementation details of a link, not mutually exclusive
 * application modes. A single peer may have both bearers available.
 */
enum class RadioType {
    BLUETOOTH,
    WIFI_DIRECT
}
