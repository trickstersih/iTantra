package com.tactical.domain.identity

/**
 * State of one physical radio link to a logical iTantra peer.
 */
enum class RadioLinkState {
    UNAVAILABLE,
    AVAILABLE,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    FAILED
}
