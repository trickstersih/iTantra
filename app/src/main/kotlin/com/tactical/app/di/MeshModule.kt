package com.tactical.app.di

import com.tactical.domain.identity.DeviceId
import com.tactical.engine.discovery.beacon.BeaconEmitter
import com.tactical.engine.discovery.beacon.PeriodicBeaconEmitter
import com.tactical.engine.discovery.catalog.DeviceCatalog
import com.tactical.engine.discovery.catalog.InMemoryDeviceCatalog
import com.tactical.engine.discovery.scanner.BeaconScanner
import com.tactical.engine.discovery.scanner.CompositeBeaconScanner
import com.tactical.engine.discovery.service.DefaultDiscoveryService
import com.tactical.engine.discovery.service.DiscoveryService
import com.tactical.engine.mesh.deduplication.DeduplicationFilter
import com.tactical.engine.mesh.deduplication.RollingBloomFilter
import com.tactical.engine.mesh.quality.LinkQualityMonitor
import com.tactical.engine.mesh.quality.RssiLinkQualityMonitor
import com.tactical.engine.mesh.router.FloodMeshRouter
import com.tactical.engine.mesh.router.MeshRouter
import com.tactical.engine.mesh.service.DefaultMeshService
import com.tactical.engine.mesh.service.MeshService
import com.tactical.engine.mesh.ttl.DecrementingTtlTracker
import com.tactical.engine.mesh.ttl.TtlTracker
import com.tactical.platform.api.radio.RadioTransport
import com.tactical.protocol.hashing.PacketHasher
import com.tactical.protocol.hashing.XxHashPacketHasher
import com.tactical.protocol.serialization.BinaryPacketSerializer
import com.tactical.protocol.serialization.PacketSerializer
import com.tactical.platform.api.ble.BleBeaconAdvertiser
import com.tactical.platform.api.ble.BleBeaconScanner
import com.tactical.platform.api.ble.BleConnectionManager
import com.tactical.platform.api.wifi.WifiDirectManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MeshModule {

    @Provides
    @Singleton
    @LocalDeviceIdValue
    fun provideLocalDeviceIdValue(identityStore: DeviceIdentityStore): String =
        identityStore.deviceIdValue

    @Provides
    @Singleton
    @LocalCallsign
    fun provideLocalCallsign(identityStore: DeviceIdentityStore): String =
        identityStore.callsign

    @Provides
    @Singleton
    fun providePacketSerializer(): PacketSerializer = BinaryPacketSerializer()

    @Provides
    @Singleton
    fun providePacketHasher(): PacketHasher = XxHashPacketHasher()

    @Provides
    @Singleton
    fun provideDeduplicationFilter(): DeduplicationFilter = RollingBloomFilter()

    @Provides
    @Singleton
    fun provideTtlTracker(): TtlTracker = DecrementingTtlTracker()

    @Provides
    @Singleton
    fun provideLinkQualityMonitor(): LinkQualityMonitor = RssiLinkQualityMonitor()

    @Provides
    @Singleton
    fun provideDeviceCatalog(): DeviceCatalog = InMemoryDeviceCatalog()

    @Provides
    @Singleton
    fun provideMeshRouter(
        @LocalDeviceIdValue localDeviceIdValue: String,
        hasher: PacketHasher,
        dedup: DeduplicationFilter,
        ttlTracker: TtlTracker
    ): MeshRouter = FloodMeshRouter(
        DeviceId(localDeviceIdValue),
        hasher,
        dedup,
        ttlTracker
    )

    @Provides
    @Singleton
    fun provideMeshService(
        @LocalDeviceIdValue localDeviceIdValue: String,
        router: MeshRouter,
        serializer: PacketSerializer,
        transport: RadioTransport,
        qualityMonitor: LinkQualityMonitor,
        bleConnectionManager: BleConnectionManager
    ): MeshService = DefaultMeshService(
        localDeviceId = DeviceId(localDeviceIdValue),
        router = router,
        serializer = serializer,
        transport = transport,
        qualityMonitor = qualityMonitor,
        squadDeviceIdsProvider = bleConnectionManager::squadDeviceIds
    )

    @Provides
    @Singleton
    fun provideBeaconEmitter(
        @LocalDeviceIdValue localDeviceIdValue: String,
        identityStore: DeviceIdentityStore,
        transport: RadioTransport,
        serializer: PacketSerializer,
        bleAdvertiser: BleBeaconAdvertiser
    ): BeaconEmitter = PeriodicBeaconEmitter(
        localDeviceId = DeviceId(localDeviceIdValue),
        callsignProvider = identityStore::callsign,
        transport = transport,
        serializer = serializer,
        bleAdvertiser = bleAdvertiser
    )

    @Provides
    @Singleton
    fun provideBeaconScanner(
        bleScanner: BleBeaconScanner,
        transport: RadioTransport,
        serializer: PacketSerializer,
        @LocalDeviceIdValue localDeviceIdValue: String
    ): BeaconScanner = CompositeBeaconScanner(
        bleScanner = bleScanner,
        radioTransport = transport,
        serializer = serializer,
        localDeviceId = localDeviceIdValue
    )

    @Provides
    @Singleton
    fun provideDiscoveryService(
        scanner: BeaconScanner,
        catalog: DeviceCatalog,
        emitter: BeaconEmitter,
        wifiDirectManager: WifiDirectManager,
        @LocalDeviceIdValue localDeviceIdValue: String,
        identityStore: DeviceIdentityStore
    ): DiscoveryService =
        DefaultDiscoveryService(
            scanner = scanner,
            catalog = catalog,
            emitter = emitter,
            wifiDirectManager = wifiDirectManager,
            localDeviceId = localDeviceIdValue,
            localCallsignProvider = identityStore::callsign
        )
}
