package com.tactical.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.tactical.app.TacticalApplication
import com.tactical.app.ui.MainActivity
import com.tactical.app.di.DeviceIdentityStore
import com.tactical.app.di.LocalAppDataStore
import com.tactical.emergency.receiver.EmergencyReceiver
import com.tactical.emergency.squelch.SquelchBreaker
import com.tactical.engine.discovery.service.DiscoveryService
import com.tactical.engine.mesh.service.MeshService
import com.tactical.platform.speech.mms.MmsTtsLanguage
import com.tactical.ptt.relay.PttPacketBuilder
import com.tactical.platform.speech.mms.MmsTtsPlaybackCoordinator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@AndroidEntryPoint
class TacticalMeshService : Service() {

    @Inject
    lateinit var meshService: MeshService

    @Inject
    lateinit var bleConnectionManager: com.tactical.platform.api.ble.BleConnectionManager

    @Inject
    lateinit var discoveryService: DiscoveryService

    @Inject
    lateinit var emergencyReceiver: EmergencyReceiver

    @Inject
    lateinit var emergencySquelchBreaker: SquelchBreaker

    @Inject
    lateinit var emergencyAlertNotifier: EmergencyAlertNotifier

    @Inject
    lateinit var messageNotificationNotifier: MessageNotificationNotifier

    @Inject
    lateinit var localAppDataStore: LocalAppDataStore

    @Inject
    lateinit var identityStore: DeviceIdentityStore

    @Inject
    lateinit var mmsTtsPlaybackCoordinator: MmsTtsPlaybackCoordinator

    // Materialize the singleton mesh squad-control coordinator from the
    // foreground service so mesh squad requests are received even when the
    // Activity/ViewModel is not alive.
    @Inject
    lateinit var meshSquadControlCoordinator: MeshSquadControlCoordinator

    private val serviceScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val binder = MeshBinder()
    private var emergencyJob: kotlinx.coroutines.Job? = null
    private var messageJob: kotlinx.coroutines.Job? = null
    private var discoveryConnectionJob: kotlinx.coroutines.Job? = null

    inner class MeshBinder : Binder() {
        fun getService(): TacticalMeshService = this@TacticalMeshService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())

        if (intent?.action == ACTION_REFRESH_WIFI_DIRECT) {
            serviceScope.launch {
                runCatching {
                    (discoveryService as? com.tactical.engine.discovery.service.DefaultDiscoveryService)
                        ?.refreshWifiDirect()
                }.onFailure { error ->
                    android.util.Log.w(
                        "TacticalMeshService",
                        "Failed to refresh Wi-Fi Direct after permission grant",
                        error
                    )
                }
            }
            return START_STICKY
        }

        // Injecting MeshService causes the mesh engine to be created.
        // DiscoveryService requires an explicit start().
        if (emergencyJob?.isActive != true) {
            emergencyJob = serviceScope.launch {
                emergencyReceiver.incoming().collect { packet ->
                    val senderName =
                        localAppDataStore.callsignForPeer(packet.sender.value)
                            ?: packet.sender.value.take(8)

                    // Persist emergencies in the foreground service so an
                    // alert received while the Activity is closed is available
                    // when Messages is opened later. LocalAppDataStore
                    // deduplicates the service/UI observers if both are active.
                    val isNewEmergency = localAppDataStore.saveReceivedMessage(
                        com.tactical.app.di.StoredReceivedMessage(
                            senderId = packet.sender.value,
                            senderName = senderName,
                            text = packet.description,
                            timestampEpochMs = packet.timestamp,
                            isVoice = false,
                            isAlert = true,
                            severity = packet.severity.name,
                            languageCode = packet.languageCode,
                            locationLatitude = packet.location?.latitude,
                            locationLongitude = packet.location?.longitude,
                            locationAccuracyMeters = packet.location?.accuracyMeters
                        )
                    )

                    if (!isNewEmergency) return@collect

                    emergencyAlertNotifier.show(
                        packet = packet,
                        senderName = senderName
                    )

                    runCatching {
                        emergencySquelchBreaker.breakSquelch(packet)
                    }.onFailure { error ->
                        android.util.Log.e(
                            "TacticalMeshService",
                            "Emergency alert playback failed",
                            error
                        )
                    }
                }
            }
        }

