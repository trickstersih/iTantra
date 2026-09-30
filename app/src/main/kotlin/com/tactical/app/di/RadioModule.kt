package com.tactical.app.di

import android.content.Context
import android.net.wifi.p2p.WifiP2pManager
import com.tactical.platform.api.radio.RadioTransport
import com.tactical.platform.radio.BleConnectionRegistry
import com.tactical.platform.radio.BleRadioTransport
import com.tactical.platform.radio.CompositeRadioTransport
import com.tactical.platform.radio.WifiDirectRadioTransport
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RadioModule {

    @Provides
    @Singleton
    fun provideWifiP2pManager(@ApplicationContext context: Context): WifiP2pManager =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager

    @Provides
    @Singleton
    fun provideWifiP2pChannel(
        @ApplicationContext context: Context,
        manager: WifiP2pManager
    ): WifiP2pManager.Channel =
        manager.initialize(context, context.mainLooper, null)

    @Provides
    @Singleton
    fun provideBleConnectionRegistry(): BleConnectionRegistry = BleConnectionRegistry()

    @Provides
    @Singleton
    fun provideBleRadioTransport(
        @ApplicationContext context: Context,
        registry: BleConnectionRegistry
    ): BleRadioTransport = BleRadioTransport(context, registry)

    @Provides
    @Singleton
    fun provideWifiDirectRadioTransport(
        wifiDirectManager: com.tactical.platform.api.wifi.WifiDirectManager,
        identityStore: DeviceIdentityStore
    ): WifiDirectRadioTransport = WifiDirectRadioTransport(
        wifiDirectManager = wifiDirectManager,
        localDeviceId = identityStore.deviceIdValue,
        localCallsignProvider = identityStore::callsign
    )

    @Provides
    @Singleton
    fun provideRadioTransport(
        bleTransport: BleRadioTransport,
        wifiDirectTransport: WifiDirectRadioTransport
    ): RadioTransport = CompositeRadioTransport(bleTransport, wifiDirectTransport)
}