package com.tactical.platform.radio

import com.tactical.domain.identity.RadioType
import com.tactical.domain.result.TacticalResult
import com.tactical.platform.api.radio.RadioTransport
import com.tactical.platform.api.radio.RawPacket
import com.tactical.platform.api.wifi.WifiDirectManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Wi-Fi Direct packet transport.
 *
 * P2P group formation belongs exclusively to WifiDirectManager. This class
 * observes the manager's shared group-state flow and owns only the TCP data
 * sockets. Incoming packets are emitted through one hot shared flow, so
 * multiple consumers never create multiple receivers or server sockets.
 */
class WifiDirectRadioTransport(
    private val wifiDirectManager: WifiDirectManager,
    private val localDeviceId: String,
    private val localCallsignProvider: () -> String,
    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : RadioTransport {

    override val type: RadioType = RadioType.WIFI_DIRECT

    private val incomingPackets = MutableSharedFlow<RawPacket>(
        replay = 0,
        extraBufferCapacity = 64
    )

    private val socketsByPeerId = ConcurrentHashMap<String, Socket>()
    private val peerIdBySocket = ConcurrentHashMap<Socket, String>()
    private val writeLocks = ConcurrentHashMap<Socket, Mutex>()
    private val intentionallyDisconnectedPeers =
        ConcurrentHashMap.newKeySet<String>()
    @Volatile
    private var knownGroupOwnerAppDeviceId: String? = null
    private val reconfigureLock = Mutex()

    @Volatile
    private var serverSocket: ServerSocket? = null
    private val serverStarting = AtomicBoolean(false)

    @Volatile
    private var activeGroupEndpoint: GroupEndpoint? = null

    init {
        scope.launch {
            wifiDirectManager.connectionInfo().collectLatest { info ->
                reconfigureLock.withLock {
                    val endpoint = GroupEndpoint(
                        groupFormed = info.groupFormed,
                        isGroupOwner = info.isGroupOwner,
                        groupOwnerAddress = info.groupOwnerAddress
                    )

                    // Group membership can change while the same P2P group
                    // remains alive. Do not tear down working TCP sockets in
                    // response to those membership-only updates.
                    if (endpoint == activeGroupEndpoint) {
                        return@withLock
                    }

                    val wasGroupFormed = activeGroupEndpoint?.groupFormed == true

                    // A fresh P2P group does not invalidate an explicit
                    // per-peer squad removal. Keep suppression latched until
                    // that specific peer is deliberately re-added through
                    // allowPeer(), rather than clearing every removed peer when
                    // an unrelated group is formed.
                    if (!wasGroupFormed && info.groupFormed) {
                        knownGroupOwnerAppDeviceId = null
                        android.util.Log.d(
                            TAG,
                            "Wi-Fi Direct fresh group formed; preserved per-peer socket suppression"
                        )
                    }

                    closeDataSockets()

                    if (!info.groupFormed) {
                        activeGroupEndpoint = null
                        return@withLock
                    }

                    activeGroupEndpoint = endpoint

                    if (info.isGroupOwner) {
                        startServer()
                    } else {
                        val groupOwnerAddress = info.groupOwnerAddress
                        if (!groupOwnerAddress.isNullOrBlank()) {
                            connectToGroupOwner(groupOwnerAddress)
                        }
                    }
                }
            }
        }
    }

    override fun incoming(): SharedFlow<RawPacket> =
        incomingPackets.asSharedFlow()

    override fun connectedPeerIds(): Set<String> =
        socketsByPeerId.keys.toSet()

    override fun connectedPeerIdsByTransport(): Map<RadioType, Set<String>> =
        mapOf(RadioType.WIFI_DIRECT to connectedPeerIds())

    override fun allowPeer(deviceId: String) {
        intentionallyDisconnectedPeers.remove(deviceId)
    }

    override suspend fun disconnectPeer(deviceId: String) {
        intentionallyDisconnectedPeers.add(deviceId)

        socketsByPeerId[deviceId]?.let { socket ->
            removeSocket(socket)
        }

        // For the direct two-phone case, do not leave an empty P2P group alive
        // after squad removal. Keeping the same group makes the peer's
        // transport suppression indistinguishable from a link that should be
        // repaired, which prevents a later manual Add-to-Squad from creating a
        // clean negotiation. Multi-member groups remain intact.
        val groupInfo = wifiDirectManager.connectionInfo().value
        if (
            groupInfo.groupFormed &&
            groupInfo.groupMemberDeviceAddresses.size <= 2
        ) {
            runCatching {
                wifiDirectManager.disconnect()
            }.onFailure { error ->
                android.util.Log.d(
                    TAG,
                    "Wi-Fi Direct two-phone group teardown after squad removal failed: " +
                        (error.message ?: error.javaClass.simpleName)
                )
            }
        }
    }

    override suspend fun broadcast(raw: RawPacket): TacticalResult<Unit> =
        withContext(Dispatchers.IO) {
            val peers = socketsByPeerId.values.distinct()
            if (peers.isEmpty()) {
                return@withContext TacticalResult.Failure(
                    "No connected Wi-Fi Direct peers"
                )
            }

            var anySucceeded = false

            // RawPacket.targetDeviceIds is an application-level delivery gate.
            // Physical forwarding remains a broadcast so an intermediate node
            // can relay a packet toward a target that is not directly adjacent.
            for (socket in peers) {
                if (sendFramedPacket(socket, raw.data)) {
                    anySucceeded = true
                }
            }

            if (anySucceeded) {
                TacticalResult.Success(Unit)
            } else {
                TacticalResult.Failure("Wi-Fi Direct transfer failed")
            }
        }

    private fun startServer() {
        if (serverSocket != null || !serverStarting.compareAndSet(false, true)) {
            return
        }

        scope.launch {
            val server = try {
                ServerSocket(TRANSPORT_PORT)
            } catch (e: IOException) {
                serverStarting.set(false)
                android.util.Log.w(
                    TAG,
                    "Wi-Fi Direct TCP server could not start: ${e.message}"
                )
                return@launch
            }

            serverSocket = server
            serverStarting.set(false)
            android.util.Log.d(
                TAG,
                "Wi-Fi Direct TCP server listening on port " + TRANSPORT_PORT
            )

            try {
                while (!server.isClosed) {
                    val socket = server.accept()
                    socket.tcpNoDelay = true
                    registerSocket(socket)

                    scope.launch {
                        try {
                            sendHello(socket)
                            readLoop(socket)
                        } finally {
                            removeSocket(socket)
                        }
                    }
                }
            } catch (_: IOException) {
                // Expected when the P2P group disappears and the server closes.
            } finally {
                if (serverSocket === server) {
                    serverSocket = null
                }
                serverStarting.set(false)
                try {
                    server.close()
                } catch (_: IOException) {
                }
            }
        }
    }

    private fun connectToGroupOwner(groupOwnerAddress: String) {
        scope.launch {
            var attempt = 0
            var consecutiveSocketFailures = 0
            var hostUnreachableFailures = 0

            // Android can keep the Wi-Fi Direct group formed while the TCP
            // data socket is temporarily lost (range changes, OEM power
            // management, app restart, etc.). Keep repairing the socket for as
            // long as this is still the active group instead of giving up
            // after a few disconnects.
            //
            // A repeated EHOSTUNREACH/"No route to host" is different: it means
            // the local phone is holding a stale P2P route, commonly after the
            // remote phone toggles Wi-Fi. In that case continuing to retry the
            // same group owner address can leave us stuck forever because the
            // manager still reports groupFormed=true and therefore will not
            // enter its normal auto-reconnect path.
            while (true) {
                val info = wifiDirectManager.connectionInfo().value
                if (
                    !info.groupFormed ||
                    info.isGroupOwner ||
                    info.groupOwnerAddress != groupOwnerAddress
                ) {
                    return@launch
                }

                val ownerAppDeviceId = knownGroupOwnerAppDeviceId
                if (
                    ownerAppDeviceId != null &&
                    intentionallyDisconnectedPeers.contains(ownerAppDeviceId)
                ) {
                    android.util.Log.d(
                        TAG,
                        "Wi-Fi Direct socket reconnect suppressed after squad removal"
                    )
                    return@launch
                }


                // Do not create a second socket while an existing iTantra
                // session is still registered for this group owner.
                if (socketsByPeerId.isNotEmpty()) {
                    kotlinx.coroutines.delay(SOCKET_RETRY_DELAY_MS)
                    continue
                }

                attempt += 1
                val socket = Socket()

                try {
                    socket.tcpNoDelay = true
                    socket.keepAlive = true
                    socket.connect(
                        InetSocketAddress(groupOwnerAddress, TRANSPORT_PORT),
                        CONNECT_TIMEOUT_MS
                    )
                    registerSocket(socket)
                    sendHello(socket)
                    consecutiveSocketFailures = 0
                    hostUnreachableFailures = 0
                    android.util.Log.d(
                        TAG,
                        "Wi-Fi Direct group-owner socket connected on attempt " +
                            attempt
                    )
                    readLoop(socket)
                } catch (e: IOException) {
                    val message = e.message ?: "I/O error"
                    val hostUnreachable =
                        message.contains("EHOSTUNREACH", ignoreCase = true) ||
                            message.contains("No route to host", ignoreCase = true)
                    val connectionRefused =
                        message.contains("ECONNREFUSED", ignoreCase = true) ||
                            message.contains("Connection refused", ignoreCase = true)
                    val connectionTimedOut =
                        message.contains("timed out", ignoreCase = true) ||
                            message.contains("timeout", ignoreCase = true)

                    consecutiveSocketFailures += 1
                    if (hostUnreachable) {
                        hostUnreachableFailures += 1
                    } else {
                        hostUnreachableFailures = 0
                    }

                    android.util.Log.d(
                        TAG,
                        "Wi-Fi Direct group-owner socket attempt " +
                            attempt +
                            " failed: " +
                            message +
                            " consecutiveFailures=" +
                            consecutiveSocketFailures +
                            " hostUnreachableFailures=" +
                            hostUnreachableFailures
                    )

                    val shouldRebuildGroup =
                        hostUnreachableFailures >= STALE_GROUP_ROUTE_FAILURES ||
                            (connectionRefused &&
                                consecutiveSocketFailures >= CONNECTION_REFUSED_GROUP_FAILURES) ||
                            (connectionTimedOut &&
                                consecutiveSocketFailures >= CONNECTION_TIMEOUT_GROUP_FAILURES)

                    if (shouldRebuildGroup) {
                        val reason = when {
                            hostUnreachable ->
                                "repeated EHOSTUNREACH/No route to host"
                            connectionRefused ->
                                "repeated ECONNREFUSED/Connection refused"
                            else ->
                                "repeated TCP connection timeouts"
                        }

                        android.util.Log.w(
                            TAG,
                            "Wi-Fi Direct application transport appears unhealthy after " +
                                reason +
                                "; rebuilding the local P2P group"
                        )

                        runCatching {
                            wifiDirectManager.disconnect()
                        }.onFailure { error ->
                            android.util.Log.w(
                                TAG,
                                "Wi-Fi Direct unhealthy-group recovery failed: " +
                                    (error.message ?: error.javaClass.simpleName)
                            )
                        }
                        return@launch
                    }
                } finally {
                    removeSocket(socket)
                }

                val currentInfo = wifiDirectManager.connectionInfo().value
                if (
                    !currentInfo.groupFormed ||
                    currentInfo.isGroupOwner ||
                    currentInfo.groupOwnerAddress != groupOwnerAddress
                ) {
                    return@launch
                }

                kotlinx.coroutines.delay(SOCKET_RETRY_DELAY_MS)
            }
        }
    }
    private fun registerSocket(socket: Socket) {
        writeLocks[socket] = Mutex()
    }

    private suspend fun readLoop(socket: Socket) {
        val input = DataInputStream(socket.getInputStream())

        try {
            while (!socket.isClosed) {
                val frameLength = input.readInt()
                require(frameLength in 1..MAX_FRAME_SIZE) {
                    "invalid Wi-Fi Direct frame length $frameLength"
                }

                val frame = ByteArray(frameLength)
                input.readFully(frame)

                when (frame.firstOrNull()?.toInt()) {
                    FRAME_TYPE_HELLO -> {
                        val hello = decodeHello(frame.copyOfRange(1, frame.size))
                            ?: throw IOException("invalid iTantra Wi-Fi hello")

                        if (intentionallyDisconnectedPeers.contains(hello.deviceId)) {
                            android.util.Log.d(
                                TAG,
                                "Rejecting intentionally disconnected Wi-Fi Direct peer: " +
                                    hello.deviceId
                            )
                            throw IOException("peer intentionally disconnected")
                        }

                        if (
                            peerIdBySocket.isEmpty() &&
                            !wifiDirectManager.connectionInfo().value.isGroupOwner
                        ) {
                            knownGroupOwnerAppDeviceId = hello.deviceId
                            wifiDirectManager.noteGroupOwnerAppDeviceId(
                                hello.deviceId
                            )
                        }

                        val previous = socketsByPeerId.put(hello.deviceId, socket)
                        peerIdBySocket[socket] = hello.deviceId

                        if (previous != null && previous !== socket) {
                            peerIdBySocket.remove(previous)
                            writeLocks.remove(previous)
                            try {
                                previous.close()
                            } catch (_: IOException) {
                            }
                        }

                        android.util.Log.d(
                            TAG,
                            "Wi-Fi Direct peer ready: ${hello.callsign} / ${hello.deviceId}"
                        )
                    }

                    FRAME_TYPE_PACKET -> {
                        // Never accept application packets from an unverified
                        // socket. The hello is the transport-level identity
                        // binding between the TCP endpoint and iTantra DeviceId.
                        if (!peerIdBySocket.containsKey(socket)) {
                            throw IOException("Wi-Fi packet before hello")
                        }

                        val payload = frame.copyOfRange(1, frame.size)
                        if (payload.isNotEmpty()) {
                            incomingPackets.tryEmit(
                                RawPacket(
                                    data = payload,
                                    rssi = UNKNOWN_RSSI,
                                    timestamp = System.currentTimeMillis(),
                                    transport = RadioType.WIFI_DIRECT
                                )
                            )
                        }
                    }

                    else -> throw IOException("unknown Wi-Fi Direct frame type")
                }
            }
        } catch (_: EOFException) {
            // Normal peer disconnect.
        } catch (_: IOException) {
            // Malformed frame or broken socket; the socket is removed below.
        }
    }

    private fun sendHello(socket: Socket) {
        val localCallsign = localCallsignProvider()
        val payload = encodeHello(localDeviceId, localCallsign)

        val frame = ByteArray(payload.size + 1)
        frame[0] = FRAME_TYPE_HELLO.toByte()
        payload.copyInto(frame, destinationOffset = 1)

        DataOutputStream(socket.getOutputStream()).apply {
            writeInt(frame.size)
            write(frame)
            flush()
        }
    }

    private suspend fun sendFramedPacket(
        socket: Socket,
        payload: ByteArray
    ): Boolean {
        if (socket.isClosed || socket.isOutputShutdown) return false

        val lock = writeLocks[socket] ?: return false

        return lock.withLock {
            try {
                val frame = ByteArray(payload.size + 1)
                frame[0] = FRAME_TYPE_PACKET.toByte()
                payload.copyInto(frame, destinationOffset = 1)

                val output = DataOutputStream(socket.getOutputStream())
                output.writeInt(frame.size)
                output.write(frame)
                output.flush()
                true
            } catch (e: IOException) {
                removeSocket(socket)
                false
            }
        }
    }

    private fun removeSocket(socket: Socket) {
        val peerId = peerIdBySocket.remove(socket)
        if (peerId != null) {
            socketsByPeerId.remove(peerId, socket)
        }

        writeLocks.remove(socket)
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }

    private fun closeDataSockets() {
        serverSocket?.let {
            try {
                it.close()
            } catch (_: IOException) {
            }
        }
        serverSocket = null

        socketsByPeerId.values.distinct().forEach { socket ->
            try {
                socket.close()
            } catch (_: IOException) {
            }
        }

        socketsByPeerId.clear()
        peerIdBySocket.clear()
        writeLocks.clear()
    }

    private data class Hello(
        val deviceId: String,
        val callsign: String
    )

    private fun encodeHello(
        deviceId: String,
        callsign: String
    ): ByteArray {
        val idBytes = deviceId.toByteArray(Charsets.UTF_8)
        val callsignBytes = callsign.toByteArray(Charsets.UTF_8)

        require(idBytes.size in 1..MAX_HELLO_FIELD)
        require(callsignBytes.size in 1..MAX_HELLO_FIELD)

        val result = java.io.ByteArrayOutputStream()
        DataOutputStream(result).apply {
            writeByte(PROTOCOL_VERSION)
            writeByte(idBytes.size)
            write(idBytes)
            writeByte(callsignBytes.size)
            write(callsignBytes)
            flush()
        }
        return result.toByteArray()
    }

    private fun decodeHello(payload: ByteArray): Hello? {
        return runCatching {
            DataInputStream(payload.inputStream()).use { input ->
                val version = input.readUnsignedByte()
                if (version != PROTOCOL_VERSION) return null

                val idLength = input.readUnsignedByte()
                if (idLength !in 1..MAX_HELLO_FIELD) return null
                val idBytes = ByteArray(idLength)
                input.readFully(idBytes)

                val callsignLength = input.readUnsignedByte()
                if (callsignLength !in 1..MAX_HELLO_FIELD) return null
                val callsignBytes = ByteArray(callsignLength)
                input.readFully(callsignBytes)

                val deviceId = idBytes.toString(Charsets.UTF_8)
                val callsign = callsignBytes.toString(Charsets.UTF_8)

                if (runCatching {
                        java.util.UUID.fromString(deviceId)
                    }.isFailure
                ) {
                    return null
                }

                Hello(
                    deviceId = deviceId,
                    callsign = callsign.ifBlank { deviceId.take(8) }
                )
            }
        }.getOrNull()
    }

    private data class GroupEndpoint(
        val groupFormed: Boolean,
        val isGroupOwner: Boolean,
        val groupOwnerAddress: String?
    )

    companion object {
        private const val TAG = "WifiDirectRadioTransport"
        private const val STALE_GROUP_ROUTE_FAILURES = 3
        // A freshly formed group can legitimately refuse the TCP socket several
        // times while the group owner application finishes bringing up port 8988.
        // Historical successful connections required up to 11 refusals, so keep
        // this threshold just above that startup behavior before rebuilding the
        // whole P2P group.
        private const val CONNECTION_REFUSED_GROUP_FAILURES = 12
        // A connect timeout already costs the full socket timeout, so three
        // consecutive timeouts are enough evidence that the P2P group is unusable.
        private const val CONNECTION_TIMEOUT_GROUP_FAILURES = 3
        private const val TRANSPORT_PORT = 8988
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val SOCKET_CONNECT_ATTEMPTS = 5
        private const val SOCKET_RETRY_DELAY_MS = 1_000L
        private const val MAX_FRAME_SIZE = 64 * 1024
        private const val MAX_HELLO_FIELD = 255
        private const val PROTOCOL_VERSION = 1
        private const val FRAME_TYPE_HELLO = 1
        private const val FRAME_TYPE_PACKET = 2
        private const val UNKNOWN_RSSI = 0

    }
}
