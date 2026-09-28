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
    private val reconfigureLock = Mutex()

    @Volatile
    private var serverSocket: ServerSocket? = null

    init {
        scope.launch {
            wifiDirectManager.connectionInfo().collectLatest { info ->
                reconfigureLock.withLock {
                    closeDataSockets()

                    if (!info.groupFormed) {
                        return@withLock
                    }

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
        if (serverSocket != null) return

        scope.launch {
            val server = try {
                ServerSocket(TRANSPORT_PORT)
            } catch (e: IOException) {
                android.util.Log.w(
                    TAG,
                    "Wi-Fi Direct TCP server could not start: ${e.message}"
                )
                return@launch
            }

            serverSocket = server

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
                try {
                    server.close()
                } catch (_: IOException) {
                }
            }
        }
    }

    private fun connectToGroupOwner(groupOwnerAddress: String) {
        scope.launch {
            val socket = Socket()

            try {
                socket.tcpNoDelay = true
                socket.connect(
                    InetSocketAddress(groupOwnerAddress, TRANSPORT_PORT),
                    CONNECT_TIMEOUT_MS
                )
                registerSocket(socket)
                sendHello(socket)
                readLoop(socket)
            } catch (e: IOException) {
                android.util.Log.d(
                    TAG,
                    "Wi-Fi Direct group-owner socket not ready: ${e.message}"
                )
            } finally {
                removeSocket(socket)
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
        val localDeviceId = LOCAL_DEVICE_ID_PLACEHOLDER
        val localCallsign = LOCAL_CALLSIGN_PLACEHOLDER
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

    companion object {
        private const val TAG = "WifiDirectRadioTransport"
        private const val TRANSPORT_PORT = 8988
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val MAX_FRAME_SIZE = 64 * 1024
        private const val MAX_HELLO_FIELD = 255
        private const val PROTOCOL_VERSION = 1
        private const val FRAME_TYPE_HELLO = 1
        private const val FRAME_TYPE_PACKET = 2
        private const val UNKNOWN_RSSI = 0

        /**
         * Filled by DI in the next Wi-Fi implementation phase. Keeping the
         * transport identity boundary explicit prevents use of the Android
         * device name/MAC as the application's stable identity.
         */
        private const val LOCAL_DEVICE_ID_PLACEHOLDER = ""
        private const val LOCAL_CALLSIGN_PLACEHOLDER = ""
    }
}
