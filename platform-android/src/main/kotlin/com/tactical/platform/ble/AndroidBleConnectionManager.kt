@file:Suppress("DEPRECATION")

package com.tactical.platform.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import com.tactical.domain.result.TacticalResult
import com.tactical.platform.api.ble.BleConnectionManager
import com.tactical.platform.api.ble.BleDiagnostics
import com.tactical.platform.api.ble.BleLinkState
import com.tactical.platform.api.ble.SquadRequest
import com.tactical.platform.api.squad.SquadMembershipStore
import com.tactical.platform.radio.BleConnectionRegistry
import com.tactical.platform.radio.BleRadioTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** App-level squad membership plus persistent BLE GATT client sessions. Android bonding is not used. */
@SuppressLint("MissingPermission")
class AndroidBleConnectionManager(
    private val context: Context,
    private val registry: BleConnectionRegistry,
    private val localDeviceId: String,
    private val squadMembershipStore: SquadMembershipStore
) : BleConnectionManager, BleConnectionRegistry.ConnectionListener {

    private val states = ConcurrentHashMap<String, MutableStateFlow<BleLinkState>>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<TacticalResult<Unit>>>()
    private val gattClients = ConcurrentHashMap<String, BluetoothGatt>()
    private val rssiStates = ConcurrentHashMap<String, MutableStateFlow<Int?>>()
    private val rssiJobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    private val pendingSquadRequestsById = ConcurrentHashMap<String, SquadRequest>()
    private val pendingSquadRequestAddresses = ConcurrentHashMap<String, String>()
    private val outgoingSquadRequestAddresses = ConcurrentHashMap<String, String>()
    private val _pendingSquadRequests = MutableStateFlow<List<SquadRequest>>(emptyList())
    private val _peerIdentityUpdates =
        MutableSharedFlow<com.tactical.platform.api.ble.PeerIdentityUpdate>(extraBufferCapacity = 16)

    private val controlIncomingListener: (String, ByteArray) -> Unit = { address, data ->
        handleControlMessage(address, data)
    }

    private val controlSender: (String, ByteArray) -> Boolean = { address, data ->
        val gatt = gattClients[address]
        if (gatt == null) {
            false
        } else {
            val characteristic = gatt
                .getService(BleRadioTransport.GATT_SERVICE_UUID)
                ?.getCharacteristic(BleRadioTransport.PACKET_CHARACTERISTIC_UUID)

            if (characteristic == null) {
                false
            } else {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeCharacteristic(
                        characteristic,
                        data,
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    ) == BluetoothStatusCodes.SUCCESS
                } else {
                    characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    characteristic.value = data
                    gatt.writeCharacteristic(characteristic)
                }
            } catch (_: Exception) {
                false
            }
            }
        }
    }

    override fun pendingSquadRequests(): StateFlow<List<SquadRequest>> =
        _pendingSquadRequests.asStateFlow()

    override fun peerIdentityUpdates(): Flow<com.tactical.platform.api.ble.PeerIdentityUpdate> =
        _peerIdentityUpdates.asSharedFlow()

    override suspend fun announceLocalCallsign(callsign: String) {
        val bytes = callsign.toByteArray(Charsets.UTF_8)
        require(bytes.isNotEmpty()) { "BLE callsign must not be blank" }
        require(bytes.size <= 7) { "BLE callsign must be at most 7 UTF-8 bytes" }

        val packet = SquadControlCodec.callsignUpdate(callsign)
        val addresses = squadDeviceIds()
            .mapNotNull { resolveAddress(it) }
            .filter { it in registry.allConnectedAddresses() }
            .toSet()

        addresses.forEach { address ->
            sendControlWithRetry(address, packet)
        }
    }

    private val reconnectScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    private var lastAdvertisingOk = false
    private var lastScanningOk = false
    private var emptyCycles = 0
    private var scanFailures = 0
    private var lastRecovery = 0L

    private val adapterStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                android.util.Log.d(TAG, "Bluetooth turned off; marking BLE links disconnected")
                gattClients.values.toList().forEach { gatt ->
                    try { gatt.disconnect() } catch (_: Exception) {}
                    try { gatt.close() } catch (_: Exception) {}
                }
                gattClients.clear()
                rssiJobs.values.toList().forEach { it.cancel() }
                rssiJobs.clear()
                registry.allConnectedAddresses().toList().forEach { registry.unregisterOutboundConnection(it) }
                states.keys.toList().forEach { setState(it, BleLinkState.DISCONNECTED) }
            } else if (state == BluetoothAdapter.STATE_ON) {
                android.util.Log.d(TAG, "Bluetooth turned on; restoring iTantra links")
                reconnectScope.launch {
                    delay(250L)
                    retryPendingSquadRequests()
                    squadDeviceIds().forEach { id ->
                        runCatching { reconnectWithRoleStagger(id) }
                    }
                }
            }
        }
    }

    init {
        registry.addConnectionListener(this)
        registry.addControlIncomingListener(controlIncomingListener)
        registry.addControlSender(controlSender)

        // Startup GATT can race with the peer's GATT server initialization.
        // Retry quietly every 10 seconds; established sessions are left alone.
        reconnectScope.launch {
            delay(1000L)
            while (true) {
                val adapter = runCatching {
                    context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
                }.getOrNull()

                if (adapter?.isEnabled == true) {
                    retryPendingSquadRequests()
                    squadDeviceIds().forEach { id ->
                        runCatching { reconnectWithRoleStagger(id) }
                    }
                }

                delay(5_000L)
            }
        }

        val adapterFilter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(adapterStateReceiver, adapterFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(adapterStateReceiver, adapterFilter)
        }
    }

    override suspend fun addMeshSquadMember(
        deviceId: String,
        callsign: String
    ): TacticalResult<Unit> {
        if (deviceId.isBlank() || deviceId == localDeviceId) {
            return TacticalResult.Failure("Invalid mesh squad member")
        }

        if (callsign.isNotBlank()) {
            BlePeerAddressRegistry.updateCallsign(deviceId, callsign)
        }

        squadMembershipStore.add(deviceId)

        // Keep a discovered physical address when one is already known, but
        // do not require it. The normal reconnect loop will wait for a future
        // direct beacon to learn the address and then establish GATT.
        resolveAddress(deviceId)?.let { address ->
            rememberAddress(deviceId, address)
        }

        val resolvedAddress = resolveAddress(deviceId)
        if (resolvedAddress != null && hasDirectConnection(resolvedAddress)) {
            setState(resolvedAddress, BleLinkState.CONNECTED)
        } else {
            setState(deviceId, BleLinkState.DISCONNECTED)
        }

        return TacticalResult.Success(Unit)
    }

    override suspend fun addToSquad(deviceAddress: String): TacticalResult<Unit> {
        if (!hasConnectPermission()) {
            return TacticalResult.Failure("Missing BLUETOOTH_CONNECT permission")
        }

        val resolvedAddress = resolveAddress(deviceAddress)
            ?: return TacticalResult.Failure("BLE address not known yet; scan for the device again")

        val appId = BlePeerAddressRegistry.applicationIdFor(resolvedAddress) ?: deviceAddress
        rememberAddress(appId, resolvedAddress)

        val connection = reconnectSquadMember(appId).let { result ->
            if (result is TacticalResult.Success) result else connect(appId)
        }
        if (connection is TacticalResult.Failure) {
            return TacticalResult.Failure("Could not reach $appId: ${connection.error}")
        }

        // A duplicate tap while this request is already pending is a no-op.
        val previousAddress =
            outgoingSquadRequestAddresses.putIfAbsent(appId, resolvedAddress)
        if (previousAddress != null) {
            if (previousAddress != resolvedAddress) {
                outgoingSquadRequestAddresses[appId] = resolvedAddress
            } else {
                return TacticalResult.Success(Unit)
            }
        }

        delay(100L)
        val sent = sendControlWithRetry(
            resolvedAddress,
            SquadControlCodec.request(localDeviceId)
        )
        if (!sent) {
            outgoingSquadRequestAddresses.remove(appId)
            return TacticalResult.Failure("GATT link is up but squad request could not be sent")
        }

        setState(resolvedAddress, BleLinkState.CONNECTED)
        return TacticalResult.Success(Unit)
    }

    private suspend fun sendControlWithRetry(
        address: String,
        data: ByteArray
    ): Boolean {
        repeat(3) { attempt ->
            if (registry.sendControl(address, data)) return true
            if (attempt < 2) delay(150L)
        }
        return false
    }

    override suspend fun removeFromSquad(deviceAddress: String) {
        val resolvedAddress = resolveAddress(deviceAddress) ?: deviceAddress
        val appId = applicationIdForAddress(resolvedAddress) ?: deviceAddress

        registry.sendControl(
            resolvedAddress,
            SquadControlCodec.remove(localDeviceId)
        )

        forgetSquadMember(appId)

        gattClients.remove(resolvedAddress)?.let {
            try { it.disconnect() } catch (_: Exception) {}
            try { it.close() } catch (_: Exception) {}
        }
        registry.unregisterOutboundConnection(resolvedAddress)
        refreshLinkState(resolvedAddress, BleLinkState.AVAILABLE)
        android.util.Log.d(TAG, "Removed " + appId + " from iTantra squad")
    }

    override suspend fun respondToSquadRequest(
        deviceId: String,
        approve: Boolean
    ): TacticalResult<Unit> {
        val request = pendingSquadRequestsById[deviceId]
            ?: return TacticalResult.Failure("Squad request is no longer pending")

        val knownAddress = pendingSquadRequestAddresses[request.deviceId]
            ?: resolveAddress(request.deviceId)

        if (knownAddress == null) {
            return TacticalResult.Failure(
                if (!isBluetoothEnabled()) {
                    "Bluetooth is off. Turn Bluetooth on and try again."
                } else {
                    "Requester is no longer reachable"
                }
            )
        }

        rememberAddress(request.deviceId, knownAddress)

        if (!hasDirectConnection(knownAddress)) {
            val reconnect = connect(request.deviceId)
            if (reconnect is TacticalResult.Failure) {
                return TacticalResult.Failure(
                    "Could not reconnect to requester: ${reconnect.error}"
                )
            }
        }

        val sent = sendControlWithRetry(
            knownAddress,
            SquadControlCodec.response(localDeviceId, approve)
        )
        if (!sent) {
            return TacticalResult.Failure("Could not send squad response; try again")
        }

        if (approve) {
            rememberSquadMember(request.deviceId, knownAddress)
            setState(knownAddress, BleLinkState.CONNECTED)
        } else {
            setState(knownAddress, BleLinkState.AVAILABLE)
        }

        pendingSquadRequestsById.remove(deviceId)
        pendingSquadRequestAddresses.remove(deviceId)
        outgoingSquadRequestAddresses.remove(request.deviceId)
        publishPendingSquadRequests()

        return TacticalResult.Success(Unit)
    }

    override suspend fun connect(deviceAddress: String): TacticalResult<Unit> {
        if (!hasConnectPermission()) return TacticalResult.Failure("Missing BLUETOOTH_CONNECT permission")

        val adapter = context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            setState(deviceAddress, BleLinkState.DISCONNECTED)
            return TacticalResult.Failure("Bluetooth is off")
        }

        val resolvedAddress = resolveAddress(deviceAddress)
            ?: return TacticalResult.Failure("BLE address not known yet; scan for the device again")
        val device = deviceForAddress(resolvedAddress)
            ?: return TacticalResult.Failure("Bluetooth device not found")

        // The app-level paired list is the explicit pairing gate.
        // Android's system bond may be absent after a system/app reset; BLE GATT
        // itself can still establish the transport without requiring that bond.
        // A peer can already be connected in the opposite GATT role:
        // this device is the server and the peer is the client. That link is
        // already bidirectional for iTantra (peer writes to us; we notify it),
        // so do not create a duplicate outbound session.
        if (registry.inboundDevice(resolvedAddress) != null) {
            setState(resolvedAddress, BleLinkState.CONNECTED)
            return TacticalResult.Success(Unit)
        }

        // gattClients contains only fully initialized/ready outbound GATT sessions.
        gattClients[resolvedAddress]?.let {
            setState(resolvedAddress, BleLinkState.CONNECTED)
            return TacticalResult.Success(Unit)
        }

        suspend fun awaitExisting(
            existing: CompletableDeferred<TacticalResult<Unit>>
        ): TacticalResult<Unit> {
            return try {
                withTimeout(20_000L) { existing.await() }
            } catch (_: TimeoutCancellationException) {
                TacticalResult.Failure("Existing GATT connection attempt timed out")
            }
        }

        pending[resolvedAddress]?.let { existing ->
            return awaitExisting(existing)
        }

        val completion = CompletableDeferred<TacticalResult<Unit>>()
        val existing = pending.putIfAbsent(resolvedAddress, completion)
        if (existing != null) {
            return awaitExisting(existing)
        }

        fun failConnection(
            targetGatt: BluetoothGatt?,
            message: String,
            state: BleLinkState = BleLinkState.FAILED
        ) {
            if (targetGatt != null) {
                gattClients.remove(resolvedAddress, targetGatt)
                rssiJobs.remove(resolvedAddress)?.cancel()
                registry.unregisterOutboundConnection(resolvedAddress)
            }
            if (pending.remove(resolvedAddress, completion)) {
                completion.complete(TacticalResult.Failure(message))
            }

            setState(
                resolvedAddress,
                if (hasDirectConnection(resolvedAddress)) {
                    BleLinkState.CONNECTED
                } else {
                    state
                }
            )
            if (targetGatt != null) {
                try { targetGatt.disconnect() } catch (_: Exception) {}
                try { targetGatt.close() } catch (_: Exception) {}
            }
        }

        setState(resolvedAddress, BleLinkState.CONNECTING)
        android.util.Log.d(
            TAG,
            "GATT connect requested for " + resolvedAddress +
                " (squad=true)"
        )

        return try {
            val callback = object : BluetoothGattCallback() {

                private var serviceDiscoveryStarted = false

                private fun isCurrentGatt(gatt: BluetoothGatt): Boolean =
                    pending[resolvedAddress] === completion || gattClients[resolvedAddress] === gatt

                private fun startServiceDiscovery(gatt: BluetoothGatt) {
                    if (serviceDiscoveryStarted || !isCurrentGatt(gatt)) return
                    serviceDiscoveryStarted = true
                    android.util.Log.d(
                        TAG,
                        "GATT connected, discovering services for " + resolvedAddress
                    )
                    val started = try {
                        gatt.discoverServices()
                    } catch (_: Exception) {
                        false
                    }
                    if (!started) {
                        failConnection(
                            gatt,
                            "GATT service discovery could not start"
                        )
                    }
                }

                override fun onConnectionStateChange(
                    gatt: BluetoothGatt,
                    status: Int,
                    newState: Int
                ) {
                    if (!isCurrentGatt(gatt)) {
                        try { gatt.close() } catch (_: Exception) {}
                        return
                    }

                    if (newState == BluetoothProfile.STATE_CONNECTED &&
                        status == BluetoothGatt.GATT_SUCCESS
                    ) {
                        setState(resolvedAddress, BleLinkState.CONNECTING)

                        android.util.Log.d(
                            TAG,
                            "GATT connected, requesting MTU " + DESIRED_MTU +
                                " for " + resolvedAddress
                        )

                        val mtuRequestStarted = try {
                            gatt.requestMtu(DESIRED_MTU)
                        } catch (_: Exception) {
                            false
                        }

                        if (!mtuRequestStarted) {
                            startServiceDiscovery(gatt)
                        } else {
                            reconnectScope.launch {
                                delay(MTU_NEGOTIATION_FALLBACK_MS)
                                startServiceDiscovery(gatt)
                            }
                        }
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        gattClients.remove(resolvedAddress, gatt)
                        registry.unregisterOutboundConnection(resolvedAddress)
                        rssiJobs.remove(resolvedAddress)?.cancel()
                        if (pending.remove(resolvedAddress, completion)) {
                            completion.complete(
                                TacticalResult.Failure("GATT disconnected: status=$status")
                            )
                        }

                        // Do not report DISCONNECTED when the same peer is
                        // still connected through the inbound GATT role.
                        refreshLinkState(
                            resolvedAddress,
                            if (squadDeviceIds().contains(
                                    applicationIdForAddress(resolvedAddress)
                                )
                            ) {
                                BleLinkState.DISCONNECTED
                            } else {
                                BleLinkState.AVAILABLE
                            }
                        )
                        try { gatt.close() } catch (_: Exception) {}
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    if (!isCurrentGatt(gatt)) {
                        try { gatt.close() } catch (_: Exception) {}
                        return
                    }

                    android.util.Log.d(
                        TAG,
                        "GATT services discovered for " + resolvedAddress + ", status=" + status
                    )

                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        failConnection(
                            gatt,
                            "GATT service discovery failed: $status"
                        )
                        return
                    }

                    val service = gatt.getService(BleRadioTransport.GATT_SERVICE_UUID)
                    val characteristic =
                        service?.getCharacteristic(BleRadioTransport.PACKET_CHARACTERISTIC_UUID)

                    if (characteristic == null) {
                        failConnection(
                            gatt,
                            "iTantra GATT service not found"
                        )
                        return
                    }

                    if (!gatt.setCharacteristicNotification(characteristic, true)) {
                        failConnection(
                            gatt,
                            "Could not enable notifications"
                        )
                        return
                    }

                    val descriptor = characteristic.getDescriptor(CCCD_UUID)
                    if (descriptor == null) {
                        failConnection(
                            gatt,
                            "CCCD descriptor missing"
                        )
                        return
                    }

                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            val rc = gatt.writeDescriptor(
                                descriptor,
                                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            )
                            if (rc != BluetoothStatusCodes.SUCCESS) {
                                failConnection(
                                    gatt,
                                    "Notification descriptor write failed: $rc"
                                )
                            }
                        } else {
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            if (!gatt.writeDescriptor(descriptor)) {
                                failConnection(
                                    gatt,
                                    "Notification descriptor write failed"
                                )
                            }
                        }
                    } catch (e: Exception) {
                        failConnection(
                            gatt,
                            "Notification setup failed: ${e.message}"
                        )
                    }
                }

                override fun onDescriptorWrite(
                    gatt: BluetoothGatt,
                    descriptor: BluetoothGattDescriptor,
                    status: Int
                ) {
                    if (!isCurrentGatt(gatt) || descriptor.uuid != CCCD_UUID) return

                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        // The GATT becomes visible to the transport only after
                        // service discovery + CCCD configuration have both succeeded.
                        gattClients[resolvedAddress] = gatt
                        registry.registerOutboundConnection(gatt)
                        setState(resolvedAddress, BleLinkState.CONNECTED)
                        startRssiPolling(resolvedAddress, gatt)
                        android.util.Log.d(
                            TAG,
                            "GATT ready for " + resolvedAddress
                        )
                        registry.sendControl(
                            resolvedAddress,
                            SquadControlCodec.hello(localDeviceId)
                        )
                        if (pending.remove(resolvedAddress, completion)) {
                            completion.complete(TacticalResult.Success(Unit))
                        }
                    } else {
                        failConnection(
                            gatt,
                            "CCCD write status=$status"
                        )
                    }
                }

                @Deprecated("Use the value overload on API 33+.")
                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic
                ) {
                    if (gattClients[resolvedAddress] === gatt &&
                        characteristic.uuid == BleRadioTransport.PACKET_CHARACTERISTIC_UUID
                    ) {
                        val value = characteristic.value
                        if (SquadControlCodec.decode(value) != null) {
                            registry.dispatchControlIncoming(resolvedAddress, value)
                            return
                        }
                        android.util.Log.d(
                            TAG,
                            "Incoming BLE notification from " + resolvedAddress +
                                ", bytes=" + value.size
                        )
                        registry.dispatchRawIncoming(resolvedAddress, value)
                    }
                }

                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray
                ) {
                    if (gattClients[resolvedAddress] === gatt &&
                        characteristic.uuid == BleRadioTransport.PACKET_CHARACTERISTIC_UUID
                    ) {
                        val control = SquadControlCodec.decode(value)
                        if (control != null) {
                            registry.dispatchControlIncoming(resolvedAddress, value)
                            return
                        }

                        android.util.Log.d(
                            TAG,
                            "Incoming BLE notification from " + resolvedAddress +
                                ", bytes=" + value.size
                        )
                        registry.dispatchRawIncoming(resolvedAddress, value)
                    }
                }

                override fun onCharacteristicWrite(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int
                ) {
                    if (gattClients[resolvedAddress] === gatt &&
                        characteristic.uuid == BleRadioTransport.PACKET_CHARACTERISTIC_UUID
                    ) {
                        registry.completeOutboundWrite(
                            resolvedAddress,
                            status == BluetoothGatt.GATT_SUCCESS
                        )
                    }
                }

                override fun onReadRemoteRssi(
                    gatt: BluetoothGatt,
                    rssi: Int,
                    status: Int
                ) {
                    if (status == BluetoothGatt.GATT_SUCCESS &&
                        gattClients[resolvedAddress] === gatt
                    ) {
                        registry.updateRssi(resolvedAddress, rssi)
                        rssiState(resolvedAddress).value = rssi
                    }
                }

                override fun onMtuChanged(
                    gatt: BluetoothGatt,
                    mtu: Int,
                    status: Int
                ) {
                    if (gattClients[resolvedAddress] === gatt ||
                        pending[resolvedAddress] === completion
                    ) {
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            registry.onMtuNegotiated(resolvedAddress, mtu)
                        }
                        startServiceDiscovery(gatt)
                    }
                }
            }

            val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(
                    context,
                    false,
                    callback,
                    BluetoothDevice.TRANSPORT_LE
                )
            } else {
                device.connectGatt(context, false, callback)
            }

            try {
                withTimeout(20_000L) { completion.await() }
            } catch (_: TimeoutCancellationException) {
                if (pending.remove(resolvedAddress, completion) != null) {
                    setState(resolvedAddress, BleLinkState.FAILED)
                }
                gattClients.remove(resolvedAddress, gatt)
                rssiJobs.remove(resolvedAddress)?.cancel()
                registry.unregisterOutboundConnection(resolvedAddress)
                try { gatt.disconnect() } catch (_: Exception) {}
                try { gatt.close() } catch (_: Exception) {}
                TacticalResult.Failure("GATT connection timed out")
            }
        } catch (e: Exception) {
            if (pending.remove(resolvedAddress, completion) != null) {
                setState(resolvedAddress, BleLinkState.FAILED)
            }
            TacticalResult.Failure("Unable to connect: ${e.message}")
        }
    }

    private fun handleControlMessage(address: String, data: ByteArray) {
        when (val message = SquadControlCodec.decode(data)) {
            is SquadControlCodec.Message.Hello -> {
                if (message.deviceId == localDeviceId) return
                rememberAddress(message.deviceId, address)
                setState(address, if (hasDirectConnection(address)) {
                    BleLinkState.CONNECTED
                } else {
                    BleLinkState.AVAILABLE
                })
            }

            is SquadControlCodec.Message.Request -> {
                if (message.deviceId == localDeviceId) return
                rememberAddress(message.deviceId, address)
                if (message.deviceId in squadDeviceIds()) {
                    reconnectScope.launch {
                        sendControlWithRetry(
                            address,
                            SquadControlCodec.response(localDeviceId, true)
                        )
                    }
                    setState(address, BleLinkState.CONNECTED)
                } else {
                    synchronized(pendingSquadRequestsById) {
                        pendingSquadRequestsById[message.deviceId] =
                            SquadRequest(
                                message.deviceId,
                                BlePeerAddressRegistry.callsignFor(message.deviceId)
                                    ?: message.deviceId.take(8)
                            )
                        pendingSquadRequestAddresses[message.deviceId] = address
                        publishPendingSquadRequestsLocked()
                    }
                }
            }

            is SquadControlCodec.Message.Response -> {
                rememberAddress(message.deviceId, address)
                if (message.accepted) {
                    rememberSquadMember(message.deviceId, address)
                    setState(address, BleLinkState.CONNECTED)
                } else {
                    setState(address, BleLinkState.AVAILABLE)
                }
                outgoingSquadRequestAddresses.remove(message.deviceId)
            }

            is SquadControlCodec.Message.CallsignUpdate -> {
                val appId = applicationIdForAddress(address) ?: return
                BlePeerAddressRegistry.updateCallsign(appId, message.callsign)
                _peerIdentityUpdates.tryEmit(
                    com.tactical.platform.api.ble.PeerIdentityUpdate(
                        deviceId = appId,
                        callsign = message.callsign
                    )
                )
            }

            is SquadControlCodec.Message.Remove -> {
                if (message.deviceId == localDeviceId) return
                forgetSquadMember(message.deviceId)
                synchronized(pendingSquadRequestsById) {
                    pendingSquadRequestsById.remove(message.deviceId)
                    pendingSquadRequestAddresses.remove(message.deviceId)
                    publishPendingSquadRequestsLocked()
                }
                outgoingSquadRequestAddresses.remove(message.deviceId)
                setState(address, BleLinkState.AVAILABLE)
                android.util.Log.d(TAG, "Remote squad removal received from " + message.deviceId)
            }

            null -> Unit
        }
    }
    override suspend fun disconnect(deviceAddress: String) {
        val resolvedAddress = resolveAddress(deviceAddress) ?: deviceAddress
        gattClients.remove(resolvedAddress)?.let {
            try { it.disconnect() } catch (_: Exception) {}
            try { it.close() } catch (_: Exception) {}
        }
        registry.unregisterOutboundConnection(resolvedAddress)
        refreshLinkState(
            resolvedAddress,
            if (squadDeviceIds().contains(
                    applicationIdForAddress(resolvedAddress)
                )
            ) {
                BleLinkState.DISCONNECTED
            } else {
                BleLinkState.AVAILABLE
            }
        )
    }

    override fun state(deviceAddress: String): Flow<BleLinkState> = stateFlow(resolveAddress(deviceAddress) ?: deviceAddress).asStateFlow()

    override fun rssi(deviceAddress: String): Flow<Int?> =
        rssiState(resolveAddress(deviceAddress) ?: deviceAddress).asStateFlow()

    private fun publishPendingSquadRequests() {
        synchronized(pendingSquadRequestsById) {
            publishPendingSquadRequestsLocked()
        }
    }

    private fun publishPendingSquadRequestsLocked() {
        _pendingSquadRequests.value = pendingSquadRequestsById.values
            .sortedBy { it.callsign.lowercase() }
    }

    private fun isBluetoothEnabled(): Boolean =
        context.getSystemService(android.bluetooth.BluetoothManager::class.java)
            ?.adapter
            ?.isEnabled == true

    private suspend fun retryPendingSquadRequests() {
        outgoingSquadRequestAddresses.forEach { (peerId, knownAddress) ->
            runCatching {
                val result = connect(peerId)
                if (result is TacticalResult.Success) {
                    val currentAddress = resolveAddress(peerId) ?: knownAddress
                    if (sendControlWithRetry(
                            currentAddress,
                            SquadControlCodec.request(localDeviceId)
                        )
                    ) {
                        outgoingSquadRequestAddresses[peerId] = currentAddress
                        android.util.Log.d(
                            TAG,
                            "Retried pending squad request to " + peerId
                        )
                    }
                }
            }
        }
    }

    override fun connectedSquadDeviceIds(): Set<String> {
        val squadIds = squadDeviceIds()
        val connectedAddresses = registry.allConnectedAddresses()

        return squadIds.filter { id ->
            // Fast path: the current app-id -> address mapping points at a
            // live GATT session.
            val mappedAddress = resolveAddress(id)
            if (mappedAddress != null && mappedAddress in connectedAddresses) {
                return@filter true
            }

            // Robust path: Android may expose a different BLE address for the
            // same peer over time. Check the reverse app-id mapping for every
            // currently live inbound/outbound GATT address as well.
            connectedAddresses.any { address ->
                applicationIdForAddress(address) == id
            }
        }.toSet()
    }

    override fun squadDeviceIds(): Set<String> =
        squadMembershipStore.squadDeviceIds()

    private suspend fun reconnectWithRoleStagger(deviceAddress: String) {
        val resolved = resolveAddress(deviceAddress)
        val peerId = if (resolved != null) {
            BlePeerAddressRegistry.applicationIdFor(resolved) ?: deviceAddress
        } else {
            deviceAddress
        }
        if (localDeviceId > peerId) delay(250L)
        runCatching { reconnectSquadMember(peerId) }
    }
    override suspend fun reconnectSquadMember(deviceAddress: String): TacticalResult<Unit> {
        val adapter = context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            setState(deviceAddress, BleLinkState.DISCONNECTED)
            return TacticalResult.Failure("Bluetooth is off")
        }

        val resolvedAddress = resolveAddress(deviceAddress)
            ?: return TacticalResult.Failure("BLE address not known yet; scan for the device again")

        // Prefer an already-established inbound GATT session. The local
        // server can notify that client, while the client can write back to
        // this server, so another outbound session is unnecessary.
        if (registry.inboundDevice(resolvedAddress) != null) {
            setState(resolvedAddress, BleLinkState.CONNECTED)
            return TacticalResult.Success(Unit)
        }

        if (gattClients.containsKey(resolvedAddress)) {
            setState(resolvedAddress, BleLinkState.CONNECTED)
            return TacticalResult.Success(Unit)
        }

        val device = deviceForAddress(resolvedAddress)
            ?: return TacticalResult.Failure("Bluetooth device not found")
        return connect(deviceAddress)
    }

    override suspend fun repairAndReconnect(deviceAddress: String): TacticalResult<Unit> {
        // "Repair" now means reset and re-establish the GATT session. It never
        // invokes Android Bluetooth bonding.
        disconnect(deviceAddress)
        return connect(deviceAddress)
    }

    override fun onInboundConnected(device: BluetoothDevice) {
        // The scan/address registry is intentionally in-memory, so after
        // process recreation an inbound GATT can arrive before discovery has
        // repopulated it. Recover the stable iTantra ID from the persisted
        // appId -> address mapping as a fallback.
        val appId = applicationIdForAddress(device.address) ?: return
        rememberAddress(appId, device.address)
        setState(device.address, BleLinkState.CONNECTED)
        android.util.Log.d(
            TAG,
            "Inbound BLE link ready for " + appId +
                " (no Android pairing)"
        )
    }

    override fun onInboundDisconnected(device: BluetoothDevice) {
        // The peer may still have an outbound GATT session to this device.
        // Compute the state from both GATT roles instead of blindly marking
        // the address disconnected.
        val appId = applicationIdForAddress(device.address)
        refreshLinkState(
            device.address,
            if (appId != null && squadDeviceIds().contains(appId)) {
                BleLinkState.DISCONNECTED
            } else {
                BleLinkState.AVAILABLE
            }
        )
        android.util.Log.d(
            TAG,
            "Inbound BLE link disconnected for " + (appId ?: device.address)
        )
    }

    override fun diagnostics(): Flow<BleDiagnostics> = MutableStateFlow(
        BleDiagnostics(lastAdvertisingOk, lastScanningOk, registry.allConnectedAddresses().size, emptyCycles, scanFailures, lastRecovery, if (scanFailures >= 3) "BLE scan has failed repeatedly" else null)
    ).asStateFlow()

    fun updateDiscoveryHealth(advertisingOk: Boolean, scanningOk: Boolean, emptyCycle: Boolean) {
        lastAdvertisingOk = advertisingOk
        lastScanningOk = scanningOk
        scanFailures = if (scanningOk) 0 else scanFailures + 1
        emptyCycles = if (emptyCycle) emptyCycles + 1 else 0
    }

    fun markRecovery(epochMs: Long) {
        lastRecovery = epochMs
        scanFailures = 0
        emptyCycles = 0
    }

    private fun rememberAddress(appId: String, address: String) {
        prefs.edit()
            .putString(PREF_ADDRESS_PREFIX + appId, address)
            .apply()
        BlePeerAddressRegistry.remember(appId, address)
    }

    private fun rssiState(address: String): MutableStateFlow<Int?> =
        rssiStates.computeIfAbsent(address) { MutableStateFlow(null) }

    private fun startRssiPolling(address: String, gatt: BluetoothGatt) {
        rssiJobs.remove(address)?.cancel()
        rssiJobs[address] = reconnectScope.launch {
            while (gattClients[address] === gatt) {
                try {
                    gatt.readRemoteRssi()
                } catch (_: Exception) {
                    // A failed RSSI read is transient.
                }
                delay(2500L)
            }
        }
    }

    private fun resolveAddress(identifier: String): String? {
        val memory = BlePeerAddressRegistry.addressFor(identifier)
        if (memory != null) return memory

        val persisted = prefs.getString(PREF_ADDRESS_PREFIX + identifier, null)
        if (persisted != null && !isBluetoothAddress(identifier)) {
            BlePeerAddressRegistry.remember(identifier, persisted)
        }
        return persisted
    }

    private fun applicationIdForAddress(address: String): String? {
        BlePeerAddressRegistry.applicationIdFor(address)?.let { return it }

        // Recover the stable app id even before discovery has refreshed the
        // in-memory address registry.
        return squadDeviceIds().firstOrNull { id ->
            prefs.getString(PREF_ADDRESS_PREFIX + id, null) == address
        }
    }

    private fun hasDirectConnection(address: String): Boolean =
        registry.inboundDevice(address) != null ||
            registry.outboundGatt(address) != null

    private fun refreshLinkState(address: String, disconnectedState: BleLinkState) {
        if (hasDirectConnection(address)) {
            setState(address, BleLinkState.CONNECTED)
        } else {
            setState(address, disconnectedState)
        }
    }

    private fun rememberSquadMember(identifier: String, address: String) {
        val appId = BlePeerAddressRegistry.applicationIdFor(address) ?: identifier
        squadMembershipStore.add(appId)
        prefs.edit()
            .putString(PREF_ADDRESS_PREFIX + appId, address)
            .apply()
        BlePeerAddressRegistry.remember(appId, address)
    }

    private fun forgetSquadMember(appId: String) {
        squadMembershipStore.remove(appId)
        prefs.edit()
            .remove(PREF_ADDRESS_PREFIX + appId)
            .apply()
    }

    private fun stateFlow(address: String): MutableStateFlow<BleLinkState> =
        states.computeIfAbsent(address) { MutableStateFlow(initialState(address)) }

    /**
     * UI code normally observes peers by stable iTantra application ID, while
     * the GATT callbacks report the physical Bluetooth address. Keep both keys
     * synchronized so a connection that appears after discovery is reflected
     * immediately without requiring an app restart.
     */
    private fun setState(address: String, state: BleLinkState) {
        stateFlow(address).value = state

        val appId = applicationIdForAddress(address)
        if (!appId.isNullOrBlank() && appId != address) {
            stateFlow(appId).value = state
        }
    }

    private fun initialState(address: String): BleLinkState =
        if (hasDirectConnection(address)) BleLinkState.CONNECTED else BleLinkState.AVAILABLE
    private fun deviceForAddress(identifier: String): BluetoothDevice? {
        if (!hasConnectPermission()) return null
        return try {
            val address = resolveAddress(identifier) ?: return null
            context.getSystemService(android.bluetooth.BluetoothManager::class.java)
                ?.adapter
                ?.getRemoteDevice(address)
        } catch (_: Exception) {
            null
        }
    }
    private fun hasConnectPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun isBluetoothAddress(value: String): Boolean =
        value.matches(Regex("(?i)^([0-9a-f]{2}:){5}[0-9a-f]{2}$"))

    companion object {
        private const val TAG = "AndroidBleConnection"
        private const val PREFS_NAME = "itantra_ble_links"
        private const val PREF_ADDRESS_PREFIX = "address_"
        private const val DESIRED_MTU = 247
        private const val MTU_NEGOTIATION_FALLBACK_MS = 2000L
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}