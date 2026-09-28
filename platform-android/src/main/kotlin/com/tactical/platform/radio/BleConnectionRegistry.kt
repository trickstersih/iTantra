package com.tactical.platform.radio

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import com.tactical.platform.ble.BlePeerAddressRegistry
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tracks connected BLE peers in both roles (this device as GATT server,
 * this device as GATT client to peers it connected out to), plus each
 * connection's negotiated MTU — needed by BleRadioTransport to fragment
 * correctly per-peer, since different peers can negotiate different MTUs.
 */
class BleConnectionRegistry {

    private val inboundDevices = ConcurrentHashMap<String, BluetoothDevice>()
    private val outboundGatts = ConcurrentHashMap<String, BluetoothGatt>()
    private val negotiatedMtu = ConcurrentHashMap<String, Int>()
    private val rssiByAddress = ConcurrentHashMap<String, Int>()
    private val transferIdCounter = AtomicInteger(0)
    private val outboundWriteWaiters =
        ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val notificationWaiters =
        ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    // Default is the un-negotiated BLE minimum (23 total, 3 reserved for
    // ATT header) — used until onMtuChanged reports a real negotiated value.
    private val defaultUsableMtu = 20

    private val rawIncomingListeners = java.util.concurrent.CopyOnWriteArrayList<(String, ByteArray) -> Unit>()
    private val connectionListeners = java.util.concurrent.CopyOnWriteArrayList<ConnectionListener>()
    private val controlIncomingListeners = java.util.concurrent.CopyOnWriteArrayList<(String, ByteArray) -> Unit>()
    private val controlSenders = java.util.concurrent.CopyOnWriteArrayList<(String, ByteArray) -> Boolean>()

    interface ConnectionListener {
        fun onInboundConnected(device: BluetoothDevice)
        fun onInboundDisconnected(device: BluetoothDevice)
    }

    fun addConnectionListener(listener: ConnectionListener) {
        connectionListeners.add(listener)
    }

    fun removeConnectionListener(listener: ConnectionListener) {
        connectionListeners.remove(listener)
    }

    fun addRawIncomingListener(listener: (String, ByteArray) -> Unit) {
        rawIncomingListeners.add(listener)
    }

    fun removeRawIncomingListener(listener: (String, ByteArray) -> Unit) {
        rawIncomingListeners.remove(listener)
    }

    fun addControlIncomingListener(listener: (String, ByteArray) -> Unit) {
        controlIncomingListeners.add(listener)
    }

    fun removeControlIncomingListener(listener: (String, ByteArray) -> Unit) {
        controlIncomingListeners.remove(listener)
    }

    fun dispatchControlIncoming(peerAddress: String, data: ByteArray) {
        controlIncomingListeners.forEach { it(peerAddress, data) }
    }

    fun addControlSender(sender: (String, ByteArray) -> Boolean) {
        controlSenders.add(sender)
    }

    fun removeControlSender(sender: (String, ByteArray) -> Boolean) {
        controlSenders.remove(sender)
    }

    fun sendControl(peerAddress: String, data: ByteArray): Boolean =
        controlSenders.any { sender ->
            runCatching { sender(peerAddress, data) }.getOrDefault(false)
        }

    /** Raw packet data arriving through the outbound GATT client role. */
    fun dispatchRawIncoming(peerAddress: String, fragment: ByteArray) {
        rawIncomingListeners.forEach { it(peerAddress, fragment) }
    }

    fun lastKnownRssi(address: String): Int? = rssiByAddress[address]

    fun registerInboundConnection(device: BluetoothDevice) {
        inboundDevices[device.address] = device
        BlePeerAddressRegistry.markConnectionActive(device.address)
        connectionListeners.forEach { it.onInboundConnected(device) }
    }

    fun unregisterInboundConnection(device: BluetoothDevice) {
        if (inboundDevices.remove(device.address) != null) {
            BlePeerAddressRegistry.markConnectionInactive(device.address)
            connectionListeners.forEach { it.onInboundDisconnected(device) }
        }
        negotiatedMtu.remove(device.address)
    }

    fun registerOutboundConnection(gatt: BluetoothGatt) {
        outboundGatts[gatt.device.address] = gatt
        BlePeerAddressRegistry.markConnectionActive(gatt.device.address)
    }

    fun unregisterOutboundConnection(address: String) {
        outboundGatts.remove(address)
        BlePeerAddressRegistry.markConnectionInactive(address)
        negotiatedMtu.remove(address)
    }

    fun onMtuNegotiated(address: String, mtu: Int) {
        // 3 bytes reserved for ATT protocol overhead per the BLE spec.
        negotiatedMtu[address] = (mtu - 3).coerceAtLeast(defaultUsableMtu)
    }

    fun usableMtuFor(address: String): Int = negotiatedMtu[address] ?: defaultUsableMtu

    fun updateRssi(address: String, rssi: Int) {
        rssiByAddress[address] = rssi
    }

    fun lastKnownRssi(device: BluetoothDevice): Int? = rssiByAddress[device.address]

    fun inboundConnectedDevices(): List<BluetoothDevice> = inboundDevices.values.toList()
    fun outboundConnectedGatts(): List<BluetoothGatt> = outboundGatts.values.toList()

    fun allConnectedAddresses(): Set<String> = inboundDevices.keys + outboundGatts.keys

    /**
     * Stable application IDs for every currently connected BLE peer whose
     * address has been resolved through the iTantra identity registry.
     */
    fun connectedApplicationIds(): Set<String> =
        allConnectedAddresses()
            .mapNotNull { BlePeerAddressRegistry.applicationIdFor(it) }
            .toSet()

    fun outboundGatt(address: String): BluetoothGatt? = outboundGatts[address]
    fun inboundDevice(address: String): BluetoothDevice? = inboundDevices[address]

    fun registerOutboundWriteWaiter(
        address: String,
        waiter: CompletableDeferred<Boolean>
    ): Boolean =
        outboundWriteWaiters.putIfAbsent(address, waiter) == null

    fun completeOutboundWrite(address: String, success: Boolean) {
        outboundWriteWaiters.remove(address)?.complete(success)
    }

    fun cancelOutboundWriteWaiter(
        address: String,
        waiter: CompletableDeferred<Boolean>
    ) {
        outboundWriteWaiters.remove(address, waiter)
    }

    fun registerNotificationWaiter(
        address: String,
        waiter: CompletableDeferred<Boolean>
    ): Boolean =
        notificationWaiters.putIfAbsent(address, waiter) == null

    fun completeNotification(address: String, success: Boolean) {
        notificationWaiters.remove(address)?.complete(success)
    }

    fun cancelNotificationWaiter(
        address: String,
        waiter: CompletableDeferred<Boolean>
    ) {
        notificationWaiters.remove(address, waiter)
    }

    fun nextTransferId(): Int = transferIdCounter.getAndIncrement()
}