package com.tactical.engine.discovery.catalog

import com.tactical.domain.identity.DeviceId
import com.tactical.domain.identity.DeviceNode
import com.tactical.domain.identity.LinkType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * ConcurrentHashMap-backed catalog with a 15s TTL eviction for stale nodes.
 *
 * Upserts are merged by stable DeviceId. A BLE observation must not erase
 * a Wi-Fi Direct observation for the same peer, and vice versa.
 */
class InMemoryDeviceCatalog(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    private val ttlMillis: Long = 15_000L,
    private val currentTimeMillis: () -> Long = { System.currentTimeMillis() }
) : DeviceCatalog {

    private val nodes = ConcurrentHashMap<DeviceId, DeviceNode>()
    private val _nodesFlow = MutableStateFlow<List<DeviceNode>>(emptyList())

    init {
        scope.launch {
            while (isActive) {
                delay(1000)
                evictStaleNodes()
            }
        }
    }

    override fun all(): StateFlow<List<DeviceNode>> = _nodesFlow.asStateFlow()

    override suspend fun upsert(node: DeviceNode) {
        nodes.compute(node.id) { _, current ->
            if (current == null) {
                node
            } else {
                merge(current, node)
            }
        }
        updateFlow()
    }

    private fun merge(
        current: DeviceNode,
        incoming: DeviceNode
    ): DeviceNode {
        val selected = if (shouldReplace(current, incoming)) incoming else current

        val mergedTransportStates =
            current.transportStates + incoming.transportStates

        return selected.copy(
            transportStates = mergedTransportStates
        )
    }

    private fun shouldReplace(
        current: DeviceNode,
        incoming: DeviceNode
    ): Boolean {
        if (incoming.link == LinkType.DIRECT) {
            return true
        }

        if (current.link == LinkType.DIRECT) {
            val age = currentTimeMillis() - current.lastSeen.toEpochMilli()
            if (age <= ttlMillis / 2L) return false
            return true
        }

        if (current.link == LinkType.STALE) return true

        return when {
            incoming.hopCount < current.hopCount -> true
            incoming.hopCount > current.hopCount -> false
            else -> incoming.lastSeen.toEpochMilli() >= current.lastSeen.toEpochMilli()
        }
    }

    private fun evictStaleNodes() {
        val now = currentTimeMillis()
        var changed = false
        val iterator = nodes.entries.iterator()

        while (iterator.hasNext()) {
            val entry = iterator.next()
            val lastSeen = entry.value.lastSeen.toEpochMilli()
            if (lastSeen + ttlMillis < now) {
                iterator.remove()
                changed = true
            } else if (
                lastSeen + (ttlMillis / 2) < now &&
                entry.value.link != LinkType.STALE
            ) {
                nodes[entry.key] = entry.value.copy(link = LinkType.STALE)
                changed = true
            }
        }

        if (changed) {
            updateFlow()
        }
    }

    private fun updateFlow() {
        _nodesFlow.value = nodes.values.toList().sortedBy { it.id.value }
    }
}
