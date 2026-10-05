package com.tactical.app.di

import android.content.Context
import com.tactical.platform.api.audio.AudioRecorder
import com.tactical.platform.api.haptics.HapticEngine
import com.tactical.platform.api.ble.BleBeaconAdvertiser
import com.tactical.platform.api.ble.BleConnectionManager
import com.tactical.platform.api.ble.BleBeaconScanner
import com.tactical.platform.api.wifi.WifiDirectManager
import com.tactical.platform.api.squad.SquadMembershipStore
import com.tactical.platform.ble.AndroidBleAdvertiser
import com.tactical.platform.ble.AndroidBleConnectionManager
import com.tactical.platform.ble.AndroidBleScanner
import com.tactical.platform.wifi.AndroidWifiDirectManager
import com.tactical.platform.audio.AndroidAudioRecordRecorder
import com.tactical.platform.audio.AlertToneGenerator
import com.tactical.platform.audio.AndroidAudioTrackPlayer
import com.tactical.platform.api.audio.AudioPlayer
import com.tactical.platform.api.alarm.AlarmBypass
import com.tactical.platform.alarm.AndroidAlarmBypass
import com.tactical.platform.api.flashlight.FlashlightController
import com.tactical.platform.flashlight.CameraFlashlightController
import com.tactical.platform.haptics.AndroidHapticEngine
import android.net.wifi.p2p.WifiP2pManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PlatformModule {

    @Provides
    @Singleton
    fun provideAudioRecorder(): AudioRecorder =
        AndroidAudioRecordRecorder()

    @Provides
    @Singleton
    fun provideAlertToneGenerator(): AlertToneGenerator =
        AlertToneGenerator()

    @Provides
    @Singleton
    fun provideAudioPlayer(
        alertToneGenerator: AlertToneGenerator
    ): AudioPlayer =
        AndroidAudioTrackPlayer(alertToneGenerator)

    @Provides
    @Singleton
    fun provideAlarmBypass(
        @ApplicationContext context: Context
    ): AlarmBypass =
        AndroidAlarmBypass(context)

    @Provides
    @Singleton
    fun provideFlashlightController(
        @ApplicationContext context: Context
    ): FlashlightController =
        CameraFlashlightController(context)

    @Provides
    @Singleton
    fun provideHapticEngine(
        @ApplicationContext context: Context
    ): HapticEngine =
        AndroidHapticEngine(context)

    @Provides
    @Singleton
    fun provideBleBeaconAdvertiser(
        @ApplicationContext context: Context
    ): BleBeaconAdvertiser =
        AndroidBleAdvertiser(context)

    @Provides
    @Singleton
    fun provideBleBeaconScanner(
        @ApplicationContext context: Context
    ): BleBeaconScanner =
        AndroidBleScanner(context)

    @Provides
    @Singleton
    fun provideBleConnectionManager(
        @ApplicationContext context: Context,
        registry: com.tactical.platform.radio.BleConnectionRegistry,
        identityStore: DeviceIdentityStore,
        squadMembershipStore: SquadMembershipStore,
        wifiDirectManager: WifiDirectManager
    ): BleConnectionManager =
        AndroidBleConnectionManager(
            context = context,
            registry = registry,
            localDeviceId = identityStore.deviceIdValue,
            squadMembershipStore = squadMembershipStore,
            wifiDirectManager = wifiDirectManager
        )

    @Provides
    @Singleton
    fun provideWifiDirectManager(
        @ApplicationContext context: Context,
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        squadMembershipStore: SquadMembershipStore,
        identityStore: DeviceIdentityStore
    ): WifiDirectManager =
        AndroidWifiDirectManager(
            context = context,
            wifiP2pManager = manager,
            wifichannel = channel,
            squadMembershipStore = squadMembershipStore,
            localDeviceId = identityStore.deviceIdValue
        )
}