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
 */
class InMemoryDeviceCatalog(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    private val ttlMillis: Long = 15_000L,
    private val currentTimeMillis: () -> Long = { System.currentTimeMillis() }
) : DeviceCatalog {

    private val nodes = ConcurrentHashMap<DeviceId, DeviceNode>()
    private val _nodesFlow = MutableStateFlow<List<DeviceNode>>(emptyList())

    init {
        // Periodic eviction task
        scope.launch {
            while (isActive) {
                delay(1000)
                evictStaleNodes()
            }
        }
    }

    override fun all(): StateFlow<List<DeviceNode>> = _nodesFlow.asStateFlow()

    override suspend fun upsert(node: DeviceNode) {
        val current = nodes[node.id]
        if (current == null || shouldReplace(current, node)) {
            nodes[node.id] = node
            updateFlow()
        }
    }

    private fun shouldReplace(
        current: DeviceNode,
        incoming: DeviceNode
    ): Boolean {
        if (incoming.link == LinkType.DIRECT) {
            // A newly observed direct path always outranks any relayed path.
            return true
        }

        if (current.link == LinkType.DIRECT) {
            // Keep a direct observation while it is still fresh. Once it has
            // aged past the stale threshold, a live mesh path may take over.
            val age = currentTimeMillis() - current.lastSeen.toEpochMilli()
            if (age <= ttlMillis / 2L) return false
            return true
        }

        if (current.link == LinkType.STALE) return true

        // Both are relayed: prefer fewer hops. If the hop count is equal,
        // use the newest observation so route changes are reflected promptly.
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
            } else if (lastSeen + (ttlMillis / 2) < now && entry.value.link != LinkType.STALE) {
                // Mark as STALE if halfway to eviction
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