        // Normal/voice messages are also handled here so notifications
        // continue to work when the Activity/ViewModel is not alive.
        if (messageJob?.isActive != true) {
            messageJob = serviceScope.launch {
                meshService.receive().collect { packet ->
                    if (packet is com.tactical.domain.packet.TextPacket) {
                        // A routed mesh packet can come back to its original
                        // sender. Never treat our own packet as incoming, or
                        // the device will notify and speak its own message.
                        if (packet.sender.value == identityStore.deviceIdValue) {
                            return@collect
                        }

                        val senderName =
                            localAppDataStore.callsignForPeer(packet.sender.value)
                                ?: packet.sender.value.take(8)
                        val isVoiceMessage = packet.languageCode != "und"
                        val displayText = PttPacketBuilder.toDisplayText(packet.text)

                        val isNewMessage = localAppDataStore.saveReceivedMessage(
                            com.tactical.app.di.StoredReceivedMessage(
                                senderId = packet.sender.value,
                                senderName = senderName,
                                text = displayText,
                                timestampEpochMs = packet.timestamp,
                                isVoice = isVoiceMessage,
                                isCallMode = packet.isCallMode
                            )
                        )

                        if (isNewMessage) {
                            messageNotificationNotifier.show(
                                senderName = senderName,
                                message = displayText,
                                isVoice = isVoiceMessage
                            )
                        }

                        if (isNewMessage && isVoiceMessage) {
                            val language = MmsTtsLanguage.fromIsoCode(packet.languageCode)
                            if (language != null) {
                                mmsTtsPlaybackCoordinator.enqueue(
                                    senderId = packet.sender.value,
                                    language = language,
                                    text = packet.text
                                )
                            }
                        }
                    }
                }
            }
        }

        // Re-establish application-level squad GATT sessions from the
        // foreground service after process/activity recreation.
        serviceScope.launch {
            delay(1000L)
            bleConnectionManager.squadDeviceIds().forEach { peerId ->
                runCatching {
                    bleConnectionManager.reconnectSquadMember(peerId)
                }
            }
        }

        discoveryConnectionJob?.cancel()
        discoveryConnectionJob = serviceScope.launch {
            try {
                discoveryService.start()
                // Keep the foreground service's discovery alive even when the
                // Activity is not running. Physical GATT reconnection is handled
                // only for saved squad members by BleConnectionManager.
                (discoveryService as? com.tactical.engine.discovery.service.DefaultDiscoveryService)
                    ?.startDiscovery()
            } catch (e: SecurityException) {
                android.util.Log.e(
                    "TacticalMeshService",
                    "Missing permission required for mesh discovery",
                    e
                )
            } catch (e: Exception) {
                android.util.Log.e(
                    "TacticalMeshService",
                    "Failed to start mesh discovery",
                    e
                )
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Some OEMs stop foreground services when the app task is swiped
        // away. Re-assert the mesh service here so the persistent foreground
        // service can keep BLE discovery/connection alive after the Activity
        // is dismissed.
        runCatching {
            ContextCompat.startForegroundService(
                applicationContext,
                Intent(applicationContext, TacticalMeshService::class.java)
            )
        }.onFailure { error ->
            android.util.Log.w(
                "TacticalMeshService",
                "Could not re-assert mesh service after task removal",
                error
            )
        }

        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        emergencyJob?.cancel()
        emergencyJob = null
        messageJob?.cancel()
        messageJob = null

        discoveryConnectionJob?.cancel()
        discoveryConnectionJob = null
        runCatching {
            runBlocking(Dispatchers.IO) { discoveryService.stop() }
        }

        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, TacticalApplication.CHANNEL_MESH)
            .setContentTitle("Itantra Mesh Active")
            .setContentText("Wi-Fi Direct / BLE mesh service running")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_REFRESH_WIFI_DIRECT =
            "com.tactical.app.action.REFRESH_WIFI_DIRECT"
        private const val NOTIFICATION_ID = 1001
    }
}